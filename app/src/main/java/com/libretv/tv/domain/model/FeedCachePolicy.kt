package com.libretv.tv.domain.model

import java.security.MessageDigest

/**
 * 首页货架缓存的键与失效判定（design-detail.md §D.2 / §D.4）。
 *
 * 三个条件全部满足才算有效：
 *
 * ```
 * valid = savedAt + ttlMinutes*60_000 > now
 *      && version == CACHE_VERSION
 *      && sourceSignature == currentSignature
 * ```
 *
 * 放在 `domain` 是为了让判定可以被纯 JVM 单测覆盖——SQLite 不好在单测里起，
 * 但「过期 / 版本不符 / 签名不符」这三条规则本身没有任何 Android 依赖。
 */
object FeedCachePolicy {

    /**
     * 缓存条目自身的版本号。
     *
     * 与数据库 schema 版本是两回事：schema 升级用 `ALTER TABLE` 保住旧行，
     * 条目版本用来让旧条目（`version = 0`）在读取时被自然判定为过期。
     */
    const val CACHE_VERSION = 2

    /** 货架条目的存活时间（feed 6 小时，与 §D.1 的 L2 分级一致）。 */
    const val FEED_TTL_MINUTES = 6 * 60L

    /**
     * 缓存键：`v2|TAB|genre|<sourceSignature>`。
     *
     * 签名进键而不是进条件：源集一变，键就变，旧条目连被读到的机会都没有。
     */
    fun cacheKey(tab: String, genre: String, sourceSignature: String): String =
        "v2|$tab|$genre|$sourceSignature"

    /**
     * 已启用源 URL 集合的哈希。
     *
     * 排序后再哈希：集合语义相同（不管用户以什么顺序开关源）就必须得到
     * 同一个签名，否则一次无害的重排会把整页缓存作废。空集合给固定值，
     * 避免把空串哈希当成了「没启用任何源」的意外碰撞。
     */
    fun sourceSignature(sourceUrls: Collection<String>): String {
        if (sourceUrls.isEmpty()) return "empty"
        val canonical = sourceUrls.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        if (canonical.isEmpty()) return "empty"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.joinToString("\n").toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** 三条件失效判定；任何一条不满足都视为无效。 */
    fun isValid(
        version: Int,
        ttlMinutes: Long,
        savedSignature: String,
        currentSignature: String,
        savedAt: Long,
        now: Long
    ): Boolean {
        if (version != CACHE_VERSION) return false
        if (savedSignature != currentSignature) return false
        if (ttlMinutes <= 0L) return false
        return savedAt + ttlMinutes * 60_000L > now
    }
}
