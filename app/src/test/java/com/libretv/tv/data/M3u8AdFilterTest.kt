package com.libretv.tv.data

import com.libretv.tv.core.M3u8AdFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playlist rewrites, modelled on LibreTV's own m3u8 tests.
 *
 * The recurring assertion is that real segments and the DISCONTINUITY boundary
 * between real groups survive: dropping those tags is what breaks PTS alignment
 * in ExoPlayer.
 */
class M3u8AdFilterTest {

    private val header = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10"

    private fun strip(text: String) = M3u8AdFilter.stripAdGroupsWithStats(text)

    private fun discCount(text: String) =
        text.lines().count { it.trim() == "#EXT-X-DISCONTINUITY" }

    @Test
    fun `master playlist without discontinuity is untouched`() {
        val master = "$header\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhttps://x.invalid/720.m3u8"
        val result = strip(master)
        assertEquals(master, result.playlist)
        assertEquals(0, result.removedSegments)
    }

    @Test
    fun `marked ad group is removed while key tag and group boundary survive`() {
        val text = listOf(
            header,
            "#EXTINF:10.0,", "movie0.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"",
            "#EXTINF:5.0,", "https://cdn.invalid/adjump/a1.ts",
            "#EXTINF:5.0,", "https://cdn.invalid/adjump/a2.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:10.0,", "movie1.ts",
            "#EXT-X-ENDLIST"
        ).joinToString("\n")

        val result = strip(text)
        assertFalse(result.playlist.contains("adjump"))
        assertTrue(result.playlist.contains("#EXT-X-KEY"))
        assertTrue(result.playlist.contains("movie0.ts"))
        assertTrue(result.playlist.contains("movie1.ts"))
        assertEquals(1, discCount(result.playlist))
        assertEquals(2, result.removedSegments)
    }

    @Test
    fun `a group mixing ad and real segments is spared`() {
        val text = listOf(
            header,
            "#EXTINF:10.0,", "movie0.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:5.0,", "https://cdn.invalid/advert/a1.ts",
            "#EXTINF:5.0,", "movie-mixed.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:10.0,", "movie1.ts",
            "#EXT-X-ENDLIST"
        ).joinToString("\n")
        val result = strip(text)
        assertTrue(result.playlist.contains("advert"))
        assertTrue(result.playlist.contains("movie-mixed.ts"))
        assertEquals(0, result.removedSegments)
    }

    @Test
    fun `short interstitial between two long groups is removed`() {
        val text = listOf(
            header,
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:65.0,", "a1.ts", "#EXTINF:65.0,", "a2.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:10.0,", "short-ad.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:65.0,", "b1.ts", "#EXTINF:65.0,", "b2.ts",
            "#EXT-X-ENDLIST"
        ).joinToString("\n")
        val result = strip(text)
        assertFalse(result.playlist.contains("short-ad.ts"))
        assertTrue(result.playlist.contains("a1.ts"))
        assertTrue(result.playlist.contains("b2.ts"))
        assertEquals(1, discCount(result.playlist))
    }

    @Test
    fun `all-short groups do not create a false interstitial`() {
        val text = listOf(
            header,
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:10.0,", "a1.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:10.0,", "a2.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:10.0,", "a3.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:10.0,", "a4.ts",
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:10.0,", "a5.ts",
            "#EXT-X-ENDLIST"
        ).joinToString("\n")
        val result = strip(text)
        // Five homogeneous groups must not be read as a pre-roll ad either.
        assertTrue(result.playlist.contains("a1.ts"))
        assertTrue(result.playlist.contains("a5.ts"))
        assertEquals(0, result.removedSegments)
    }

    @Test
    fun `pre-roll insertion within the 90 second threshold is removed`() {
        val lines = mutableListOf(header, "#EXT-X-DISCONTINUITY")
        repeat(10) { i -> lines += "#EXTINF:6.0,"; lines += "ad$i.ts" }
        lines += "#EXT-X-DISCONTINUITY"
        lines += "#EXTINF:10.0,"; lines += "movie1.ts"
        lines += "#EXT-X-DISCONTINUITY"
        lines += "#EXTINF:10.0,"; lines += "movie2.ts"
        lines += "#EXT-X-ENDLIST"

        val result = strip(lines.joinToString("\n"))
        assertFalse(result.playlist.contains("ad0.ts"))
        assertTrue(result.playlist.contains("movie1.ts"))
        assertTrue(result.playlist.contains("movie2.ts"))
        assertEquals(10, result.removedSegments)
    }

    @Test
    fun `pre-roll longer than 90 seconds is not treated as an ad`() {
        val lines = mutableListOf(header, "#EXT-X-DISCONTINUITY")
        repeat(11) { i -> lines += "#EXTINF:10.0,"; lines += "content$i.ts" }
        lines += "#EXT-X-DISCONTINUITY"
        lines += "#EXTINF:10.0,"; lines += "movie.ts"
        lines += "#EXT-X-ENDLIST"
        val result = strip(lines.joinToString("\n"))
        assertTrue(result.playlist.contains("content0.ts"))
        assertEquals(0, result.removedSegments)
    }

    @Test
    fun `orphan discontinuity before the first segment is cleaned up`() {
        val text = listOf(
            header,
            "#EXT-X-DISCONTINUITY",
            "#EXTINF:10.0,", "movie.ts",
            "#EXT-X-ENDLIST"
        ).joinToString("\n")
        val result = strip(text)
        assertEquals(0, discCount(result.playlist))
        assertTrue(result.playlist.contains("movie.ts"))
    }

    @Test
    fun `byte order mark is removed`() {
        val text = "\uFEFF$header\n#EXTINF:10.0,\nmovie.ts\n#EXT-X-ENDLIST"
        assertFalse(strip(text).playlist.startsWith("\uFEFF"))
    }
}
