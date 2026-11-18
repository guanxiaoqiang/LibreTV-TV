package com.libretv.tv.data.remote

import com.libretv.tv.data.QualityTier
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for the stream probe engine: HLS walks down to the first segment, MP4 does not. */
class ProbeEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: ProbeEngine

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = ProbeEngine()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url(path: String): String = server.url(path).toString()

    @Test
    fun `an hls master is followed down to the first segment`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
                low/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
                hd/index.m3u8
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:10
                #EXTINF:9.9,
                seg0.ts
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2048))))

        val result = engine.probe(url("/master.m3u8"))

        assertNotNull(result.firstByteMs)
        assertEquals(QualityTier.FULL_HD, result.quality?.tier)
        assertEquals(6_000_000, result.bandwidthBps)
        assertTrue("应该探测到分片而不是清单", result.probedUrl!!.endsWith("seg0.ts"))
    }

    @Test
    fun `a relative variant url is resolved against the master`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1280x720
                hd/index.m3u8
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                #EXTM3U
                #EXTINF:9.9,
                seg0.ts
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2048))))

        val result = engine.probe(url("/master.m3u8"))

        assertTrue("变体地址应被解析成绝对地址", result.probedUrl!!.startsWith("http"))
        assertTrue(result.probedUrl!!.endsWith("hd/seg0.ts"))
    }

    @Test
    fun `a direct mp4 is probed without reading a manifest`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2048))))

        val result = engine.probe(url("/movie.mp4"))

        assertNotNull(result.firstByteMs)
        assertNull("直链没有清单，实测画质应为空", result.quality)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a non-http url is rejected`() = runBlocking {
        val result = engine.probe("rtmp://a.invalid/live")

        assertNull(result.firstByteMs)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a failing manifest leaves the probe empty`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = engine.probe(url("/master.m3u8"))

        assertNull(result.firstByteMs)
    }
}
