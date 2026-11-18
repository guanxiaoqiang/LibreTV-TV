package com.libretv.tv.data.remote

import com.libretv.tv.core.SourceError
import com.libretv.tv.data.SourceConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the CMS API layer against a scripted server: dialect fallback
 * chains, error classification, pagination parameters and timeouts.
 */
class CmsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CmsApiImpl
    private lateinit var engine: HttpEngine

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = HttpEngine(maxRetries = 0)
        api = CmsApiImpl(engine)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun source(dialect: String = "standard") = SourceConfig(
        name = "test",
        url = server.url("/api.php/provide/vod").toString(),
        dialect = dialect
    )

    private fun listBody(name: String, id: String = "1") = """
        {"list":[{"vod_id":"$id","vod_name":"$name","vod_pic":"","vod_year":"2024",
        "vod_area":"","type_name":"","vod_remarks":"HD","vod_content":"","vod_play_url":""}],
        "total":1,"page":1,"pagecount":1,"limit":20}
    """.trimIndent()

    @Test
    fun `search hits the first candidate`() = runBlocking {
        server.enqueue(MockResponse().setBody(listBody("流浪地球")))

        val result = api.search(source(), "流浪地球")

        assertTrue(result.isOk)
        assertEquals(1, result.getOrNull()!!.size)
        assertEquals(1, server.requestCount)
        val path = server.takeRequest().path.orEmpty()
        assertTrue("搜索应带 wd 参数", path.contains("wd="))
        assertTrue("搜索应带 pg 参数", path.contains("pg=1"))
    }

    @Test
    fun `search falls through to the second candidate in the lenient dialect`() = runBlocking {
        val lenient = source(dialect = "lenient")
        // 第一个候选（ac=videolist）返回无法解析的正文。
        server.enqueue(MockResponse().setBody("<html>blocked</html>"))
        // 第二个候选（ac=list）返回正常列表。
        server.enqueue(MockResponse().setBody(listBody("流浪地球")))

        val result = api.search(lenient, "流浪地球")

        assertTrue(result.isOk)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest().path.orEmpty()
        assertTrue(first.contains("ac=videolist"))
        val second = server.takeRequest().path.orEmpty()
        assertTrue("宽松方言应回退到 ac=list", second.contains("ac=list"))
    }

    @Test
    fun `a chain where no candidate parses yields an empty result`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("not json")) }

        val result = api.search(source(dialect = "lenient"), "流浪地球")

        // 空结果不是失败：源答了，只是没有内容。
        assertTrue(result.isOk)
        assertTrue(result.getOrNull()!!.isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `detail without a usable play url yields EmptyResult`() = runBlocking {
        repeat(2) {
            server.enqueue(
                MockResponse().setBody("""{"vod":[{"vod_id":"9","vod_name":"某片","vod_play_url":""}]}""")
            )
        }

        val result = api.detail(source(), "9")

        val err = result as com.libretv.tv.core.ApiResult.Err
        assertTrue("详情没有播放地址才算真失败", err.error is SourceError.EmptyResult)
    }

    @Test
    fun `an http 403 is classified as blocked`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = api.search(source(), "流浪地球")

        val err = result as com.libretv.tv.core.ApiResult.Err
        assertTrue("403 应归为风控", err.error is SourceError.Blocked)
    }

    @Test
    fun `an http 500 is classified as http failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = api.search(source(), "流浪地球")

        val err = result as com.libretv.tv.core.ApiResult.Err
        assertTrue(err.error is SourceError.HttpFailure)
        assertEquals(503, (err.error as SourceError.HttpFailure).code)
    }

    @Test
    fun `a timeout is classified as timeout`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(listBody("流浪地球"))
                .setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
        )

        val fastApi = CmsApiImpl(engine, chainTimeoutMs = 300)
        val result = fastApi.search(source(), "流浪地球")

        val err = result as com.libretv.tv.core.ApiResult.Err
        assertTrue(err.error is SourceError.Timeout)
    }

    @Test
    fun `pagination uses the requested page`() = runBlocking {
        server.enqueue(MockResponse().setBody(listBody("第2页")))

        api.categoryItems(source(), typeId = 6, page = 3)

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.contains("t=6"))
        assertTrue(path.contains("pg=3"))
    }

    @Test
    fun `detail falls back from ac=detail to ac=videolist`() = runBlocking {
        val detailBody = """
            {"vod":[{"vod_id":"9","vod_name":"某片","vod_play_url":"线路1${'$'}${'$'}https://a.invalid/1.m3u8"}]}
        """.trimIndent()
        // ac=detail 返回没有 play_url 的条目 → 不合用。
        server.enqueue(MockResponse().setBody("""{"vod":[{"vod_id":"9","vod_name":"某片","vod_play_url":""}]}"""))
        // ac=videolist 返回完整的。
        server.enqueue(MockResponse().setBody(detailBody))

        val result = api.detail(source(), "9")

        assertTrue(result.isOk)
        assertEquals("线路1", parsePlayLines(result.getOrNull()!!.playUrl).first().name)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an empty category tree still proves the source is alive`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"list":[],"class":[]}"""))

        val result = api.probe(source())

        assertTrue(result.isOk)
        assertTrue(result.getOrNull()!!.isEmpty())
    }
}
