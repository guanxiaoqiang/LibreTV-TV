package com.libretv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Recent keywords must be capped, deduplicated and ordered newest-first. */
class SearchHistoryTest {

    @Test
    fun `submitting a keyword puts it first`() {
        val merged = mergeSearchHistory(
            existing = listOf("霸王别姬", "让子弹飞"),
            keyword = "  流浪地球  "
        )
        assertEquals(listOf("流浪地球", "霸王别姬", "让子弹飞"), merged)
    }

    @Test
    fun `an older duplicate is removed case insensitively`() {
        assertEquals(
            listOf("avengers", "流浪地球"),
            mergeSearchHistory(listOf("流浪地球", "Avengers"), "avengers")
        )
        assertEquals(
            listOf("流浪地球", "霸王别姬"),
            mergeSearchHistory(listOf("流浪地球", "霸王别姬"), "流浪地球")
        )
    }

    @Test
    fun `blank input leaves the list untouched`() {
        val existing = listOf("流浪地球", "霸王别姬")
        val merged = mergeSearchHistory(existing, "   ")
        assertSame(existing, merged)
    }

    @Test
    fun `the list is capped at the configured limit`() {
        val existing = (1..5).map { "旧词$it" }
        val merged = mergeSearchHistory(existing, "新词", limit = 3)
        assertEquals(3, merged.size)
        assertEquals(listOf("新词", "旧词1", "旧词2"), merged)
    }
}
