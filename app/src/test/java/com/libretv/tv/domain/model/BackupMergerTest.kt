package com.libretv.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份合并规则。
 *
 * 这里保护的是观众的数据：重复导入不能让源翻倍，也不能把本地已有的历史
 * 与收藏悄悄换掉。
 */
class BackupMergerTest {

    private fun source(url: String, name: String = "源") = SourceConfig(
        name = name,
        url = url,
        detail = "",
        dialect = "cms"
    )

    private fun history(key: String, position: Long) = WatchRecord(
        key = key,
        title = key,
        episodeIndex = 1,
        positionMs = position,
        durationMs = 1000L,
        updatedAt = 0L
    )

    private fun favorite(titleKey: String, title: String = titleKey) = FavoriteEntry(
        titleKey = titleKey,
        title = title
    )

    @Test
    fun `source with the same url is skipped and new ones are appended`() {
        val current = BackupBundle(sources = listOf(source("http://a.com/api.php/provide/vod")))
        val incoming = BackupBundle(
            sources = listOf(
                source("http://a.com/api.php/provide/vod"),
                source("http://b.com/api.php/provide/vod")
            )
        )

        val plan = BackupMerger.merge(current, incoming)

        assertEquals(2, plan.sources.size)
        assertEquals(1, plan.addedSourceCount)
        assertEquals(1, plan.duplicateSourceCount)
    }

    @Test
    fun `subscriptions are deduplicated by url`() {
        val current = BackupBundle(subscriptions = listOf(Subscription(id = "1", name = "A", url = "http://a")))
        val incoming = BackupBundle(
            subscriptions = listOf(
                Subscription(id = "2", name = "A", url = "http://a"),
                Subscription(id = "3", name = "B", url = "http://b")
            )
        )

        val plan = BackupMerger.merge(current, incoming)

        assertEquals(2, plan.subscriptions.size)
        assertEquals(1, plan.addedSubscriptionCount)
    }

    @Test
    fun `history is not overwritten by default`() {
        val current = BackupBundle(history = listOf(history("k1", 100L)))
        val incoming = BackupBundle(history = listOf(history("k1", 900L), history("k2", 200L)))

        val plan = BackupMerger.merge(current, incoming)

        assertEquals(2, plan.history.size)
        assertEquals(100L, plan.history.first { it.key == "k1" }.positionMs)
        assertEquals(1, plan.addedHistoryCount)
    }

    @Test
    fun `history is replaced when overwrite is requested`() {
        val current = BackupBundle(history = listOf(history("k1", 100L)))
        val incoming = BackupBundle(history = listOf(history("k1", 900L)))

        val plan = BackupMerger.merge(
            current,
            incoming,
            BackupMergeOptions(overwriteHistory = true)
        )

        assertEquals(1, plan.history.size)
        assertEquals(900L, plan.history.first().positionMs)
    }

    @Test
    fun `favorites are merged by normalized title key`() {
        val current = BackupBundle(favorites = listOf(favorite("the show")))
        val incoming = BackupBundle(
            favorites = listOf(favorite("the show"), favorite("other show"))
        )

        val plan = BackupMerger.merge(current, incoming)

        assertEquals(2, plan.favorites.size)
        assertEquals(1, plan.addedFavoriteCount)
    }

    @Test
    fun `favorites are replaced when overwrite is requested`() {
        val current = BackupBundle(favorites = listOf(favorite("k", "本地")))
        val incoming = BackupBundle(favorites = listOf(favorite("k", "备份")))

        val plan = BackupMerger.merge(
            current,
            incoming,
            BackupMergeOptions(overwriteFavorites = true)
        )

        assertEquals(1, plan.favorites.size)
        assertEquals("备份", plan.favorites.first().title)
    }

    @Test
    fun `settings are kept unless import is requested`() {
        val current = BackupBundle(settings = BackupSettings(adFilterEnabled = true))
        val incoming = BackupBundle(settings = BackupSettings(adFilterEnabled = false))

        val kept = BackupMerger.merge(current, incoming)
        assertTrue(kept.settings.adFilterEnabled)

        val imported = BackupMerger.merge(
            current,
            incoming,
            BackupMergeOptions(importSettings = true)
        )
        assertEquals(false, imported.settings.adFilterEnabled)
    }
}
