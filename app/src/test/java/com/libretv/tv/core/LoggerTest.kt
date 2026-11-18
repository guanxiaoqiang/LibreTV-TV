package com.libretv.tv.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the leveled logger: filtering, ordering and sink failures. */
class LoggerTest {

    private class RecordingSink : LogSink {
        val entries = mutableListOf<Triple<LogLevel, String, String>>()

        override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
            entries += Triple(level, tag, message)
        }
    }

    private val sink = RecordingSink()
    private val originalSink = Logger.sink

    @After
    fun tearDown() {
        Logger.level = LogLevel.INFO
        Logger.sink = originalSink
    }

    @Test
    fun `records below the configured level are dropped`() {
        Logger.sink = sink
        Logger.level = LogLevel.INFO

        Logger.d("Tag", "debug")
        Logger.i("Tag", "info")
        Logger.w("Tag", "warn")
        Logger.e("Tag", "error")

        assertEquals(listOf("info", "warn", "error"), sink.entries.map { it.third })
        assertEquals(LogLevel.INFO, sink.entries[0].first)
    }

    @Test
    fun `debug level keeps everything but off keeps nothing`() {
        Logger.sink = sink

        Logger.level = LogLevel.DEBUG
        Logger.d("Tag", "debug")
        Logger.level = LogLevel.OFF
        Logger.i("Tag", "info")
        Logger.e("Tag", "error")

        assertEquals(listOf("debug"), sink.entries.map { it.third })
    }

    @Test
    fun `long tags are truncated to the logcat limit`() {
        Logger.sink = sink
        Logger.level = LogLevel.DEBUG

        Logger.i("AVeryLongTagNameThatExceedsTheLimit", "hello")

        assertEquals(23, sink.entries.single().second.length)
    }

    @Test
    fun `a failing sink never breaks the caller`() {
        Logger.sink = LogSink { _, _, _, _ -> throw IllegalStateException("boom") }
        Logger.level = LogLevel.DEBUG

        Logger.i("Tag", "still fine")

        assertTrue(true)
    }

    @Test
    fun `level names round trip through parse`() {
        for (level in LogLevel.values()) {
            assertEquals(level, LogLevel.parse(level.name))
        }
        assertEquals(LogLevel.INFO, LogLevel.parse(null))
        assertEquals(LogLevel.INFO, LogLevel.parse("nonsense"))
    }
}
