package com.libretv.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存三条件失效判定与 v2 键格式的行为锚点（design-detail.md §D.2 / §D.4）。
 *
 * 判定放在 domain 就是为了能在纯 JVM 上把三种失效各钉一个用例：
 * TTL 过期、版本不符（旧 schema 行）、签名不符（源集变化）。
 */
class FeedCachePolicyTest {

    private val signature = FeedCachePolicy.sourceSignature(listOf("http://a", "http://b"))
    private val now = 1_700_000_000_000L
    private val freshSavedAt = now - 60_000L // 一分钟前写入

    private fun validRow(
        version: Int = FeedCachePolicy.CACHE_VERSION,
        ttlMinutes: Long = FeedCachePolicy.FEED_TTL_MINUTES,
        savedSignature: String = signature,
        currentSignature: String = signature,
        savedAt: Long = freshSavedAt
    ) = FeedCachePolicy.isValid(
        version, ttlMinutes, savedSignature, currentSignature, savedAt, now
    )

    // ---------- 三种失效 ----------

    @Test
    fun `a fresh row with matching version and signature is valid`() {
        assertTrue(validRow())
    }

    @Test
    fun `an expired row is invalid`() {
        // TTL 一分钟前到期。
        val expiredAt = now - FeedCachePolicy.FEED_TTL_MINUTES * 60_000L - 1_000L
        assertFalse(
            FeedCachePolicy.isValid(
                FeedCachePolicy.CACHE_VERSION, FeedCachePolicy.FEED_TTL_MINUTES,
                signature, signature, expiredAt, now
            )
        )
        // 恰好到期那一瞬也算过期： savedAt + ttl == now 不满足 >。
        val exactExpiry = now - FeedCachePolicy.FEED_TTL_MINUTES * 60_000L
        assertFalse(
            FeedCachePolicy.isValid(
                FeedCachePolicy.CACHE_VERSION, FeedCachePolicy.FEED_TTL_MINUTES,
                signature, signature, exactExpiry, now
            )
        )
    }

    @Test
    fun `an old schema row with version 0 is invalid`() {
        // v1 迁移保留下来的旧行：version 落到默认 0，读取时视为过期。
        assertFalse(validRow(version = 0))
        assertFalse(validRow(version = FeedCachePolicy.CACHE_VERSION + 1))
    }

    @Test
    fun `a row from a different source set is invalid`() {
        val otherSignature = FeedCachePolicy.sourceSignature(listOf("http://a", "http://c"))
        assertFalse(validRow(currentSignature = otherSignature))
        // 空签名（旧 schema 默认值）同样不匹配。
        assertFalse(validRow(currentSignature = ""))
    }

    // ---------- 签名计算 ----------

    @Test
    fun `signature ignores order and duplicates of the same set`() {
        val a = FeedCachePolicy.sourceSignature(listOf("http://a", "http://b"))
        val b = FeedCachePolicy.sourceSignature(listOf("http://b", "http://a"))
        assertEquals(a, b)
    }

    @Test
    fun `different source sets get different signatures`() {
        val a = FeedCachePolicy.sourceSignature(listOf("http://a", "http://b"))
        val b = FeedCachePolicy.sourceSignature(listOf("http://a", "http://b", "http://c"))
        val disabled = FeedCachePolicy.sourceSignature(listOf("http://a"))
        assertNotEquals(a, b)
        assertNotEquals(a, disabled)
        assertNotEquals(b, disabled)
    }

    @Test
    fun `empty source sets share one stable signature`() {
        assertEquals(
            FeedCachePolicy.sourceSignature(emptyList()),
            FeedCachePolicy.sourceSignature(setOf("  ", ""))
        )
        assertNotEquals(
            FeedCachePolicy.sourceSignature(emptyList()),
            FeedCachePolicy.sourceSignature(listOf("http://a"))
        )
    }

    @Test
    fun `signature is deterministic across calls`() {
        val urls = (1..20).map { "http://source-$it.example/api" }
        assertEquals(FeedCachePolicy.sourceSignature(urls), FeedCachePolicy.sourceSignature(urls.reversed()))
    }

    // ---------- 键格式 ----------

    @Test
    fun `cache key embeds version tab genre and signature`() {
        assertEquals(
            "v2|MOVIE|热门|$signature",
            FeedCachePolicy.cacheKey("MOVIE", "热门", signature)
        )
    }

    @Test
    fun `a changed source set produces a different cache key`() {
        val before = FeedCachePolicy.cacheKey(
            "MOVIE", "热门", FeedCachePolicy.sourceSignature(listOf("http://a", "http://b"))
        )
        val after = FeedCachePolicy.cacheKey(
            "MOVIE", "热门", FeedCachePolicy.sourceSignature(listOf("http://a"))
        )
        assertNotEquals(before, after)
    }
}
