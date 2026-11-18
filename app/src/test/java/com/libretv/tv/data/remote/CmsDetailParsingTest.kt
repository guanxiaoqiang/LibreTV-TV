package com.libretv.tv.data.remote

import com.libretv.tv.domain.model.VodDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情响应的包装形式。
 *
 * 真实采集站五花八门：标准 maccms 把数组放在 `vod`，但大量变种（包括
 * maoyanapi 这类）放在 `list`，还有的裹一层 `data`。只认一种包装的代价是
 * 整批源被判死——表现为「所有影片都没有播放源」。
 */
class CmsDetailParsingTest {

    private val dialect = CmsDialects.byId("standard")

    @Test
    fun `list wrapper is parsed like vod`() {
        val text = """
            {"code":1,"msg":"数据列表","page":1,"pagecount":1,"limit":20,"total":1,
             "list":[{"vod_id":137597,"vod_name":"流浪地球","vod_play_from":"正片",
                      "vod_play_url":"正片${'$'}https://play.maoyanplay.top/20250827/uqZIh70s/index.m3u8"}]}
        """.trimIndent()
        val item = dialect.parseDetail(text)
        assertNotNull("list 包装的详情必须能解析出来", item)
        assertEquals("流浪地球", item?.vodName)
        assertTrue(item!!.vodPlayUrl.contains("index.m3u8"))
    }

    @Test
    fun `vod wrapper still works`() {
        val text = """{"vod":[{"vod_id":1,"vod_name":"沙丘","vod_play_url":"正片${'$'}https://a/1.m3u8"}]}"""
        val item = dialect.parseDetail(text)
        assertEquals("沙丘", item?.vodName)
    }

    @Test
    fun `data nested wrapper is parsed`() {
        val text = """{"data":{"list":[{"vod_id":7,"vod_name":"沙丘2","vod_play_url":"正片${'$'}https://a/2.m3u8"}]}}"""
        val item = dialect.parseDetail(text)
        assertEquals("沙丘2", item?.vodName)
    }

    @Test
    fun `firstItem prefers vod then list then data`() {
        val both = VodDetail(
            vod = listOf(com.libretv.tv.domain.model.VodItem(vodId = "1", vodName = "A")),
            list = listOf(com.libretv.tv.domain.model.VodItem(vodId = "2", vodName = "B"))
        )
        assertEquals("A", both.firstItem()?.vodName)

        val onlyList = VodDetail(list = listOf(com.libretv.tv.domain.model.VodItem(vodId = "2", vodName = "B")))
        assertEquals("B", onlyList.firstItem()?.vodName)

        val nested = VodDetail(data = onlyList)
        assertEquals("B", nested.firstItem()?.vodName)
    }

    @Test
    fun `play url of a list response yields a playable line`() {
        // 详情接口失灵时，列表响应里自带的 vod_play_url 就是兜底线索。
        val lines = parsePlayLines("正片${'$'}https://play.maoyanplay.top/20250827/uqZIh70s/index.m3u8")
        assertEquals(1, lines.size)
        assertEquals(1, lines.first().episodes.size)
        assertEquals("https://play.maoyanplay.top/20250827/uqZIh70s/index.m3u8", lines.first().episodes.first().url)
    }
}
