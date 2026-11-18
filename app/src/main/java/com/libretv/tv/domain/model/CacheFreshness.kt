package com.libretv.tv.domain.model

/**
 * 缓存新鲜度的纯判定（local-first-storage 3.1 / 3.2）。
 *
 * 三态定义（D3）：
 *  - Fresh：未过软 TTL，可直接使用；
 *  - Stale：过了软 TTL 但未过硬过期，可直接展示且后台刷新；
 *  - Expired：过了硬过期，仍展示但提示可能陈旧；刷新失败也不降级。
 *
 * 纯函数、无 Android 依赖——UI 的「卡片不跳动」契约靠它给出明确语义，
 * 而不是靠界面自己猜「这份数据是不是旧的」。
 */
object CacheFreshness {

    sealed class Verdict {
        object Fresh : Verdict()
        object Stale : Verdict()
        data class Expired(val ageMs: Long) : Verdict()
    }

    /**
     * 依据软/硬过期时间判定三态。
     *
     * @param expiresAt 软 TTL 截止时间
     * @param hardExpiresAt 硬过期截止时间
     * @param now 当前时间
     */
    fun judge(expiresAt: Long, hardExpiresAt: Long, now: Long): Verdict = when {
        now < expiresAt -> Verdict.Fresh
        now < hardExpiresAt -> Verdict.Stale
        else -> Verdict.Expired(ageMs = (now - hardExpiresAt).coerceAtLeast(0L))
    }

    /**
     * 有效性判定（3.2）：保存时间 + 有效期、模型版本、来源集合签名三项
     * 任一不满足即不可用。与 [FeedCachePolicy.isValid] 同语义，但独立成
     * 通用实现，供非 feed 类缓存复用。
     */
    fun isValid(
        savedAt: Long,
        ttlMs: Long,
        savedSignature: String,
        currentSignature: String,
        version: Int,
        currentVersion: Int,
        now: Long
    ): Boolean =
        version == currentVersion &&
            savedSignature == currentSignature &&
            ttlMs > 0L &&
            savedAt + ttlMs > now
}
