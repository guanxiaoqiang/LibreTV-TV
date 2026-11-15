package com.libretv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules of the source list shown on the source settings page.
 *
 * The merge combines three inputs: the read-only bundled list, the sources
 * the viewer added by hand and the sources the viewer deleted or switched
 * off.
 */
class SourceMergerTest {

    private val bundled = listOf(
        SourceConfig("A", "https://a.example/api.php/provide/vod"),
        SourceConfig("B", "https://b.example/api.php/provide/vod"),
        SourceConfig("C", "https://c.example/api.php/provide/vod")
    )

    @Test
    fun `bundled sources stay enabled by default`() {
        val entries = SourceMerger.merge(bundled, emptyList(), emptySet(), emptySet())
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.enabled })
        assertTrue(entries.none { it.custom })
    }

    @Test
    fun `deleted bundled sources disappear from the list`() {
        val entries = SourceMerger.merge(
            bundled = bundled,
            custom = emptyList(),
            removedUrls = setOf("https://b.example/api.php/provide/vod"),
            disabledUrls = emptySet()
        )
        assertEquals(2, entries.size)
        assertTrue(entries.none { it.name == "B" })
    }

    @Test
    fun `disabled sources stay visible but are switched off`() {
        val entries = SourceMerger.merge(
            bundled = bundled,
            custom = emptyList(),
            removedUrls = emptySet(),
            disabledUrls = setOf("https://a.example/api.php/provide/vod")
        )
        assertEquals(3, entries.size)
        val a = entries.first { it.name == "A" }
        assertFalse(a.enabled)
        assertTrue(entries.first { it.name == "B" }.enabled)
    }

    @Test
    fun `custom sources are appended and marked as custom`() {
        val custom = listOf(SourceConfig("我的源", "https://my.example/api.php/provide/vod"))
        val entries = SourceMerger.merge(bundled, custom, emptySet(), emptySet())
        assertEquals(4, entries.size)
        val mine = entries.first { it.name == "我的源" }
        assertTrue(mine.custom)
        assertTrue(mine.enabled)
    }

    @Test
    fun `a custom source that repeats a bundled url is merged instead of duplicated`() {
        val custom = listOf(SourceConfig("重复", "https://a.example/api.php/provide/vod"))
        val entries = SourceMerger.merge(bundled, custom, emptySet(), emptySet())
        assertEquals(3, entries.size)
        assertEquals(1, entries.count { it.url == "https://a.example/api.php/provide/vod" })
    }

    @Test
    fun `blank urls are never listed`() {
        val entries = SourceMerger.merge(
            bundled = bundled + SourceConfig("空", ""),
            custom = emptyList(),
            removedUrls = emptySet(),
            disabledUrls = emptySet()
        )
        assertEquals(3, entries.size)
        assertTrue(entries.none { it.url.isBlank() })
    }
}
