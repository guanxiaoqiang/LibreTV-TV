package com.libretv.tv.core

import com.libretv.tv.domain.model.CacheFreshness
import com.libretv.tv.domain.model.CacheInvalidation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存语义层单测（local-first-storage 3.1–3.5）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheSemanticsTest {

    // ---------- 3.1 三态 ----------
    @Test
    fun `three states from freshness`() {
        assertTrue(CacheFreshness.judge(100, 200, 99) is CacheFreshness.Verdict.Fresh)
        assertTrue(CacheFreshness.judge(100, 200, 100) is CacheFreshness.Verdict.Stale)
        assertTrue(CacheFreshness.judge(100, 200, 199) is CacheFreshness.Verdict.Stale)
        assertTrue(CacheFreshness.judge(100, 200, 200) is CacheFreshness.Verdict.Expired)
    }

    // ---------- 3.2 有效性 ----------
    @Test
    fun `invalid when expiry version or signature differs`() {
        assertTrue(CacheFreshness.isValid(0, 1000, "s", "s", 2, 2, 500))
        assertFalse(CacheFreshness.isValid(0, 1000, "s", "s", 2, 2, 1500)) // 过期
        assertFalse(CacheFreshness.isValid(0, 1000, "s", "s", 1, 2, 500)) // 版本
        assertFalse(CacheFreshness.isValid(0, 1000, "old", "new", 2, 2, 500)) // 签名
    }

    // ---------- 3.3 三类失效 ----------
    @Test
    fun `signature invalidation only touches source dependent caches`() {
        val feed = CacheInvalidation.EntryMeta("feed", "k", "old", 2)
        val probe = CacheInvalidation.EntryMeta("probe", "k", "old", 2)
        val event = CacheInvalidation.Event.SourceSetChanged("new")
        assertTrue(CacheInvalidation.isInvalidated(feed, event))
        assertFalse(CacheInvalidation.isInvalidated(probe, event))
    }

    @Test
    fun `source removal only touches that source scoped caches`() {
        val detailA = CacheInvalidation.EntryMeta("detail", "k", "s", 2, "http://a")
        val detailB = CacheInvalidation.EntryMeta("detail", "k", "s", 2, "http://b")
        val event = CacheInvalidation.Event.SourceRemoved("http://a")
        assertTrue(CacheInvalidation.isInvalidated(detailA, event))
        assertFalse(CacheInvalidation.isInvalidated(detailB, event))
    }

    @Test
    fun `version invalidation touches all old versions`() {
        val old = CacheInvalidation.EntryMeta("feed", "k", "s", 1)
        val current = CacheInvalidation.EntryMeta("feed", "k", "s", 2)
        val event = CacheInvalidation.Event.ModelUpgraded(2)
        assertTrue(CacheInvalidation.isInvalidated(old, event))
        assertFalse(CacheInvalidation.isInvalidated(current, event))
    }

    @Test
    fun `category prefix invalidation`() {
        val entry = CacheInvalidation.EntryMeta("feed", "v2|电影|动作|sig", "s", 2)
        assertTrue(CacheInvalidation.isInvalidated(entry, CacheInvalidation.Event.CategoryRefreshed("v2|电影")))
        assertFalse(CacheInvalidation.isInvalidated(entry, CacheInvalidation.Event.CategoryRefreshed("v2|剧集")))
    }

    // ---------- 3.4 去重与失败不降级 ----------
    @Test
    fun `same kind refresh is deduplicated`() = runTest {
        val refresher = BackgroundRefresher(scope = this)
        var count = 0
        val block: suspend () -> Unit = { count++ }
        assertTrue(refresher.schedule("feed", block))
        assertFalse(refresher.schedule("feed", block))
        assertTrue(refresher.schedule("detail", block))
        refresher.cancelAll()
    }

    @Test
    fun `refresh failure is reported without throwing`() = runTest {
        var reported: Throwable? = null
        val refresher = BackgroundRefresher(scope = this)
        refresher.schedule(
            "feed",
            onRefresh = { error("boom") },
            onFailure = { reported = it }
        )
        delay(100)
        assertTrue(reported?.message?.contains("boom") == true)
    }

    // ---------- 3.5 配额隔离 ----------
    @Test
    fun `quota rejects over-limit and user-active requests`() {
        val quota = HarvestQuota(maxConcurrent = 2, perSourcePerHour = 2)
        assertTrue(quota.canAcquire("http://a", 0))
        quota.acquire("http://a", 0)
        assertTrue(quota.canAcquire("http://a", 0))
        quota.acquire("http://a", 0)
        assertFalse(quota.canAcquire("http://a", 0)) // 每源上限

        quota.release(); quota.release()
        quota.userActive = true
        assertFalse(quota.canAcquire("http://b", 0)) // 用户请求期间让路
        quota.userActive = false
        assertTrue(quota.canAcquire("http://b", 0))
    }

    @Test
    fun `concurrency cap is enforced`() {
        val quota = HarvestQuota(maxConcurrent = 1, perSourcePerHour = 10)
        quota.acquire("http://a", 0)
        assertFalse(quota.canAcquire("http://b", 0))
        quota.release()
        assertTrue(quota.canAcquire("http://b", 0))
    }

    @Test
    fun `harvest switch off rejects every background request`() {
        val quota = HarvestQuota()
        assertTrue(quota.canAcquire("http://a", 0))
        quota.harvestEnabled = false
        assertFalse(quota.canAcquire("http://a", 0))
        quota.harvestEnabled = true
        assertTrue(quota.canAcquire("http://a", 0))
    }
}
