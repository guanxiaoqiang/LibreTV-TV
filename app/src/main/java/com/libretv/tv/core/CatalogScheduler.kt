package com.libretv.tv.core

import com.libretv.tv.domain.model.CatalogPriority
import com.libretv.tv.domain.model.CatalogTask

/**
 * 采集任务优先级队列（local-first-storage 5.3 / content-catalog 2.3）。
 *
 * 出队顺序固定：下一集（P0）→ 首页目录（P1）→ 已搜未看详情（P2）→
 * 健康巡检（P3）。同优先级按入队时间 FIFO。纯 Kotlin，可 JVM 单测。
 *
 * 用 MutableList + 显式排序而非 java.util.PriorityQueue——后者需要 API 24，
 * 而本项目 minSdk 23。
 */
class CatalogScheduler {

    private data class Queued(val seq: Long, val task: CatalogTask)

    private val queue = mutableListOf<Queued>()
    private var sequence = 0L

    /** 入队一个任务。 */
    fun enqueue(task: CatalogTask) {
        queue.add(Queued(sequence++, task))
    }

    /** 按优先级出队（同级 FIFO），空队列返回 null。 */
    fun poll(): CatalogTask? {
        if (queue.isEmpty()) return null
        val best = queue.withIndex().minWithOrNull(
            compareBy<IndexedValue<Queued>> { it.value.task.priority.rank }.thenBy { it.value.seq }
        ) ?: return null
        queue.removeAt(best.index)
        return best.value.task
    }

    /** 当前排队数量。 */
    fun size(): Int = queue.size

    /** 清空队列。 */
    fun clear() = queue.clear()
}

/**
 * 增量采集的跳过判定（5.2 / content-catalog 2.2）。
 *
 * 有效期内且上游未报告新增 → 跳过；分页不得超过上限。纯函数，可单测。
 */
object HarvestPolicy {

    /** 该条目是否跳过采集。 */
    fun shouldSkip(
        updatedAt: Long,
        now: Long,
        ttlMs: Long,
        upstreamHasNew: Boolean = false
    ): Boolean = !upstreamHasNew && (now - updatedAt) < ttlMs

    /** 本次要抓的页数（不超过 [maxPages]）。 */
    fun pagesToFetch(requested: Int, maxPages: Int): Int =
        requested.coerceIn(1, maxPages)
}
