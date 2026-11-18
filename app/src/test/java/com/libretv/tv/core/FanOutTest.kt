package com.libretv.tv.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Tests for the shared fan-out budget: concurrency cap, per-item timeout, partial results. */
class FanOutTest {

    @Test
    fun `successful items are returned in input order`() = runBlocking {
        val out = FanOut.map(listOf(1, 2, 3, 4)) { it * 2 }
        assertEquals(listOf(2, 4, 6, 8), out)
    }

    @Test
    fun `a failing item does not fail the whole batch`() = runBlocking {
        val out = FanOut.map(listOf("ok", "bad", "ok2")) { item ->
            if (item == "bad") throw IllegalStateException("boom") else item
        }
        assertEquals(listOf("ok", "ok2"), out)
    }

    @Test
    fun `a null result is treated as a missing item`() = runBlocking {
        val out = FanOut.map(listOf("a", "b")) { if (it == "a") null else it }
        assertEquals(listOf("b"), out)
    }

    @Test
    fun `an empty input produces an empty output`() = runBlocking {
        assertEquals(emptyList<String>(), FanOut.map(emptyList<String>()) { it })
    }

    @Test
    fun `the concurrency cap is respected`() = runBlocking {
        // 用原子计数器：block 跑在多个线程上，普通 var 会丢更新导致假阳性。
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val items = (1..20).toList()

        FanOut.map(items, perItemTimeoutMs = 5_000, totalBudgetMs = 20_000) { item ->
            val now = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(20)
            active.decrementAndGet()
            item
        }

        assertTrue(
            "峰值并发应不超过 ${FanOut.MAX_CONCURRENCY}，实际 ${peak.get()}",
            peak.get() <= FanOut.MAX_CONCURRENCY
        )
        assertTrue("应该真的并发过", peak.get() > 1)
    }

    @Test
    fun `a slow item times out and the rest survive`() = runBlocking {
        val out = FanOut.map(listOf("slow", "fast"), perItemTimeoutMs = 100, totalBudgetMs = 5_000) { item ->
            if (item == "slow") {
                delay(10_000)
                item
            } else {
                item
            }
        }
        assertEquals(listOf("fast"), out)
    }

    @Test
    fun `the total budget keeps the results gathered so far`() = runBlocking {
        val out = FanOut.map(
            items = listOf("fast", "slow1", "slow2"),
            perItemTimeoutMs = 5_000,
            totalBudgetMs = 300
        ) { item ->
            if (item == "fast") {
                delay(10)
            } else {
                delay(5_000)
            }
            item
        }
        assertEquals(listOf("fast"), out)
    }

    @Test
    fun `forEach delivers results incrementally`() = runBlocking {
        val delivered = mutableListOf<String>()

        FanOut.forEach(
            items = listOf("fast", "slow"),
            perItemTimeoutMs = 5_000,
            totalBudgetMs = 5_000,
            onEach = { delivered += it }
        ) { item ->
            if (item == "fast") {
                delay(10)
            } else {
                delay(2_000)
            }
            item
        }

        // 慢的那一项还没回来时，快的那一项已经交付了。
        assertEquals("fast", delivered.first())
        assertTrue("慢的一项最终也应交付", delivered.contains("slow"))
    }

    @Test
    fun `forEach skips failing items`() = runBlocking {
        val delivered = mutableListOf<String>()

        FanOut.forEach(
            items = listOf("a", "bad", "c"),
            onEach = { delivered += it }
        ) { item ->
            if (item == "bad") throw IllegalStateException("boom") else item
        }

        assertEquals(listOf("a", "c"), delivered.sorted())
    }
}
