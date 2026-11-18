package com.libretv.tv.data.remote

import com.libretv.tv.core.ApiResult
import com.libretv.tv.core.Logger
import com.libretv.tv.core.SourceError
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/** 豆瓣推荐条带里的一部影片已迁至 `domain/model/Douban.kt`。 */
typealias DoubanSubject = com.libretv.tv.domain.model.DoubanSubject

@Serializable
private data class DoubanSubjects(
    @SerialName("subjects") val subjects: List<DoubanSubject>? = null
)

/**
 * 豆瓣推荐接口契约。
 *
 * 豆瓣只提供「看什么」的线索；可播放的流永远来自采集源，所以这里
 * 的失败按发现条带处理（条带置空），不影响播放路径。
 */
interface DoubanApi {
    suspend fun recommendations(
        tag: String,
        limit: Int = 20,
        type: String = "movie",
        pageStart: Int = 0
    ): ApiResult<List<DoubanSubject>>
}

class DoubanApiImpl(
    private val engine: HttpEngine,
    private val json: Json = CmsDialects.defaultJson
) : DoubanApi {
    companion object {
        private const val TAG = "DoubanApi"
        private const val BASE = "https://movie.douban.com/j/search_subjects"
    }

    override suspend fun recommendations(
        tag: String,
        limit: Int,
        type: String,
        pageStart: Int
    ): ApiResult<List<DoubanSubject>> {
        val started = System.currentTimeMillis()
        // The tag is Chinese text, so it has to be percent encoded or the
        // request comes back as an empty list. page_start drives the
        // segmented loading of each genre strip.
        val encodedTag = java.net.URLEncoder.encode(tag, "UTF-8")
        val url = "$BASE?type=$type&tag=$encodedTag&sort=recommend" +
            "&page_limit=$limit&page_start=$pageStart"
        return when (val resp = engine.getText(url)) {
            is ApiResult.Ok -> {
                val subjects = try {
                    json.decodeFromString<DoubanSubjects>(resp.value).subjects.orEmpty()
                } catch (e: Exception) {
                    Logger.w(TAG, "豆瓣「$tag」解析失败：${e.message}")
                    return ApiResult.Err(SourceError.Malformed, e.message)
                }
                Logger.d(TAG, "豆瓣「$tag」-> ${subjects.size} 条（${System.currentTimeMillis() - started}ms）")
                ApiResult.Ok(subjects)
            }
            is ApiResult.Err -> resp
        }
    }
}
