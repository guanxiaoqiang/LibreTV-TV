package com.libretv.tv.data

import com.libretv.tv.domain.model.BackupBundle
import com.libretv.tv.domain.model.BackupSettings
import com.libretv.tv.domain.model.FavoriteEntry
import com.libretv.tv.domain.model.SourceConfig
import com.libretv.tv.domain.model.Subscription
import com.libretv.tv.domain.model.WatchRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份包的编解码。
 *
 * 坏文件必须给出人话错误——导入是观众主动做的操作，报一句「JSON 解析失败」
 * 等于让人自己猜。
 */
class BackupCodecTest {

    private fun sample() = BackupBundle(
        exportedAt = 1_700_000_000_000L,
        sources = listOf(
            SourceConfig(name = "演示", url = "http://demo.com/api.php/provide/vod", detail = "", dialect = "cms")
        ),
        subscriptions = listOf(Subscription(id = "s1", name = "订阅", url = "http://demo.com/list.json")),
        settings = BackupSettings(adFilterEnabled = false, skipMarksEnabled = true),
        history = listOf(
            WatchRecord(key = "k1", title = "片子", positionMs = 120_000L, durationMs = 2_400_000L)
        ),
        favorites = listOf(
            FavoriteEntry(titleKey = "片子", title = "片子", sourceName = "演示", createdAt = 42L)
        )
    )

    @Test
    fun `bundle survives a round trip`() {
        val original = sample()
        val decoded = BackupCodec.decode(BackupCodec.encode(original)).getOrThrow()

        assertEquals(original.version, decoded.version)
        assertEquals(original.exportedAt, decoded.exportedAt)
        assertEquals(original.sources, decoded.sources)
        assertEquals(original.subscriptions, decoded.subscriptions)
        assertEquals(original.settings, decoded.settings)
        assertEquals(original.history, decoded.history)
        assertEquals(original.favorites, decoded.favorites)
    }

    @Test
    fun `unknown version is rejected`() {
        val text = """{"version":99,"exportedAt":0}"""

        val result = BackupCodec.decode(text)

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("99"))
    }

    @Test
    fun `broken json is rejected with a readable message`() {
        val result = BackupCodec.decode("{not json")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("备份文件"))
    }

    @Test
    fun `empty file is rejected`() {
        val result = BackupCodec.decode("   ")

        assertTrue(result.isFailure)
    }
}
