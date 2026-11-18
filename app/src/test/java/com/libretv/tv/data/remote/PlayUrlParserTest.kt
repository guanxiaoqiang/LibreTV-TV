package com.libretv.tv.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Apple CMS parsing rules against real-world payload shapes.
 * These are pure parsing tests and do not touch the network.
 */
class PlayUrlParserTest {

    @Test
    fun `parses single line play url`() {
        val raw = "第1集\$http://a.invalid/1.m3u8#第2集\$http://a.invalid/2.m3u8"
        val lines = parsePlayLines(raw)
        assertEquals(1, lines.size)
        assertEquals(2, lines[0].episodes.size)
        assertEquals("第1集", lines[0].episodes[0].name)
        assertEquals("http://a.invalid/1.m3u8", lines[0].episodes[0].url)
    }

    @Test
    fun `parses multiple lines separated by triple dollar`() {
        val raw = "云播\$\$https://a.invalid/1.m3u8#https://a.invalid/2.m3u8\$\$\$蓝光\$\$https://b.invalid/1.m3u8"
        val lines = parsePlayLines(raw)
        assertEquals(2, lines.size)
        assertEquals("云播", lines[0].name)
        assertEquals("蓝光", lines[1].name)
        assertEquals(2, lines[0].episodes.size)
        assertEquals(1, lines[1].episodes.size)
        assertEquals("https://b.invalid/1.m3u8", lines[1].episodes[0].url)
    }

    @Test
    fun `episode without name gets positional label`() {
        val raw = "\$\$\$https://a.invalid/1.m3u8"
        val lines = parsePlayLines(raw)
        assertEquals(1, lines.size)
        assertEquals("第1集", lines[0].episodes[0].name)
    }

    @Test
    fun `blank urls are dropped`() {
        val raw = "第1集\$#第2集\$https://a.invalid/2.m3u8"
        val lines = parsePlayLines(raw)
        assertEquals(1, lines.size)
        assertEquals(1, lines[0].episodes.size)
        assertEquals("第2集", lines[0].episodes[0].name)
    }

    @Test
    fun `empty input yields no lines`() {
        assertTrue(parsePlayLines(null).isEmpty())
        assertTrue(parsePlayLines("").isEmpty())
        assertTrue(parsePlayLines("garbage").isEmpty())
    }
}
