package com.libretv.tv.core

import com.libretv.tv.data.SearchResult
import com.libretv.tv.data.SourceConfig
import com.libretv.tv.domain.gateway.CatalogGateway
import com.libretv.tv.domain.gateway.ContentGateway
import com.libretv.tv.domain.model.CatalogOffer
import com.libretv.tv.domain.model.CatalogTitle
import com.libretv.tv.domain.model.HarvestHealth
import com.libretv.tv.domain.model.normalizeTitle

/**
 * 内容目录增量采集器（local-first-storage 5.2 / content-catalog）。
 *
 * 从分类货架拉取条目，转成为「标题 + 来源投影」写入本地目录——目录不含
 * 播放地址（D5）。采集遵循：有效期内且无新增则跳过、分页上限、配额隔离、
 * 失败计入来源健康度且按阶梯降级。
 */
class CatalogHarvester(
    private val content: ContentGateway,
    private val catalog: CatalogGateway,
    private val quota: HarvestQuota,
    private val scheduler: CatalogScheduler,
    private val maxPages: Int = 2,
    private val baseIntervalMs: Long = 6 * 3_600_000L
) {

    /** 采集统计，供源管理页显示「采集中 / 最后更新」。 */
    data class HarvestResult(
        val sourceUrl: String,
        val collected: Int,
        val skipped: Boolean,
        val failed: Boolean
    )

    /** 采集一个来源的某分类（P1 首页目录任务）。 */
    suspend fun harvestCategory(
        source: SourceConfig,
        typeId: Int,
        requestedPages: Int = 1,
        now: Long = System.currentTimeMillis()
    ): HarvestResult {
        val sourceUrl = source.url
        // 健康降级：暂停采集的来源直接跳过。
        val health = healthBySource[sourceUrl] ?: 0
        if (HarvestHealth.shouldPauseHarvest(health)) {
            return HarvestResult(sourceUrl, 0, skipped = true, failed = false)
        }

        // 有效期 + 配额判定。
        val fresh = catalog.isFresh("category:$sourceUrl:$typeId", now, baseIntervalMs)
        val interval = HarvestHealth.harvestIntervalMs(baseIntervalMs, health)
        if (HarvestPolicy.shouldSkip(now - (lastRun[sourceUrl] ?: 0L), now, interval) && fresh) {
            return HarvestResult(sourceUrl, 0, skipped = true, failed = false)
        }
        val pages = HarvestPolicy.pagesToFetch(requestedPages, maxPages)

        var collected = 0
        var failed = false
        for (page in 1..pages) {
            if (!quota.canAcquire(sourceUrl, now)) break
            quota.acquire(sourceUrl, now)
            val rows = runCatching { content.categoryShelf(source, typeId, page) }
                .onFailure { failed = true }
                .getOrNull()
                .also { quota.release() }
                ?: continue
            collected += store(source, rows, now)
        }

        if (failed) recordFailure(sourceUrl)
        lastRun[sourceUrl] = now
        return HarvestResult(sourceUrl, collected, skipped = false, failed = failed)
    }

    /** 把货架条目写进本地目录（标题 + 来源投影，无播放地址）。 */
    private suspend fun store(source: SourceConfig, rows: List<SearchResult>, now: Long): Int {
        rows.forEach { row ->
            val item = row.item
            val titleKey = normalizeTitle(item.vodName)
            if (titleKey.isBlank()) return@forEach
            catalog.upsert(
                title = CatalogTitle(
                    titleKey = titleKey,
                    title = item.vodName,
                    poster = item.vodPic,
                    year = item.vodYear,
                    area = item.vodArea,
                    typeName = item.typeName,
                    updatedAt = now
                ),
                offers = listOf(
                    CatalogOffer(
                        titleKey = titleKey,
                        sourceUrl = source.url,
                        sourceName = source.name,
                        vodId = item.vodId,
                        quality = "",
                        remark = item.vodRemarks,
                        latencyMs = row.latencyMs.toLong(),
                        updatedAt = now
                    )
                ),
                episodes = emptyList() // 集名由详情补采填充（D5：播放时实时解析）
            )
        }
        return rows.size
    }

    /** 采集失败：累加该来源连续失败次数并降低采集优先级。 */
    suspend fun recordFailure(sourceUrl: String) {
        val failures = (healthBySource[sourceUrl] ?: 0) + 1
        healthBySource[sourceUrl] = failures
        catalog.recordHarvestFailure(sourceUrl, System.currentTimeMillis())
    }

    /** 采集成功：清零失败计数。 */
    fun recordSuccess(sourceUrl: String) {
        healthBySource[sourceUrl] = 0
    }

    fun healthOf(sourceUrl: String): Int = healthBySource[sourceUrl] ?: 0

    /** 把 P0/P1 等任务入队，由调度器按优先级出队执行。 */
    fun enqueue(task: com.libretv.tv.domain.model.CatalogTask) = scheduler.enqueue(task)

    private val healthBySource = mutableMapOf<String, Int>()
    private val lastRun = mutableMapOf<String, Long>()
}
