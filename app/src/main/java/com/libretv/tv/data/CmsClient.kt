package com.libretv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Minimal Apple CMS v10 client.
 *
 * The endpoint shape is the same one the desktop LibreTV server uses:
 *   {base}?ac=videolist&wd={keyword}&pg=1        -> search
 *   {base}?ac=videolist&ids={id}                -> detail (includes vod_play_url)
 *   {base}?ac=detail&ids={id}                   -> detail (preferred, richer metadata)
 *
 * A few collectors use `?ac=list` for categories and some only honour `ac=videolist`,
 * so the detail request tries `ac=detail` first and falls back.
 */
class CmsClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
) {
    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }

    /** Short-timeout client used only for first-byte measurements. */
    private val probeClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun buildUrl(base: String, params: Map<String, String>): String {
        val sep = if (base.contains('?')) "&" else "?"
        return base + sep + params.entries.joinToString("&") { (k, v) ->
            "${k}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.string()
        }
    }

    private inline fun <reified T> decode(text: String): T? = try {
        json.decodeFromString<T>(text)
    } catch (e: Exception) {
        null
    }

    /**
     * Time the first byte of a playable stream. Only a kilobyte is read and the
     * response is closed immediately, so this is cheap enough to run for every
     * source in a picker. Returns null for non-HTTP schemes or a failed probe.
     */
    suspend fun probeStream(url: String): Long? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return@withContext null
        }
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-1023")
                .get()
                .build()
            val start = System.currentTimeMillis()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val buffer = ByteArray(1024)
                resp.body?.byteStream()?.use { it.read(buffer) }
                System.currentTimeMillis() - start
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Search one source. Returns null when the source is unreachable or malformed. */
    suspend fun search(base: String, keyword: String): List<VodItem>? = withContext(Dispatchers.IO) {
        val text = get(buildUrl(base, mapOf("ac" to "videolist", "wd" to keyword, "pg" to "1")))
            ?: return@withContext null
        decode<VodList>(text)?.list
    }

    /**
     * Read the source's category tree.
     *
     * Apple CMS builds disagree on the shape: most attach `class` to
     * `ac=list&t=1`, some honour `ac=class`, and a few only return it on a
     * plain `ac=list`. Try them in order and keep the first non-empty tree.
     */
    suspend fun categories(base: String): List<VodClass> = withContext(Dispatchers.IO) {
        val candidates = listOf(
            mapOf("ac" to "list", "t" to "1"),
            mapOf("ac" to "class"),
            mapOf("ac" to "list")
        )
        for (params in candidates) {
            val text = get(buildUrl(base, params)) ?: continue
            val classes = decode<VodList>(text)?.categories.orEmpty()
            if (classes.isNotEmpty()) return@withContext classes
        }
        emptyList()
    }

    /** One page of a category listing. `t` is the source's own type_id. */
    suspend fun categoryItems(base: String, typeId: Int, page: Int = 1): List<VodItem> =
        withContext(Dispatchers.IO) {
            val primary = mapOf(
                "ac" to "videolist",
                "t" to typeId.toString(),
                "pg" to page.toString()
            )
            val items = get(buildUrl(base, primary))?.let { decode<VodList>(it)?.list }
            if (!items.isNullOrEmpty()) return@withContext items

            val fallback = mapOf(
                "ac" to "list",
                "t" to typeId.toString(),
                "pg" to page.toString()
            )
            get(buildUrl(base, fallback))?.let { decode<VodList>(it)?.list }.orEmpty()
        }

    /** Fetch full detail including vod_play_url. */
    suspend fun detail(base: String, id: String): Pair<VodItem, String>? = withContext(Dispatchers.IO) {
        val detailText = get(buildUrl(base, mapOf("ac" to "detail", "ids" to id)))
        val parsedDetail = detailText?.let { decode<VodDetail>(it) }
        val fromDetail = parsedDetail?.vod?.firstOrNull()
        val playUrl = fromDetail?.let { extractPlayUrl(it) }

        if (fromDetail != null && playUrl != null) {
            return@withContext fromDetail to playUrl
        }

        // Fall back to videolist, which some collectors return for ids as well.
        val listText = get(buildUrl(base, mapOf("ac" to "videolist", "ids" to id)))
        val fromList = listText?.let { decode<VodList>(it) }?.list?.firstOrNull()
        val playUrl2 = fromList?.let { extractPlayUrl(it) }
        if (fromList != null && playUrl2 != null) fromList to playUrl2 else null
    }

    /**
     * vod_play_url looks like: 线路名$$$ep1$ep2$ep3#$$$线路2$$$...
     * Each episode is 名称$url.
     */
    private fun extractPlayUrl(item: VodItem): String? {
        val raw = item.vodPlayUrl
        if (raw.isBlank()) return null
        return raw
    }

    /**
     * Parse `vod_play_url` into playable lines.
     *
     * Real collectors emit several shapes for the same field, all of them are
     * accepted here:
     *
     *   A) `线路名$$$第1集$url#第2集$url$$$线路名2$$$...`   (classic maccms)
     *   B) `线路名$$第1集$url#第2集$url$$$线路名2$$$...`   (name glued to its block)
     *   C) `第1集$url#第2集$url`                            (single unnamed line)
     *
     * Episodes are separated by `#`, the episode name and the URL by a single `$`.
     * A segment without a `$` is treated as a bare URL, a segment that looks like
     * neither is treated as the name of the following line.
     */
    fun parsePlayLines(raw: String?): List<PlayLine> {
        if (raw.isNullOrBlank()) return emptyList()

        val lines = mutableListOf<PlayLine>()
        var pendingName: String? = null

        for (segment in raw.split("$$$")) {
            val seg = segment.trim()
            if (seg.isEmpty()) continue

            var explicitName: String? = null
            var content = seg
            val glueIdx = seg.indexOf("$$")
            if (glueIdx >= 0) {
                explicitName = seg.substring(0, glueIdx).trim().takeIf { it.isNotEmpty() }
                content = seg.substring(glueIdx + 2).trim()
            }

            val episodes = parseEpisodes(content)
            if (episodes.isNotEmpty()) {
                val name = explicitName
                    ?: pendingName
                    ?: "线路${lines.size + 1}"
                lines += PlayLine(lines.size, name, episodes)
                pendingName = null
            } else {
                // No episodes in this segment -> it carries the name of the next line.
                val carried = explicitName ?: content.takeIf { it.isNotBlank() }
                if (!carried.isNullOrBlank()) pendingName = carried
            }
        }
        return lines
    }

    /** Split one episode block into episodes. */
    private fun parseEpisodes(content: String): List<Episode> {
        if (content.isBlank()) return emptyList()
        val episodes = mutableListOf<Episode>()
        for (segment in content.split("#")) {
            val seg = segment.trim()
            if (seg.isEmpty()) continue
            val parts = seg.split("$", limit = 2)
            val name: String
            val url: String
            if (parts.size >= 2) {
                name = parts[0].trim()
                url = parts[1].trim()
            } else if (looksLikeUrl(seg)) {
                name = ""
                url = seg
            } else {
                continue
            }
            if (url.isEmpty()) continue
            val position = episodes.size
            episodes += Episode(position, name.ifBlank { "第${position + 1}集" }, url)
        }
        return episodes
    }

    private fun looksLikeUrl(value: String): Boolean {
        val lower = value.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("rtmp://") ||
            lower.contains(".m3u8") ||
            lower.contains(".mp4")
    }
}
