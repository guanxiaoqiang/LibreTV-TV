package com.libretv.tv.domain.usecase

import com.libretv.tv.data.SearchResult
import com.libretv.tv.data.VodItem
import com.libretv.tv.domain.model.FeedCard
import com.libretv.tv.domain.model.FeedSnapshot
import com.libretv.tv.domain.model.HomeGenre
import com.libretv.tv.domain.model.asFeedCard
import com.libretv.tv.domain.model.aggregate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 快照构建规则：稳定顺序、游标只进不退、关键词过滤。 */
class BuildFeedSnapshotTest {

    private fun result(source: String, name: String) = SearchResult(
        sourceName = source,
        item = VodItem(vodId = "$source-$name", vodName = name),
        sourceUrl = "https://$source.invalid",
        latencyMs = 0L
    )

    private fun card(key: String) = FeedCard(key = key, title = key, poster = "")

    @Test
    fun `previous cards keep their order and new titles append`() {
        val raw = listOf(
            result("a", "B片"),
            result("b", "A片"),
            result("c", "C片")
        )
        // 第一页已上屏的顺序（A 先到）。
        val firstPageCards = aggregate(raw.take(2)).map { it.asFeedCard() }
        val snapshot = BuildFeedSnapshot(
            genre = HomeGenre("热门"),
            raw = raw,
            seen = emptySet(),
            old = null,
            keywordFilter = false,
            page = 1,
            previousCards = firstPageCards
        )

        // 聚合桶按输入顺序：B片 先到 → previous 顺序原样保留，C片 追加。
        assertEquals(
            listOf("g|b片", "g|a片", "g|c片"),
            snapshot.cards.map { it.key }
        )
    }

    @Test
    fun `keyword filter drops titles that do not match the genre`() {
        val snapshot = BuildFeedSnapshot(
            genre = HomeGenre("科幻", keywords = listOf("科幻")),
            raw = listOf(
                result("a", "科幻片"),
                result("b", "爱情片")
            ),
            seen = emptySet(),
            old = null,
            keywordFilter = true,
            page = 1,
            previousCards = emptyList()
        )

        assertEquals(1, snapshot.cards.size)
        assertEquals("科幻片", snapshot.cards.single().title)
    }

    @Test
    fun `the paging cursor only moves forward across refreshes`() {
        val old = FeedSnapshot(
            cards = emptyList(),
            rawResults = emptyList(),
            seenResultKeys = emptySet(),
            seenDoubanIds = emptySet(),
            nextSourcePage = 3,
            nextDoubanStart = 20,
            hasMore = true,
            totalItems = 0
        )
        val snapshot = BuildFeedSnapshot(
            genre = HomeGenre("热门"),
            raw = listOf(result("a", "某片")),
            seen = emptySet(),
            old = old,
            keywordFilter = false,
            // 后台刷新从第 1 页重新拉：游标不能退回 2。
            page = 1,
            previousCards = emptyList()
        )

        assertEquals(3, snapshot.nextSourcePage)
        assertEquals(20, snapshot.nextDoubanStart)
        assertTrue(snapshot.hasMore)
    }

    @Test
    fun `no more pages once the source cap is reached`() {
        val snapshot = BuildFeedSnapshot(
            genre = HomeGenre("热门"),
            raw = listOf(result("a", "某片")),
            seen = emptySet(),
            old = null,
            keywordFilter = false,
            page = BuildFeedSnapshot.MAX_SOURCE_PAGE,
            previousCards = emptyList()
        )

        assertFalse(snapshot.hasMore)
    }
}
