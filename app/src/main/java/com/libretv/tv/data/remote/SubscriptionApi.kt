package com.libretv.tv.data.remote

import com.libretv.tv.core.ApiResult
import com.libretv.tv.domain.model.SourceConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 订阅地址的抓取。
 *
 * 订阅内容是「谁都可以写的远端 JSON」，所以解析必须容错：字段缺失、
 * 类型不对、条目不是对象，都只跳过那一条，不能让整次同步失败。
 */
class SubscriptionApi(private val http: HttpEngine) {

    /** 拉取订阅正文。 */
    suspend fun fetch(url: String): ApiResult<String> = http.getText(url)
}

/** 订阅清单的解析结果。 */
sealed class SourceListResult {

    /** 解析成功。 */
    data class Ok(
        /** 实际保留的源（已去重、已截断）。 */
        val sources: List<SourceConfig>,
        /** 远端一共给了多少条。 */
        val total: Int,
        /** 因超出上限被丢弃的条数。 */
        val truncated: Int
    ) : SourceListResult()

    /** 解析失败。 */
    data class Err(val message: String) : SourceListResult()
}

/**
 * 解析订阅正文。
 *
 * 支持两种形态（design D4）：
 *
 *  - SourceList v2：`{"sources": [...], "liveSources": [...]}`，只取 `sources`；
 *  - 裸数组：`[{...}, {...}]`。
 *
 * 条目按地址去重，超出上限的部分截断并计数——同步时会把这个数字
 * 告诉观众，而不是悄悄丢掉。
 */
object SourceListParser {

    /** 单个订阅最多导入的源数量。 */
    const val MAX_SOURCES = 100

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String, limit: Int = MAX_SOURCES): SourceListResult {
        val text = body.trim()
        if (text.isEmpty()) return SourceListResult.Err("订阅内容为空")
        if (!text.startsWith("{") && !text.startsWith("[")) {
            return SourceListResult.Err("订阅内容不是 JSON")
        }

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return SourceListResult.Err("订阅内容不是合法 JSON")

        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["sources"] as? JsonArray
                ?: return SourceListResult.Err("订阅内容里没有 sources 数组")
            else -> return SourceListResult.Err("订阅内容格式无法识别")
        }

        val parsed = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.string("url").ifBlank { obj.string("api") }
            if (url.isBlank()) return@mapNotNull null
            SourceConfig(
                name = obj.string("name").ifBlank { url.substringAfter("://").substringBefore("/") },
                url = url,
                detail = obj.string("detail"),
                dialect = obj.string("dialect")
            )
        }

        val distinct = parsed.distinctBy { it.url }
        val kept = distinct.take(limit)
        return SourceListResult.Ok(
            sources = kept,
            total = distinct.size,
            truncated = (distinct.size - kept.size).coerceAtLeast(0)
        )
    }

    private fun JsonObject.string(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: return ""
        return primitive.content
    }
}
