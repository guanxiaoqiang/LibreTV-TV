package com.libretv.tv.data.remote

import com.libretv.tv.domain.model.TvboxParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TVBOX 配置解析。
 *
 * 重点是「跳过也要说原因」：观众导入一份混着 Spider、XML、外链的配置，
 * 看到的是哪些能用、哪些不能用，而不是一个空列表。
 */
class TvboxParserTest {

    @Test
    fun validAppleCmsConfigIsImported() {
        val config = """
            {
              "spider": "https://cdn.example/spider.js",
              "sites": [
                {"key":"a","name":"A 站","type":3,"api":"https://a.example/api.php/provide/vod?ac=list"},
                {"key":"b","name":"B 站","type":3,"api":"https://b.example/api.php/provide/vod","detail":"d"}
              ]
            }
        """.trimIndent()

        val result = TvboxParser.parse(config) as TvboxParseResult.Ok

        assertEquals(2, result.sources.size)
        assertEquals("A 站", result.sources[0].name)
        assertEquals("cms", result.sources[0].dialect)
        assertEquals("d", result.sources[1].detail)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun spiderEntriesAreSkippedWithAReason() {
        val config = """
            {
              "sites": [
                {"key":"s","name":"Spider 站","type":3,"api":"https://cdn.example/spider.js"}
              ]
            }
        """.trimIndent()

        val result = TvboxParser.parse(config) as TvboxParseResult.Ok

        assertTrue(result.sources.isEmpty())
        assertEquals(1, result.skipped.size)
        assertEquals("Spider 站", result.skipped[0].name)
        assertEquals(TvboxParser.SKIP_SPIDER, result.skipped[0].reason)
    }

    @Test
    fun externalJsonEntriesAreSkippedWithAReason() {
        val config = """
            {
              "sites": [
                {"key":"e","name":"外链站","type":3,"api":"https://cdn.example/config.json"}
              ]
            }
        """.trimIndent()

        val result = TvboxParser.parse(config) as TvboxParseResult.Ok

        assertTrue(result.sources.isEmpty())
        assertEquals(TvboxParser.SKIP_EXTERNAL_JSON, result.skipped[0].reason)
    }

    @Test
    fun xmlAndLiveEntriesAreSkipped() {
        val config = """
            {
              "sites": [
                {"key":"x","name":"XML 站","type":1,"api":"https://x.example/api.php/provide/vod"},
                {"key":"l","name":"直播站","type":0,"api":"https://l.example/api.php/provide/vod"}
              ]
            }
        """.trimIndent()

        val result = TvboxParser.parse(config) as TvboxParseResult.Ok

        assertTrue(result.sources.isEmpty())
        assertEquals(2, result.skipped.size)
        assertTrue(result.skipped.any { it.reason == TvboxParser.SKIP_XML })
        assertTrue(result.skipped.any { it.reason == TvboxParser.SKIP_LIVE })
    }

    @Test
    fun entriesWithoutApiAreSkipped() {
        val config = """{"sites":[{"key":"n","name":"空站"}]}"""

        val result = TvboxParser.parse(config) as TvboxParseResult.Ok

        assertTrue(result.sources.isEmpty())
        assertEquals(TvboxParser.SKIP_NO_API, result.skipped[0].reason)
    }

    @Test
    fun nonAppleCmsEndpointsAreSkipped() {
        val config = """{"sites":[{"key":"x","name":"别的站","api":"https://x.example/custom"}]}"""

        val result = TvboxParser.parse(config) as TvboxParseResult.Ok

        assertTrue(result.sources.isEmpty())
        assertEquals(TvboxParser.SKIP_NOT_CMS, result.skipped[0].reason)
    }

    @Test
    fun emptyConfigIsReportedAsError() {
        assertTrue(TvboxParser.parse("") is TvboxParseResult.Err)
        assertTrue(TvboxParser.parse("{}") is TvboxParseResult.Err)
        assertTrue(TvboxParser.parse("not json") is TvboxParseResult.Err)
        assertTrue(TvboxParser.parse("{broken") is TvboxParseResult.Err)
    }

    @Test
    fun liveOnlyConfigExplainsItself() {
        val config = """
            {
              "sites": [],
              "lives": [{"name":"直播","type":0,"url":"https://live.example/api.php/provide"}]
            }
        """.trimIndent()

        val result = TvboxParser.parse(config)

        assertTrue(result is TvboxParseResult.Err)
        assertTrue((result as TvboxParseResult.Err).message.contains("直播"))
    }

    @Test
    fun duplicateUrlsAreCollapsed() {
        val config = """
            {
              "sites": [
                {"name":"A","api":"https://a.example/api.php/provide/vod?ac=list"},
                {"name":"A 副本","api":"https://a.example/api.php/provide/vod?ac=list"}
              ]
            }
        """.trimIndent()

        val result = TvboxParser.parse(config) as TvboxParseResult.Ok

        assertEquals(1, result.sources.size)
    }
}
