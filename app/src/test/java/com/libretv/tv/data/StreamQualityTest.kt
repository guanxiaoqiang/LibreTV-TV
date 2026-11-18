package com.libretv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the measured-quality parsing of HLS master playlists. */
class StreamQualityTest {

    @Test
    fun `the highest resolution variant wins`() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
            mid/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080
            high/index.m3u8
        """.trimIndent()

        val quality = parseHlsMaster(playlist)

        assertEquals(1920, quality?.width)
        assertEquals(1080, quality?.height)
        assertEquals(8_000_000, quality?.bandwidthBps)
        assertEquals(QualityTier.FULL_HD, quality?.tier)
    }

    @Test
    fun `a 4k variant is recognised`() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160
            uhd/index.m3u8
        """.trimIndent()

        assertEquals(QualityTier.UHD_4K, parseHlsMaster(playlist)?.tier)
    }

    @Test
    fun `a media playlist has no stream info`() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
        """.trimIndent()

        assertNull(parseHlsMaster(playlist))
    }

    @Test
    fun `bandwidth only entries fall back to a bitrate based tier`() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000
            index.m3u8
        """.trimIndent()

        val quality = parseHlsMaster(playlist)
        assertEquals(5_000_000, quality?.bandwidthBps)
        // 没有分辨率时按码率兜底：5 Mbps ≈ HD。
        assertEquals(QualityTier.HD, quality?.tier)
    }

    @Test
    fun `a tiny bitrate still reads as standard definition`() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=640x360
            index.m3u8
        """.trimIndent()

        assertEquals(QualityTier.SD, parseHlsMaster(playlist)?.tier)
    }

    @Test
    fun `quoted attributes with commas are parsed correctly`() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            index.m3u8
        """.trimIndent()

        val quality = parseHlsMaster(playlist)
        assertEquals(1920, quality?.width)
        assertEquals(1080, quality?.height)
    }

    @Test
    fun `quality tiers follow the measured height`() {
        assertEquals(QualityTier.UHD_4K, tierOf(StreamQuality(width = 3840, height = 2160)))
        assertEquals(QualityTier.FULL_HD, tierOf(StreamQuality(width = 1920, height = 1080)))
        assertEquals(QualityTier.HD, tierOf(StreamQuality(width = 1280, height = 720)))
        assertEquals(QualityTier.SD, tierOf(StreamQuality(width = 854, height = 480)))
        assertEquals(QualityTier.UNKNOWN, tierOf(StreamQuality()))
    }

    @Test
    fun `variants keep their own uri`() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
            hd/index.m3u8
        """.trimIndent()

        val variants = parseHlsVariants(playlist)

        assertEquals(2, variants.size)
        assertEquals("low/index.m3u8", variants[0].uri)
        assertEquals(360, variants[0].quality.height)
        assertEquals("hd/index.m3u8", variants[1].uri)
        assertEquals(720, variants[1].quality.height)
    }

    @Test
    fun `a media playlist yields no variants`() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            seg0.ts
        """.trimIndent()

        assertTrue(parseHlsVariants(playlist).isEmpty())
    }

    @Test
    fun `the first segment of a media playlist is found`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:9.9,
            /path/seg0.ts
            #EXTINF:9.9,
            seg1.ts
        """.trimIndent()

        assertEquals("/path/seg0.ts", firstSegmentUri(playlist))
    }

    @Test
    fun `hls urls are recognised`() {
        assertTrue(com.libretv.tv.data.remote.ProbeEngine.isHls("https://a.invalid/hd/index.m3u8"))
        assertTrue(com.libretv.tv.data.remote.ProbeEngine.isHls("https://a.invalid/hd/index.M3U8?token=1"))
        assertTrue(!com.libretv.tv.data.remote.ProbeEngine.isHls("https://a.invalid/movie.mp4"))
    }
}
