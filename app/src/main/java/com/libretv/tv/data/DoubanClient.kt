package com.libretv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One movie from a Douban recommendation row. */
@Serializable
data class DoubanSubject(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("rate") val rate: String = "",
    @SerialName("cover") val cover: String = "",
    @SerialName("url") val url: String = "",
    @SerialName("year") val year: String = ""
)

@Serializable
data class DoubanSubjects(
    @SerialName("subjects") val subjects: List<DoubanSubject>? = null
)

/**
 * Douban recommendation feed.
 *
 * Douban exposes a small JSON endpoint that returns a ranked list of titles
 * without an API key. It is only a discovery surface: the titles are fed back
 * into the bundled collection sources to find a playable stream, so nothing
 * here ever touches the playback path.
 */
class DoubanClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private const val BASE = "https://movie.douban.com/j/search_subjects"

        /** The rows shown on the home screen, in display order. */
        val RECOMMEND_TABS = listOf(
            "热门" to "热门",
            "最新" to "最新",
            "高分" to "高分",
            "文艺" to "文艺",
            "喜剧" to "喜剧",
            "科幻" to "科幻",
            "动画" to "动画",
            "爱情" to "爱情",
            "动作" to "动作",
            "战争" to "战争"
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun recommendations(
        tag: String,
        limit: Int = 20,
        type: String = "movie"
    ): List<DoubanSubject> =
        withContext(Dispatchers.IO) {
            try {
                // The tag is Chinese text, so it has to be percent encoded or the
                // request comes back as an empty list.
                val encodedTag = java.net.URLEncoder.encode(tag, "UTF-8")
                val url = "$BASE?type=$type&tag=$encodedTag&sort=recommend" +
                    "&page_limit=$limit&page_start=0"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", CmsClient.USER_AGENT)
                    .header("Referer", "https://movie.douban.com/")
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val body = resp.body?.string().orEmpty()
                    json.decodeFromString<DoubanSubjects>(body).subjects.orEmpty()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
}
