package com.libretv.tv.data

import com.libretv.tv.core.ApiResult
import com.libretv.tv.core.SourceError
import com.libretv.tv.domain.gateway.SubscriptionGateway
import com.libretv.tv.domain.model.SourceConfig
import com.libretv.tv.domain.model.SourceOrigin
import com.libretv.tv.domain.model.Subscription
import com.libretv.tv.domain.model.SubscriptionIntervals
import com.libretv.tv.domain.model.SubscriptionRules
import com.libretv.tv.domain.model.SubscriptionStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅同步的规则。
 *
 * 覆盖四组最容易出错的地方：后台不打扰、单轮上限、连续失败自动暂停、
 * 以及「按归属增删」不能碰到观众手工加的源。
 */
class SubscriptionSyncTest {

    /** 内存版的网关。 */
    private class FakeGateway(
        var subs: List<Subscription> = emptyList(),
        var sources: List<SourceConfig> = emptyList()
    ) : SubscriptionGateway {
        override fun subscriptions(): List<Subscription> = subs
        override fun saveSubscriptions(list: List<Subscription>) {
            subs = list
        }
        override fun customSources(): List<SourceConfig> = sources

        override fun replaceSubscriptionSources(
            subscriptionId: String,
            sources: List<SourceConfig>
        ): List<String> {
            val removed = this.sources.filter { it.subscriptionId == subscriptionId }.map { it.url }
            val kept = this.sources.filterNot { it.subscriptionId == subscriptionId }
            val keptUrls = kept.map { it.url }.toSet()
            val tagged = sources
                .filter { it.url !in keptUrls }
                .map { it.copy(subscriptionId = subscriptionId, origin = SourceOrigin.SUBSCRIPTION) }
            this.sources = kept + tagged
            return removed
        }

        override fun removeSubscriptionSources(subscriptionId: String): List<String> {
            val removed = this.sources.filter { it.subscriptionId == subscriptionId }
            this.sources = this.sources.filterNot { it.subscriptionId == subscriptionId }
            return removed.map { it.url }
        }
    }

    private fun sub(id: String, days: Int = 1, status: SubscriptionStatus = SubscriptionStatus.ACTIVE) =
        Subscription(
            id = id,
            name = "sub-" + id,
            url = "https://" + id + ".example/list.json",
            refreshDays = days,
            status = status
        )

    @Test
    fun backgroundNeverStartsARequest() = runTest {
        val gateway = FakeGateway(subs = listOf(sub("a")))
        var called = false
        val sync = SubscriptionSync(gateway, { called = true; ApiResult.Ok("[]") }, isForeground = { false })

        val report = sync.syncDue()

        assertTrue(report.skipped)
        assertFalse(called)
    }

    @Test
    fun syncReplacesOnlyTheSourcesOfThatSubscription() = runTest {
        val manual = SourceConfig("manual", "https://manual.example/api.php/provide/vod")
        val oldSubSource = SourceConfig(
            "old", "https://old.example/x", subscriptionId = "a", origin = SourceOrigin.SUBSCRIPTION
        )
        val gateway = FakeGateway(subs = listOf(sub("a")), sources = listOf(manual, oldSubSource))
        val sync = SubscriptionSync(
            gateway,
            { ApiResult.Ok("""[{"name":"new","url":"https://new.example/x"}]""") },
            isForeground = { true }
        )

        val report = sync.syncDue()

        assertEquals(1, report.synced)
        assertEquals(1, report.removed)
        assertTrue(gateway.sources.any { it.url == "https://manual.example/api.php/provide/vod" })
        assertTrue(gateway.sources.none { it.url == "https://old.example/x" })
        val imported = gateway.sources.first { it.url == "https://new.example/x" }
        assertEquals("a", imported.subscriptionId)
        assertEquals(SourceOrigin.SUBSCRIPTION, imported.origin)
    }

    @Test
    fun threeConsecutiveFailuresPauseTheSubscription() = runTest {
        val gateway = FakeGateway(subs = listOf(sub("a")))
        val sync = SubscriptionSync(gateway, { ApiResult.Err(SourceError.Unreachable, "连不上") }, isForeground = { true })

        sync.syncNow("a")
        sync.syncNow("a")
        assertEquals(SubscriptionStatus.ACTIVE, gateway.subscriptions().first().status)
        assertEquals(2, gateway.subscriptions().first().failureCount)

        sync.syncNow("a")

        val paused = gateway.subscriptions().first()
        assertEquals(SubscriptionStatus.PAUSED, paused.status)
        assertEquals(SubscriptionRules.MAX_FAILURES, paused.failureCount)
    }

    @Test
    fun manualSyncSuccessClearsTheFailureCountAndResumes() = runTest {
        val gateway = FakeGateway(
            subs = listOf(sub("a", status = SubscriptionStatus.PAUSED).copy(failureCount = 3))
        )
        var ok = false
        val sync = SubscriptionSync(
            gateway,
            {
                if (ok) ApiResult.Ok("""[{"name":"A","url":"https://a.example/x"}]""")
                else ApiResult.Err(SourceError.Unreachable, "失败")
            },
            isForeground = { true }
        )

        ok = true
        val outcome = sync.syncNow("a")

        assertTrue(outcome.success)
        val resumed = gateway.subscriptions().first()
        assertEquals(0, resumed.failureCount)
        assertEquals(SubscriptionStatus.ACTIVE, resumed.status)
        assertTrue(resumed.lastSyncAt > 0L)
    }

    @Test
    fun oneRunHandlesAtMostFiveSubscriptions() = runTest {
        val subs = (1..8).map { sub("s" + it) }
        val gateway = FakeGateway(subs = subs)
        val sync = SubscriptionSync(
            gateway,
            { ApiResult.Ok("""[{"name":"A","url":"https://a.example/x"}]""") },
            isForeground = { true }
        )

        val report = sync.syncDue()

        assertEquals(SubscriptionRules.MAX_SUBSCRIPTIONS_PER_RUN, report.synced)
    }

    @Test
    fun syncConcurrencyNeverExceedsTwo() = runTest {
        val subs = (1..5).map { sub("s" + it) }
        val gateway = FakeGateway(subs = subs)
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val sync = SubscriptionSync(
            gateway,
            {
                val current = running.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, current) }
                kotlinx.coroutines.delay(20)
                running.decrementAndGet()
                ApiResult.Ok("""[{"name":"A","url":"https://a.example/x"}]""")
            },
            isForeground = { true }
        )

        sync.syncDue()

        assertTrue(peak.get() <= SubscriptionRules.SYNC_CONCURRENCY)
    }

    @Test
    fun subscriptionWithRefreshOffIsNeverDue() {
        val subscription = sub("a", days = SubscriptionIntervals.OFF)
        assertFalse(subscription.isDue(System.currentTimeMillis()))
    }

    @Test
    fun pausedSubscriptionIsNeverDue() {
        val subscription = sub("a", days = 1, status = SubscriptionStatus.PAUSED)
        assertFalse(subscription.isDue(System.currentTimeMillis()))
    }

    @Test
    fun subscriptionIsDueAfterTheRefreshPeriod() {
        val now = 1_000_000_000L
        val fresh = sub("a", days = 7).copy(lastSyncAt = now - SubscriptionRules.DAY_MS)
        val stale = sub("b", days = 7).copy(lastSyncAt = now - 8 * SubscriptionRules.DAY_MS)

        assertFalse(fresh.isDue(now))
        assertTrue(stale.isDue(now))
    }
}