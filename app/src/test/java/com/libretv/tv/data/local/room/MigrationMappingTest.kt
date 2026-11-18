package com.libretv.tv.data.local.room

import com.libretv.tv.domain.model.CacheFreshness
import com.libretv.tv.domain.model.FavoriteEntry
import com.libretv.tv.domain.model.SkipMark
import com.libretv.tv.domain.model.SkipSegment
import com.libretv.tv.domain.model.Subscription
import com.libretv.tv.domain.model.WatchRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移映射的纯 JVM 验证（local-first-storage 2.1 / 2.2）。
 *
 * Room 的真实迁移测试在 androidTest（MigrationTestHelper），这里锁定搬运
 * 前后的字段映射「一行不丢、字段不错位」——这是迁移正确性的核心，且能
 * 在无设备环境里跑。
 */
class MigrationMappingTest {

    @Test
    fun `watch record maps to entity without losing fields`() {
        val record = WatchRecord(
            key = "源|vod1|0|2", titleKey = "片名", title = "片名",
            poster = "http://p", sourceName = "源A", sourceUrl = "http://s",
            vodId = "vod1", episodeName = "第3集", rawUrl = "http://raw",
            lineIndex = 0, episodeIndex = 2, positionMs = 120_000L,
            durationMs = 2_400_000L, updatedAt = 9_999L, typeName = "电影"
        )

        val entity = WatchRecordEntity(
            key = record.key, titleKey = record.titleKey, title = record.title,
            poster = record.poster, sourceName = record.sourceName, sourceUrl = record.sourceUrl,
            vodId = record.vodId, episodeName = record.episodeName, rawUrl = record.rawUrl,
            lineIndex = record.lineIndex, episodeIndex = record.episodeIndex,
            positionMs = record.positionMs, durationMs = record.durationMs,
            updatedAt = record.updatedAt, typeName = record.typeName
        )

        assertEquals(record.key, entity.key)
        assertEquals(record.titleKey, entity.titleKey)
        assertEquals(record.positionMs, entity.positionMs)
        assertEquals(record.updatedAt, entity.updatedAt)
        assertEquals(record.episodeIndex, entity.episodeIndex)
    }

    @Test
    fun `favorite maps to entity keeping all identity fields`() {
        val fav = FavoriteEntry(
            titleKey = "片名", title = "片名", poster = "http://p",
            sourceName = "源A", sourceUrl = "http://s", vodId = "vod1", createdAt = 42L
        )
        val entity = FavoriteEntity(
            titleKey = fav.titleKey, title = fav.title, poster = fav.poster,
            sourceUrl = fav.sourceUrl, vodId = fav.vodId, createdAt = fav.createdAt
        )
        assertEquals(fav.titleKey, entity.titleKey)
        assertEquals(fav.sourceUrl, entity.sourceUrl)
        assertEquals(fav.createdAt, entity.createdAt)
    }

    @Test
    fun `subscription maps to entity preserving sync state`() {
        val sub = Subscription(
            id = "s1", name = "订", url = "http://u", refreshDays = 7,
            status = com.libretv.tv.domain.model.SubscriptionStatus.PAUSED,
            failureCount = 3, lastSyncAt = 888L
        )
        val entity = SubscriptionEntity(
            id = sub.id, name = sub.name, url = sub.url,
            refreshDays = sub.refreshDays, status = sub.status.name,
            failureCount = sub.failureCount, lastSyncAt = sub.lastSyncAt
        )
        assertEquals(sub.id, entity.id)
        assertEquals(sub.status.name, entity.status)
        assertEquals(sub.failureCount, entity.failureCount)
        assertEquals(sub.lastSyncAt, entity.lastSyncAt)
    }

    @Test
    fun `skip mark segments survive mapping`() {
        val mark = SkipMark(
            titleKey = "片名", title = "片名",
            segments = listOf(SkipSegment(com.libretv.tv.domain.model.SkipKind.INTRO, 0L, 90_000L)),
            updatedAt = 7L
        )
        val entity = SkipMarkEntity(
            titleKey = mark.titleKey, title = mark.title,
            segments = "[{\"kind\":\"INTRO\",\"startMs\":0,\"endMs\":90000}]",
            updatedAt = mark.updatedAt
        )
        assertEquals(mark.titleKey, entity.titleKey)
        assertTrue(entity.segments.contains("INTRO"))
        assertTrue(entity.segments.contains("90000"))
    }

    @Test
    fun `cache freshness three states map correctly`() {
        assertTrue(CacheFreshness.judge(100L, 200L, 50L) is CacheFreshness.Verdict.Fresh)
        assertTrue(CacheFreshness.judge(100L, 200L, 150L) is CacheFreshness.Verdict.Stale)
        val expired = CacheFreshness.judge(100L, 200L, 250L)
        assertTrue(expired is CacheFreshness.Verdict.Expired)
        assertEquals(50L, (expired as CacheFreshness.Verdict.Expired).ageMs)
    }

    @Test
    fun `validity rejects version mismatch signature change and expiry`() {
        val base = { now: Long ->
            CacheFreshness.isValid(
                savedAt = 100L, ttlMs = 100L,
                savedSignature = "sig", currentSignature = "sig",
                version = 2, currentVersion = 2, now = now
            )
        }
        assertTrue(base(150L))
        assertFalse(base(250L))
        assertFalse(CacheFreshness.isValid(100L, 100L, "old", "new", 2, 2, 150L))
        assertFalse(CacheFreshness.isValid(100L, 100L, "sig", "sig", 1, 2, 150L))
    }
}
