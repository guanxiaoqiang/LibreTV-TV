package com.libretv.tv.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅清单的解析规则。
 *
 * 订阅内容是观众自己提供的远端 JSON，容错是第一要求：坏条目只跳过自己，
 * 不能让整次同步失败。
 */
class SourceListParserTest {

    @Test
    fun v2FormatKeepsOnlySources() {
        val body = """
            {
              "sources": [
                {"name": "A", "url": "https://a.example/api.php/provide/vod", "detail": "d"},
                {"name": "B", "url": "https://b.example/api.php/provide/vod"}
              ],
              "liveSources": [
                {"name": "Live", "url": "https://live.example/api.php/provide/vod"}
              ]
            }
        """.trimIndent()

        val result = SourceListParser.parse(body) as SourceListResult.Ok
        assertEquals(2, result.sources.size)
        assertEquals("A", result.sources[0].name)
        assertEquals("d", result.sources[0].detail)
        assertTrue(result.sources.none { it.name == "Live" })
    }

    @Test
    fun bareArrayIsAccepted() {
        val body = """[{"name":"A","url":"https://a.example/x"}]"""
        val result = SourceListParser.parse(body) as SourceListResult.Ok
        assertEquals(1, result.sources.size)
        assertEquals("https://a.example/x", result.sources[0].url)
    }

    @Test
    fun duplicateUrlsAreCollapsed() {
        val body = """
            [
              {"name":"A","url":"https://a.example/x"},
              {"name":"A again","url":"https://a.example/x"},
              {"name":"B","url":"https://b.example/x"}
            ]
        """.trimIndent()

        val result = SourceListParser.parse(body) as SourceListResult.Ok
        assertEquals(2, result.sources.size)
        assertEquals(2, result.total)
    }

    @Test
    fun entriesWithoutUrlAreSkipped() {
        val body = """
            [
              {"name":"no url"},
              {"name":"A","url":"https://a.example/x"},
              "not an object"
            ]
        """.trimIndent()

        val result = SourceListParser.parse(body) as SourceListResult.Ok
        assertEquals(1, result.sources.size)
    }

    @Test
    fun listIsTruncatedAtTheLimit() {
        val body = (1..120).joinToString(",", "[", "]") {
            """{"name":"S$it","url":"https://s$it.example/x"}"""
        }

        val result = SourceListParser.parse(body) as SourceListResult.Ok
        assertEquals(100, result.sources.size)
        assertEquals(120, result.total)
        assertEquals(20, result.truncated)
    }

    @Test
    fun malformedContentIsReportedAsError() {
        assertTrue(SourceListParser.parse("not json at all") is SourceListResult.Err)
        assertTrue(SourceListParser.parse("{broken") is SourceListResult.Err)
        assertTrue(SourceListParser.parse("") is SourceListResult.Err)
        assertTrue(SourceListParser.parse("""{"liveSources":[]}""") is SourceListResult.Err)
    }

    @Test
    fun missingNameFallsBackToHost() {
        val body = """[{"url":"https://a.example/api.php/provide/vod"}]"""
        val result = SourceListParser.parse(body) as SourceListResult.Ok
        assertEquals("a.example", result.sources[0].name)
    }
}
