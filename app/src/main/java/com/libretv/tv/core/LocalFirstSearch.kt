package com.libretv.tv.core

import com.libretv.tv.domain.model.CatalogTitle

/**
 * 本地优先搜索编排（local-first-storage 6.2 / 6.3 / 6.4，content-catalog §6）。
 *
 * 规则：
 *  1. 先查本地索引，命中立即返回（不发起网络）；
 *  2. 本地无命中 → 回退并发扇出，并把关键词写入待补采队列；
 *  3. 后台补采返回的新来源**追加**到既有结果之后，顺序与位置不变（D8）；
 *  4. 无命中关键词进入队列，供采集器后续消费。
 *
 * 网络调用以函数参数注入，因此整个决策过程可纯 JVM 单测。
 */
class LocalFirstSearch {

    /** 一次搜索的结果。 */
    data class Outcome(
        val results: List<CatalogTitle>,
        val fromLocal: Boolean,
        val fannedOut: Boolean,
        val enqueuedBackfill: Boolean
    )

    private val backfillQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /**
     * @param localLookup 本地索引查询（命中返回非空）
     * @param fanout 全源并发扇出（仅无命中时调用）
     */
    suspend fun search(
        keyword: String,
        localLookup: suspend (String) -> List<CatalogTitle>,
        fanout: suspend (String) -> List<CatalogTitle>
    ): Outcome {
        val local = localLookup(keyword)
        if (local.isNotEmpty()) {
            // 本地命中：立即返回；补采由后台稍后进行，不在关键路径。
            backfillQueue.offer(keyword)
            return Outcome(local, fromLocal = true, fannedOut = false, enqueuedBackfill = true)
        }
        // 无命中：回退扇出，并登记待补采。
        backfillQueue.offer(keyword)
        val remote = fanout(keyword)
        return Outcome(remote, fromLocal = false, fannedOut = true, enqueuedBackfill = true)
    }

    /**
     * 追加后台补采结果（6.3）：新条目放最后，已有条目的顺序和位置完全不变。
     */
    fun appendBackfill(
        existing: List<CatalogTitle>,
        backfilled: List<CatalogTitle>
    ): List<CatalogTitle> {
        val existingKeys = existing.map { it.titleKey }.toSet()
        return existing + backfilled.filterNot { it.titleKey in existingKeys }
    }

    /** 待补采队列里的下一个关键词；空则返回 null。 */
    fun pollBackfill(): String? = backfillQueue.poll()

    /** 待补采数量。 */
    fun backfillSize(): Int = backfillQueue.size
}
