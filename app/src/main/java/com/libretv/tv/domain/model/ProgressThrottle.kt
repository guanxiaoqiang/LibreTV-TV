package com.libretv.tv.domain.model

/**
 * 观看进度写入节流（local-first-storage 4.2 / watch-history 规格）。
 *
 * 规则：
 *  - 起播不满 [MIN_PLAY_MS] 不写（避免历史被"刚打开"塞满）；
 *  - 同一条目、位置变化不足 [POSITION_DELTA_MS] 不写（抖动不落盘）；
 *  - 切换条目（key 变化）或 [force]（退出补写）必须写。
 *
 * 纯函数状态机，便于 JVM 单测验证「高频小幅变化只产生一次写入」。
 */
class ProgressThrottle(
    private val minPlayMs: Long = 10_000L,
    private val positionDeltaMs: Long = 5_000L
) {
    private var lastKey: String? = null
    private var lastPositionMs: Long = 0L

    /** 本次上报是否应当落库。 */
    fun shouldWrite(key: String, positionMs: Long, force: Boolean = false): Boolean {
        if (force) {
            remember(key, positionMs)
            return positionMs > 0L
        }
        if (positionMs <= minPlayMs) return false
        val changedKey = key != lastKey
        val movedEnough = kotlin.math.abs(positionMs - lastPositionMs) >= positionDeltaMs
        if (!changedKey && !movedEnough) return false
        remember(key, positionMs)
        return true
    }

    private fun remember(key: String, positionMs: Long) {
        lastKey = key
        lastPositionMs = positionMs
    }

    /** 切集/换源时重置，让新条目第一次上报即可落库。 */
    fun reset() {
        lastKey = null
        lastPositionMs = 0L
    }
}
