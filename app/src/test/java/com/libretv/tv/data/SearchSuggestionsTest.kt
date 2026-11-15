package com.libretv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strip under the input field must react to both input paths. */
class SearchSuggestionsTest {

    private val index = mapOf(
        "lldq" to "流浪地球",
        "lldq2" to "流浪地球2",
        "bfbj" to "霸王别姬",
        "hxn" to "火星救援"
    )

    @Test
    fun `initials win over free text and match the pinyin index`() {
        assertEquals(
            listOf("流浪地球2", "流浪地球"),
            SearchSuggestions.suggest(
                titleIndex = index,
                history = listOf("霸王别姬"),
                initials = "ll",
                query = "完全不相关"
            )
        )
    }

    @Test
    fun `live text input matches bundled titles and recent keywords`() {
        val suggestions = SearchSuggestions.suggest(
            titleIndex = index,
            history = listOf("流浪地球 重映版"),
            initials = "",
            query = "流浪"
        )
        assertEquals(listOf("流浪地球", "流浪地球2", "流浪地球 重映版"), suggestions)
    }

    @Test
    fun `prefix hits are ranked ahead of mid-string hits`() {
        val titles = mapOf(
            "a" to "三体",
            "b" to "流浪地球",
            "c" to "地球最后的夜晚"
        )
        assertEquals(
            listOf("地球最后的夜晚", "流浪地球"),
            SearchSuggestions.suggest(titles, emptyList(), "", "地球")
        )
    }

    @Test
    fun `blank input yields no suggestions`() {
        assertTrue(SearchSuggestions.suggest(index, listOf("旧词"), "", "   ").isEmpty())
        assertTrue(SearchSuggestions.suggest(index, emptyList(), "", "").isEmpty())
    }

    @Test
    fun `the strip is capped and deduplicated`() {
        val many = (1..12).associate { "k$it" to "片名$it" }
        val suggestions = SearchSuggestions.suggest(many, emptyList(), "k", "", limit = 5)
        assertEquals(5, suggestions.size)
        assertEquals(suggestions.distinct(), suggestions)
    }
}
