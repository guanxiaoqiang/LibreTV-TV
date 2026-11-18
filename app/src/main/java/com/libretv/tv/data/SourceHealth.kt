package com.libretv.tv.data

import com.libretv.tv.core.SourceError
import com.libretv.tv.core.userMessage

// 健康度模型已迁至 `domain/model/Health.kt`（`data/Models.kt` 里的 typealias 保持旧引用可用）。

/**
 * 阶梯冷却的时长。
 *
 * 连续失败 → 停 30 分钟 → 停 24 小时 → 长期停用。
 * 冷却期内再次失败会升一级，成功则复位。
 */
object CooldownTiers {
    /** tier 1：停 30 分钟。 */
    const val TIER_1_MS: Long = 30 * 60 * 1000L

    /** tier 2：停 24 小时。 */
    const val TIER_2_MS: Long = 24 * 60 * 60 * 1000L

    /** tier 3：长期停用，不再有自动到期。 */
    const val TIER_SUSPENDED = 3

    fun durationMs(tier: Int): Long = when (tier) {
        1 -> TIER_1_MS
        2 -> TIER_2_MS
        else -> Long.MAX_VALUE / 2
    }
}

/**
 * 阶梯冷却状态机。
 *
 * 纯函数实现：输入上一次的状态与这一次的探测结果，输出新状态。
 * 无副作用意味着可以直接单测，也可以由调用方决定往哪里持久化。
 */
object SourceHealthTracker {

    /** 请求成功：tier 复位，立即恢复参与。 */
    fun onSuccess(previous: SourceHealth): SourceHealth = previous.copy(
        reachable = true,
        error = null,
        lastError = null,
        state = HealthState.HEALTHY,
        failureTier = 0,
        cooldownUntil = 0L
    )

    /** 请求失败：tier 进一档，冷却时长随之加长。 */
    fun onFailure(previous: SourceHealth, error: SourceError?, now: Long = System.currentTimeMillis()): SourceHealth {
        // 没有结果的源（空结果）不算失败。
        if (error == null || error == SourceError.EmptyResult) {
            return previous
        }
        val nextTier = (previous.failureTier + 1).coerceAtMost(CooldownTiers.TIER_SUSPENDED)
        val newState = when (nextTier) {
            CooldownTiers.TIER_SUSPENDED -> HealthState.SUSPENDED
            else -> HealthState.COOLDOWN
        }
        val cooldownUntil = if (newState == HealthState.SUSPENDED) {
            Long.MAX_VALUE / 2
        } else {
            now + CooldownTiers.durationMs(nextTier)
        }
        return previous.copy(
            reachable = false,
            lastError = error,
            error = error.userMessage,
            state = newState,
            failureTier = nextTier,
            cooldownUntil = cooldownUntil
        )
    }

    /**
     * 冷却到期后的**半开**判定。
     *
     * 到期后允许一次探测：成功复位，失败继续升档。
     * 调用方用 [SourceHealth.isSkippable] 决定要不要发起这次探测。
     */
    fun onHalfOpen(
        previous: SourceHealth,
        succeeded: Boolean,
        now: Long = System.currentTimeMillis()
    ): SourceHealth = if (succeeded) {
        onSuccess(previous)
    } else {
        // 半开探测失败本身就是一次失败，沿用上次的错误归类。
        onFailure(previous, previous.lastError ?: com.libretv.tv.core.SourceError.Unreachable, now)
    }

    /** 用户手动恢复：强制清零并立即参与。 */
    fun onManualReset(previous: SourceHealth): SourceHealth = onSuccess(previous)
}

/**
 * 健康度台账：把「本次探测结果」与「持久化的冷却档位」合并。
 *
 * 探测本身只回答「这一次通不通」；tier 与冷却时间由这里累积并写盘，
 * 重启后不归零。
 */
class SourceHealthLedger(private val store: SourceStore) {

    /** 记录一次探测结果，返回合并了档位的状态并持久化。 */
    fun record(sourceUrl: String, probe: SourceHealth): SourceHealth {
        val previous = restore(sourceUrl)
        val merged = if (probe.reachable) {
            SourceHealthTracker.onSuccess(previous).copy(latencyMs = probe.latencyMs)
        } else {
            SourceHealthTracker.onFailure(previous, probe.lastError).copy(latencyMs = probe.latencyMs)
        }
        store.healthTiers = store.healthTiers + (sourceUrl to merged.toRecord())
        return merged
    }

    /** 读取持久化档位；冷却到期后处于半开状态（允许一次探测）。 */
    fun restore(sourceUrl: String, now: Long = System.currentTimeMillis()): SourceHealth {
        val record = store.healthTiers[sourceUrl] ?: return SourceHealth(reachable = true)
        val state = when {
            record.failureTier <= 0 -> HealthState.HEALTHY
            record.failureTier >= CooldownTiers.TIER_SUSPENDED -> HealthState.SUSPENDED
            record.cooldownUntil > now -> HealthState.COOLDOWN
            else -> HealthState.HEALTHY
        }
        return SourceHealth(
            reachable = state == HealthState.HEALTHY,
            state = state,
            failureTier = record.failureTier,
            cooldownUntil = record.cooldownUntil,
            lastError = errorByKind(record.lastErrorKind)
        )
    }

    /** 用户手动恢复。 */
    fun reset(sourceUrl: String) {
        store.healthTiers = store.healthTiers - sourceUrl
    }

    /** 当前不应参与扇出的源集合。 */
    fun skippableUrls(now: Long = System.currentTimeMillis()): Set<String> =
        store.healthTiers.filterValues { record ->
            when (record.failureTier) {
                0 -> false
                CooldownTiers.TIER_SUSPENDED -> true
                else -> record.cooldownUntil > now
            }
        }.keys
}

/** 持久化字段与运行时状态的双向转换。 */
private fun SourceHealth.toRecord() = HealthTierRecord(
    failureTier = failureTier,
    cooldownUntil = cooldownUntil,
    lastErrorKind = lastError?.javaClass?.simpleName.orEmpty()
)

private fun errorByKind(kind: String): com.libretv.tv.core.SourceError? = when (kind) {
    "Timeout" -> com.libretv.tv.core.SourceError.Timeout
    "Unreachable" -> com.libretv.tv.core.SourceError.Unreachable
    "Blocked" -> com.libretv.tv.core.SourceError.Blocked
    "Malformed" -> com.libretv.tv.core.SourceError.Malformed
    "UnsupportedDialect" -> com.libretv.tv.core.SourceError.UnsupportedDialect
    else -> null
}
