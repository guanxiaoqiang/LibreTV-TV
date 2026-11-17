package com.libretv.tv.ui

import com.libretv.tv.data.QualityTier
import com.libretv.tv.data.SourceOffer
import com.libretv.tv.data.TitleGroup
import com.libretv.tv.data.VodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local cache has to survive a restart: a card is written to the database
 * and read back later. These tests pin down that a round trip keeps what the
 * grid needs to draw the card and to open the title again.
 */
class FeedCacheMappingTest {

    private fun sampleGroup() = TitleGroup(
        key = "tt0119644",
        title = "流浪地球",
        poster = "http://img/poster.jpg",
        year = "2019",
        area = "中国大陆",
        typeName = "科幻",
        offers = listOf(
            SourceOffer(
                sourceName = "片库",
                sourceUrl = "http://api.example",
                vodId = "4021",
                item = VodItem(vodId = "4021", vodName = "流浪地球"),
                quality = QualityTier.FULL_HD,
                remark = "1080P",
                latencyMs = 320L,
                isTrailer = false
            )
        )
    )

    @Test
    fun `a card survives the cache round trip`() {
        val card = sampleGroup().asFeedCard()
        val restored = card.toCached().toFeedCard()

        assertEquals(card.key, restored.key)
        assertEquals(card.title, restored.title)
        assertEquals(card.poster, restored.poster)
        assertEquals(card.badges.size, restored.badges.size)
        assertEquals(card.badges.first().text, restored.badges.first().text)
        assertEquals(card.badges.first().kind, restored.badges.first().kind)
    }

    @Test
    fun `a cached card can still open its title`() {
        val card = sampleGroup().asFeedCard()
        val restored = card.toCached().toFeedCard()
        val offer = restored.group?.offers?.firstOrNull()

        assertNotNull(restored.group)
        assertNotNull(offer)
        assertEquals("http://api.example", offer?.sourceUrl)
        assertEquals("4021", offer?.vodId)
        assertEquals(QualityTier.FULL_HD, offer?.quality)
        assertEquals("流浪地球", offer?.item?.vodName)
    }

    @Test
    fun `the cached strip keeps its pagination cursors`() {
        val snapshot = FeedSnapshot(
            cards = listOf(sampleGroup().asFeedCard()),
            rawResults = emptyList(),
            seenResultKeys = emptySet(),
            seenDoubanIds = emptySet(),
            nextSourcePage = 2,
            nextDoubanStart = 40,
            hasMore = true,
            totalItems = 7
        )

        val cached = snapshot.toCachedFeed("MOVIE|热门", 1_700_000_000_000L)
        val restored = cached.toSnapshot()

        assertEquals("MOVIE|热门", restored.let { cached.key })
        assertEquals(2, restored.nextSourcePage)
        assertEquals(40, restored.nextDoubanStart)
        assertTrue(restored.hasMore)
        assertEquals(7, restored.totalItems)
        assertEquals(1, restored.cards.size)
        assertEquals("流浪地球", restored.cards.first().title)
    }
}
