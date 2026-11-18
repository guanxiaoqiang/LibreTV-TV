package com.libretv.tv.domain.model

/**
 * 采集失败对来源健康度的影响（local-first-storage 5.7 / content-catalog 5.7）。
 *
 * 与观看源的阶梯冷却相互独立：采集失败只降低**采集优先级**并拉长采集间隔，
 * 不影响观看时该源是否可用。连续失败越多，采集越靠后直至暂停采集。
 */
object HarvestHealth {

    /** 连续失败次数对应的采集优先级增量（越大越靠后）。 */
    fun priorityPenalty(consecutiveFailures: Int): Int = when {
        consecutiveFailures <= 0 -> 0
        consecutiveFailures == 1 -> 2
        consecutiveFailures == 2 -> 4
        else -> 8
    }

    /** 是否暂停采集该源（连续失败达到阈值）。 */
    fun shouldPauseHarvest(consecutiveFailures: Int): Boolean = consecutiveFailures >= 3

    /** 采集间隔随失败次数拉长（毫秒）。 */
    fun harvestIntervalMs(baseMs: Long, consecutiveFailures: Int): Long =
        baseMs * (1L + consecutiveFailures.coerceAtMost(4))
}
