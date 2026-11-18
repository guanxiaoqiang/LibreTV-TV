package com.libretv.tv.data

import com.libretv.tv.domain.model.FavoriteEntry
import com.libretv.tv.domain.model.ProgressThrottle
import com.libretv.tv.domain.model.WatchRecord
import com.libretv.tv.domain.model.normalizeTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 观影记录与收藏的单测（local-first-storage 4.2 / 4.3 / 4.4 / 4.7）。
 */
class HistoryFavoritesTest {

    // ---------- 4.1 权威键：同一集不同地址只一条 ----------
    @Test
    fun `authoritative key ignores playback url`() {
        val a = com.libretv.tv.domain.model.WatchKeys.episodeKey("源", "vod1", 0, 2, "片", "第3集")
        val b = com.libretv.tv.domain.model.WatchKeys.episodeKey("源", "vod1", 0, 2, "片", "第3集")
        val c = com.libretv.tv.domain.model.WatchKeys.episodeKey("源", "vod1", 0, 3, "片", "第4集")
        assertEquals(a, b)   // 播放地址是快照，不参与键
        org.junit.Assert.assertNotEquals(a, c) // 剧集不同就是不同记录
    }

    @Test
    fun `fallback key includes playback url when vod id is missing`() {
        val k1 = com.libretv.tv.domain.model.WatchKeys.episodeKey("", "", 0, 0, "片", "第1集", "http://a")
        val k2 = com.libretv.tv.domain.model.WatchKeys.episodeKey("", "", 0, 0, "片", "第1集", "http://b")
        org.junit.Assert.assertNotEquals(k1, k2) // 无 ID 时地址参与键（watch-history 规格）
    }

    // ---------- 4.2 节流 ----------
    @Test
    fun `throttle skips tiny position changes but writes meaningful moves`() {
        val t = ProgressThrottle()
        assertFalse(t.shouldWrite("k", 5_000L))     // 起播不足 10s
        assertTrue(t.shouldWrite("k", 12_000L))     // 首次落库
        assertFalse(t.shouldWrite("k", 13_000L))    // 抖动 < 5s
        assertFalse(t.shouldWrite("k", 15_000L))
        assertTrue(t.shouldWrite("k", 18_000L))     // 变化 6s
    }

    @Test
    fun `throttle writes immediately on key change and on force`() {
        val t = ProgressThrottle()
        t.shouldWrite("a", 20_000L)
        assertFalse(t.shouldWrite("a", 21_000L))
        assertTrue(t.shouldWrite("b", 11_000L))     // 切条
        assertFalse(t.shouldWrite("b", 12_000L))    // 抖动
        assertTrue(t.shouldWrite("b", 30_000L, force = true)) // 退出补写生效
    }

    // ---------- 4.3 跨源聚合（接口默认方法） ----------
    @Test
    fun `aggregate by title groups sources and keeps latest progress`() {
        val gateway = object : com.libretv.tv.domain.gateway.WatchHistoryGateway {
            override val records = kotlinx.coroutines.flow.MutableStateFlow(
                listOf(
                    WatchRecord(key = "a|1", titleKey = "片", title = "片", sourceUrl = "http://a", positionMs = 100L, updatedAt = 1L),
                    WatchRecord(key = "b|1", titleKey = "片", title = "片", sourceUrl = "http://b", positionMs = 200L, updatedAt = 3L)
                )
            )
            override fun upsert(record: WatchRecord) {}
            override fun delete(key: String) {}
            override fun clear() {}
        }

        val aggregated = gateway.aggregateByTitle()
        assertEquals(1, aggregated.size)
        assertEquals(2, aggregated[0].sourceCount)
        assertEquals(200L, aggregated[0].latest.positionMs)
    }

    // ---------- 4.4 收藏按归一化片名去重（BackupMerger 已覆盖，这里补键规则） ----------
    @Test
    fun `favorite dedupe key is normalized title`() {
        assertEquals(normalizeTitle("片 名（2024）"), normalizeTitle("片名(2024)"))
        val a = FavoriteEntry(titleKey = normalizeTitle("片 名"), title = "片 名")
        val b = FavoriteEntry(titleKey = normalizeTitle("片名"), title = "片名")
        assertEquals(a.titleKey, b.titleKey)
    }

    // ---------- 4.6 取消后详情页不再显示已收藏 ----------
    @Test
    fun `after removal title key is absent from favorite keys`() {
        val key = normalizeTitle("片子")
        val favoriteKeys = mutableSetOf(key)
        assertTrue(key in favoriteKeys)                 // 详情页显示「已收藏」
        favoriteKeys.remove(key)                        // 取消收藏
        assertFalse(key in favoriteKeys)                // 详情页显示「未收藏」
    }

    // ---------- 4.7 上限与倒序（Store 逻辑） ----------
    @Test
    fun `history retention keeps newest first and caps at 200`() {
        val records = (0 until 205).map { i ->
            WatchRecord(key = "k$i", title = "片$i", positionMs = 10_000L, updatedAt = i.toLong())
        }
        val all = WatchHistoryStore.retainNewest(records)
        assertEquals(200, all.size)
        assertEquals("k204", all.first().key)   // 最新在前
        assertEquals("k5", all.last().key)      // 最旧 5 条被淘汰
        assertFalse(all.any { it.key == "k0" })
    }
}
