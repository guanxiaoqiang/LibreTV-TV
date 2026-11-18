package com.libretv.tv.data.remote

import com.libretv.tv.core.Logger
import com.libretv.tv.data.HlsVariant
import com.libretv.tv.data.StreamQuality
import com.libretv.tv.data.firstSegmentUri
import com.libretv.tv.data.parseHlsVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 流探活引擎。
 *
 * 「这个源能不能播」不是一次请求就能回答的：
 *
 * - **HLS**（`.m3u8`）：请求拿到的是**清单**，不是视频。清单能秒回不代表能播，
 *   所以要顺着主清单 → 媒体清单 → 第一个分片走一遍，量的是真正的首片首字节；
 * - **MP4 / 直链**：直接量首字节即可。
 *
 * 顺带把主清单里的 `RESOLUTION` / `BANDWIDTH` 取出来，这就是实测画质——
 * 比采集站写在备注里的声明可靠得多。
 */
class ProbeEngine(
    /** 探活专用 client：超时更短，失败要快，别占用播放连接。 */
    private val client: OkHttpClient = defaultClient()
) {
    companion object {
        private const val TAG = "ProbeEngine"

        /** 主清单最多读 64KB：真实的变体列表远小于这个量。 */
        private const val MAX_PLAYLIST_BYTES = 64 * 1024

        /** 媒体清单最多读 64KB。 */
        private const val MAX_MEDIA_PLAYLIST_BYTES = 64 * 1024

        /** 探活专用超时：起播延迟的测量值如果包含一次 18s 超时就没有意义了。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /** 判断一个地址是不是 HLS 清单。 */
        fun isHls(url: String): Boolean =
            url.substringBefore('?').contains(".m3u8", ignoreCase = true)
    }

    /** 一次探活的结论。 */
    data class Result(
        /** 首字节耗时（毫秒）；探测失败为 `null`。 */
        val firstByteMs: Long?,
        /** 实测清晰度；无法实测为 `null`。 */
        val quality: StreamQuality?,
        /** 实测码率（bps）。 */
        val bandwidthBps: Int,
        /** 实际被探测的分片地址（HLS 时为分片，MP4 时为原地址）。 */
        val probedUrl: String?
    )

    /**
     * 探测一个播放地址。
     *
     * HLS 会一路读到第一个分片；直链只读首字节。失败时返回 `firstByteMs = null`
     * 的结果，调用方按「这个源不可用」处理。
     */
    suspend fun probe(url: String): Result = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return@withContext Result(null, null, 0, null)
        }
        try {
            if (isHls(url)) probeHls(url) else probeDirect(url)
        } catch (e: Exception) {
            Logger.w(TAG, "探测失败：${HttpEngine.redact(url)}（${e.message}）")
            Result(null, null, 0, null)
        }
    }

    /** 直链（MP4 等）：量首字节。 */
    private fun probeDirect(url: String): Result {
        val ms = measureFirstByte(url) ?: return Result(null, null, 0, null)
        return Result(ms, null, 0, url)
    }

    /**
     * HLS：主清单 → 最佳变体 → 媒体清单 → 第一个分片。
     *
     * 中间任何一步读不到，就退回到「只量清单的首字节」——清单拿得到至少说明
     * 这个地址是活的。
     */
    private fun probeHls(url: String): Result {
        val master = readText(url, MAX_PLAYLIST_BYTES)
        if (master == null) {
            val ms = measureFirstByte(url)
            return Result(ms, null, 0, url)
        }

        val variants = parseHlsVariants(master)
        val best = variants.maxWithOrNull(
            compareBy<HlsVariant> { it.quality.height }.thenBy { it.quality.bandwidthBps }
        )

        // 主清单里没有变体：它本身就是媒体清单，直接找首片。
        val mediaUrl = best?.let { resolve(url, it.uri) } ?: url
        val media = if (best != null) readText(mediaUrl, MAX_MEDIA_PLAYLIST_BYTES) else master

        val segment = media?.let { firstSegmentUri(it) }?.let { resolve(mediaUrl, it) }
        val probed = segment ?: mediaUrl
        val ms = measureFirstByte(probed)

        return Result(
            firstByteMs = ms,
            quality = best?.quality,
            bandwidthBps = best?.quality?.bandwidthBps ?: 0,
            probedUrl = probed
        )
    }

    /** 读一段文本，限制最大字节数。 */
    private fun readText(url: String, limit: Int): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpEngine.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.byteStream()?.use { input ->
                val buffer = ByteArray(limit)
                val read = input.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
            }
        }
    } catch (e: Exception) {
        Logger.d(TAG, "读取清单失败：${HttpEngine.redact(url)}（${e.message}）")
        null
    }

    /** 量首字节耗时：只读 1KB 就断开。 */
    private fun measureFirstByte(url: String): Long? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpEngine.USER_AGENT)
            .header("Range", "bytes=0-1023")
            .get()
            .build()
        val start = System.currentTimeMillis()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val buffer = ByteArray(1024)
            resp.body?.byteStream()?.use { it.read(buffer) }
            System.currentTimeMillis() - start
        }
    } catch (e: Exception) {
        Logger.d(TAG, "首字节探测失败：${HttpEngine.redact(url)}（${e.message}）")
        null
    }

    /** 把清单里的相对地址解析成绝对地址。 */
    private fun resolve(base: String, relative: String): String {
        if (relative.startsWith("http://", true) || relative.startsWith("https://", true)) {
            return relative
        }
        val baseUrl = base.toHttpUrlOrNull() ?: return relative
        return baseUrl.resolve(relative)?.toString() ?: relative
    }
}
