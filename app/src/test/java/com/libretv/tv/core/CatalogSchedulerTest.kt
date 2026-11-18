package com.libretv.tv.core

import com.libretv.tv.domain.model.CatalogPriority
import com.libretv.tv.domain.model.CatalogTask
import com.libretv.tv.domain.model.HarvestHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内容目录调度单测（local-first-storage 5.2 / 5.3 / 5.4 / 5.7）。 */
class CatalogSchedulerTest {

    private fun task(p: CatalogPriority, key: String) =
        CatalogTask(priority = p, sourceUrl = "http://s", titleKey = key, enqueuedAt = 0L)

    @Test
    fun `tasks dequeue by priority P0 before P1 before P3`() {
        val q = CatalogScheduler()
        q.enqueue(task(CatalogPriority.P3_HEALTH_CHECK, "health"))
        q.enqueue(task(CatalogPriority.P1_HOME_SHELF, "home"))
        q.enqueue(task(CatalogPriority.P0_NEXT_EPISODE, "next"))
        q.enqueue(task(CatalogPriority.P2_SEARCHED_DETAIL, "detail"))

        assertEquals("next", q.poll()?.titleKey)
        assertEquals("home", q.poll()?.titleKey)
        assertEquals("detail", q.poll()?.titleKey)
        assertEquals("health", q.poll()?.titleKey)
        assertEquals(null, q.poll())
    }

    @Test
    fun `same priority is FIFO`() {
        val q = CatalogScheduler()
        q.enqueue(task(CatalogPriority.P1_HOME_SHELF, "first"))
        q.enqueue(task(CatalogPriority.P1_HOME_SHELF, "second"))
        assertEquals("first", q.poll()?.titleKey)
        assertEquals("second", q.poll()?.titleKey)
    }

    // ---------- 5.2 有效期跳过与分页上限 ----------
    @Test
    fun `fresh entry with no upstream new is skipped`() {
        assertTrue(HarvestPolicy.shouldSkip(updatedAt = 0L, now = 1_000L, ttlMs = 5_000L))
        assertFalse(HarvestPolicy.shouldSkip(0L, 1_000L, 5_000L, upstreamHasNew = true))
        assertFalse(HarvestPolicy.shouldSkip(0L, 10_000L, 5_000L)) // 过期
    }

    @Test
    fun `page count is capped`() {
        assertEquals(2, HarvestPolicy.pagesToFetch(requested = 5, maxPages = 2))
        assertEquals(1, HarvestPolicy.pagesToFetch(requested = 0, maxPages = 2))
        assertEquals(2, HarvestPolicy.pagesToFetch(requested = 2, maxPages = 2))
    }

    // ---------- 5.7 健康降级 ----------
    @Test
    fun `consecutive failures lower priority and eventually pause harvest`() {
        assertEquals(0, HarvestHealth.priorityPenalty(0))
        assertTrue(HarvestHealth.priorityPenalty(2) > HarvestHealth.priorityPenalty(1))
        assertFalse(HarvestHealth.shouldPauseHarvest(2))
        assertTrue(HarvestHealth.shouldPauseHarvest(3))
    }

    @Test
    fun `harvest interval grows with failures`() {
        val base = 1_000L
        assertEquals(base, HarvestHealth.harvestIntervalMs(base, 0))
        assertTrue(HarvestHealth.harvestIntervalMs(base, 3) > base)
    }
}
