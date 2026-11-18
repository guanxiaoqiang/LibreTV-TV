package com.libretv.tv.domain.model

import com.libretv.tv.core.SourceError

/**
 * 源健康度的三态。
 *
 * 「能不能用」不是布尔值：一个超时一次的源和一个连 DNS 都解析不了的源
 * 应该得到不同的对待。阶梯冷却模型（`benchmark.md` B1）把坏源的代价
 * 从「每次都重新踩一遍」变成「越坏停得越久」。
 */
enum class HealthState {
    /** 正常参与扇出。 */
    HEALTHY,

    /** 冷却中：本轮跳过，到期后半开探测。 */
    COOLDOWN,

    /** 长期停用：永久跳过，需要用户手动恢复。 */
    SUSPENDED
}

/**
 * 一个源的运行时健康状态。
 *
 * [reachable] / [latencyMs] / [error] 是「这一次探测」的即时结果；
 * [state] / [failureTier] / [cooldownUntil] 是跨请求累积的结论。
 * 后者必须持久化（P2-10），否则重启后 tier 归零，阶梯冷却失效。
 */
data class SourceHealth(
    val reachable: Boolean,
    val latencyMs: Long = 0L,
    /** 这一次探测的失败原因；成功时为 `null`。 */
    val error: String? = null,
    /** 结构化的失败原因，供阶梯冷却决策使用。 */
    val lastError: SourceError? = null,
    /** 三态：正常 / 冷却中 / 停用。 */
    val state: HealthState = HealthState.HEALTHY,
    /** 阶梯档位：0 正常 / 1 停 30 分钟 / 2 停 24 小时 / 3 长期停用。 */
    val failureTier: Int = 0,
    /** 冷却到期时间戳；非冷却态为 0。 */
    val cooldownUntil: Long = 0L
) {
    /** 冷却剩余毫秒数；未在冷却中返回 0。 */
    fun cooldownRemainingMs(now: Long = System.currentTimeMillis()): Long =
        (cooldownUntil - now).coerceAtLeast(0L)

    /** 是否应该在本轮扇出里跳过。 */
    fun isSkippable(now: Long = System.currentTimeMillis()): Boolean = when (state) {
        HealthState.SUSPENDED -> true
        HealthState.COOLDOWN -> cooldownUntil > now
        HealthState.HEALTHY -> false
    }
}

/** One row of the source settings list. */
data class SourceEntry(
    val config: SourceConfig,
    /** False when the viewer switched this source off. */
    val enabled: Boolean = true,
    /** True for sources the viewer added by hand. */
    val custom: Boolean = false
) {
    val name: String get() = config.name
    val url: String get() = config.url
}

