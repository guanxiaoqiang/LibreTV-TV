package com.libretv.tv.domain.model

import kotlinx.serialization.Serializable

/**
 * 远程订阅：一份「以远端为准」的采集源清单。
 *
 * 订阅与手工添加的源是两回事——订阅的内容由远端决定，下一次同步会覆盖
 * 观众对该源的改动；手工源完全不受订阅影响（design D1）。
 */
@Serializable
data class Subscription(
    /** 稳定标识：订阅的增删改与源归属都靠它。 */
    val id: String,
    /** 展示名（观众自己起）。 */
    val name: String,
    /** 订阅地址（HTTP/HTTPS）。 */
    val url: String,
    /** 刷新周期（天）。0 = 关闭自动同步。 */
    val refreshDays: Int = SubscriptionIntervals.OFF,
    /** 自动同步状态。 */
    val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    /** 连续失败次数；达到 [SubscriptionRules.MAX_FAILURES] 就自动暂停。 */
    val failureCount: Int = 0,
    /** 上次同步时间（毫秒）。0 = 从未同步过。 */
    val lastSyncAt: Long = 0L
) {
    val paused: Boolean get() = status == SubscriptionStatus.PAUSED

    /** 自动同步是否到期（从未同步过也算到期）。 */
    fun isDue(now: Long): Boolean {
        if (paused || refreshDays == SubscriptionIntervals.OFF) return false
        if (lastSyncAt <= 0L) return true
        return now - lastSyncAt >= refreshDays * SubscriptionRules.DAY_MS
    }
}

/** 订阅状态。 */
@Serializable
enum class SubscriptionStatus {
    /** 自动同步中。 */
    ACTIVE,

    /** 连续失败过多，已暂停自动检查（等观众手动同步或改周期）。 */
    PAUSED
}

/** 订阅相关的固定规则。 */
object SubscriptionRules {
    /** 一天（毫秒）。 */
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** 单次同步最多处理几个订阅。 */
    const val MAX_SUBSCRIPTIONS_PER_RUN = 5

    /** 单次同步的并发数。 */
    const val SYNC_CONCURRENCY = 2

    /** 连续失败多少次后自动暂停。 */
    const val MAX_FAILURES = 3

    /** 单个订阅最多导入多少个点播源。 */
    const val MAX_SOURCES_PER_SUBSCRIPTION = 100
}

/** 刷新周期的可选值与文案。 */
object SubscriptionIntervals {
    /** 关闭自动同步。 */
    const val OFF = 0

    /** 允许的周期（天）。 */
    val ALLOWED = listOf(OFF, 1, 3, 7, 14, 30)

    /** 是否是受支持的周期。 */
    fun isAllowed(days: Int): Boolean = days in ALLOWED

    /** 周期文案。 */
    fun label(days: Int): String = when (days) {
        OFF -> "关闭"
        1 -> "每天"
        3 -> "每 3 天"
        7 -> "每周"
        14 -> "每两周"
        30 -> "每月"
        else -> "每 $days 天"
    }
}
