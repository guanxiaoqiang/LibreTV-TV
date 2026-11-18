package com.libretv.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Tests for the probe scheduler: concurrency cap, priority, in-flight dedup, caching. */
class ProbeSchedulerTest {

    @Test
    fun `the same key is probed once and the result is reused`() = runBlocking {
        val scheduler = ProbeScheduler()
        val calls = AtomicInteger()

        val first = scheduler.submit("a|1") { calls.incrementAndGet(); "value" }
        val second = scheduler.submit("a|1") { calls.incrementAndGet(); "other" }

        assertEquals("value", first)
        assertEquals("value", second)
        assertEquals(1, calls.get())
    }

    @Test
    fun `the cache can be invalidated`() = runBlocking {
        val scheduler = ProbeScheduler()
        val calls = AtomicInteger()

        scheduler.submit("a|1") { calls.incrementAndGet(); "v1" }
        scheduler.invalidate("a|1")
        scheduler.submit("a|1") { calls.incrementAndGet(); "v2" }

        assertEquals(2, calls.get())
    }

    @Test
    fun `a failing probe yields null instead of throwing`() = runBlocking {
        val scheduler = ProbeScheduler()

        val result = scheduler.submit<String>("bad") { throw IllegalStateException("boom") }

        assertNull(result)
    }

    @Test
    fun `concurrent probes for the same key share one in-flight request`() = runBlocking {
        val scheduler = ProbeScheduler(maxConcurrent = 4)
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()

        coroutineScope {
            val jobs = (1..5).map {
                async {
                    scheduler.submit("shared") {
                        calls.incrementAndGet()
                        gate.await()
                        "done"
                    }
                }
            }
            delay(50)
            gate.complete(Unit)
            val results = jobs.map { it.await() }
            assertEquals(List(5) { "done" }, results)
        }

        assertEquals(1, calls.get())
    }

    @Test
    fun `the concurrency cap is respected`() = runBlocking {
        val scheduler = ProbeScheduler(maxConcurrent = 2)
        var active = 0
        var peak = 0

        coroutineScope {
            val jobs = (1..6).map { index ->
                async {
                    scheduler.submit("key$index") {
                        active++
                        peak = maxOf(peak, active)
                        delay(30)
                        active--
                        index
                    }
                }
            }
            jobs.awaitAll()
        }

        assertTrue("峰值并发应不超过 2，实际 $peak", peak <= 2)
    }

    @Test
    fun `a cancelled waiter does not leak the slot`() = runBlocking {
        val scheduler = ProbeScheduler(maxConcurrent = 1)

        // 占满唯一的槽位。
        val blocker = async {
            scheduler.submit("blocker") {
                delay(500)
                "x"
            }
        }
        delay(50)

        // 第二个请求必须排队；取消它之后槽位应被正确释放。
        val queued = async {
            scheduler.submit("queued") {
                delay(50)
                "y"
            }
        }
        delay(50)
        queued.cancel()

        assertEquals("x", blocker.await())

        // 槽位没有被泄漏：新的请求仍能拿到它。
        val after = scheduler.submit("after") { "z" }
        assertEquals("z", after)
    }

    @Test
    fun `many cancellations never exhaust the slots`() = runBlocking {
        val scheduler = ProbeScheduler(maxConcurrent = 1)

        repeat(20) { round ->
            val blocker = async {
                scheduler.submit("blocker$round") {
                    delay(60)
                    "x"
                }
            }
            delay(10)
            val queued = (1..3).map {
                async {
                    scheduler.submit("queued$round-$it") {
                        delay(10)
                        "y"
                    }
                }
            }
            delay(10)
            queued.forEach { it.cancel() }
            assertEquals("x", blocker.await())
        }

        // 20 轮取消之后，槽位依然可用。
        assertEquals("z", scheduler.submit("after") { "z" })
    }

    @Test
    fun `a higher priority probe overtakes the queue`() = runBlocking {
        val scheduler = ProbeScheduler(maxConcurrent = 1)
        val order = mutableListOf<String>()

        val blocker = async {
            scheduler.submit("blocker") {
                delay(200)
                "x"
            }
        }
        delay(30)

        val low = async {
            scheduler.submit("low", priority = 0) {
                order += "low"
                "low"
            }
        }
        delay(30)
        val high = async {
            scheduler.submit("high", priority = 100) {
                order += "high"
                "high"
            }
        }

        blocker.await()
        low.await()
        high.await()

        assertEquals("高优先级应先执行", listOf("high", "low"), order)
    }
}
