package com.libretv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The home grid must never reorder or rebuild cards that are already on screen. */
class FeedOrderTest {

    private fun card(key: String, sources: Int = 1) = FeedCard(
        key = key,
        title = key,
        poster = "",
        badges = listOf(FeedBadge("$sources 源", FeedBadgeKind.ACCENT))
    )

    @Test
    fun `later arrivals append and never push the head around`() {
        val first = card("g|A")
        val second = card("g|B")
        val merged = mergeStableCards(
            previous = listOf(first, second),
            // A gained a source, but under a hot sort it would jump ahead of B.
            incoming = listOf(card("g|C"), card("g|A", 3), card("g|B")),
            refreshExisting = false
        )

        assertEquals(listOf("g|A", "g|B", "g|C"), merged.map { it.key })
        assertSame(first, merged[0])
        assertSame(second, merged[1])
    }

    @Test
    fun `metadata refresh stays in the same slot`() {
        val first = card("g|A")
        val untouched = card("g|B")
        val merged = mergeStableCards(
            previous = listOf(first, untouched),
            incoming = listOf(card("g|A", 4), card("g|B"), card("g|C")),
            refreshExisting = true
        )

        assertEquals(listOf("g|A", "g|B", "g|C"), merged.map { it.key })
        assertEquals("4 源", merged[0].badges.first().text)
        assertSame(untouched, merged[1])
    }

    @Test
    fun `discovery cards and duplicate keys are safe`() {
        val discovery = FeedCard(key = "d|123", title = "豆瓣片", poster = "")
        val same = card("g|A")
        val merged = mergeStableCards(
            previous = listOf(same, discovery, same),
            incoming = listOf(card("g|A"), card("g|B")),
            refreshExisting = true
        )

        assertEquals(listOf("g|A", "d|123", "g|B"), merged.map { it.key })
    }
}
