package com.libretv.tv.data.remote

import com.libretv.tv.domain.model.SourceConfig
import com.libretv.tv.domain.model.TvboxParseResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * TVBOX 配置解析。
 *
 * 一份 TVBOX 配置里什么都有：`sites` 里混着 Apple CMS 接口、Spider 接口、
 * XML 接口、外链 JSON 和直播源。本应用只吃 Apple CMS 那一种，所以解析的
 * 重点不是「尽量多导入」，而是**把不能导入的条目连原因一起告诉观众**——
 * 静默丢弃会让观众以为配置是坏的。
 */
object TvboxParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): TvboxParseResult {
        val body = text.trim()
        if (body.isEmpty()) return TvboxParseResult.Err("配置文件是空的")
        if (!body.startsWith("{")) return TvboxParseResult.Err("不是 TVBOX 配置（应以 { 开头）")

        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return TvboxParseResult.Err("配置文件不是合法 JSON")
        if (root !is JsonObject) return TvboxParseResult.Err("配置文件不是合法 JSON")

        val sites = root["sites"] as? JsonArray
            ?: return TvboxParseResult.Err("配置里没有 sites 段")

        val sources = mutableListOf<SourceConfig>()
        val skipped = mutableListOf<TvboxParseResult.Skipped>()

        sites.forEach { element ->
            val site = element as? JsonObject
            if (site == null) {
                skipped += TvboxParseResult.Skipped("(未命名)", "条目格式不对")
                return@forEach
            }
            val name = site.string("name").ifBlank { "(未命名)" }
            val api = site.string("api").trim()
            val type = site.int("type")

            when {
                api.isEmpty() -> skipped += TvboxParseResult.Skipped(name, SKIP_NO_API)
                !api.startsWith("http://") && !api.startsWith("https://") ->
                    skipped += TvboxParseResult.Skipped(name, SKIP_NOT_HTTP)
                api.endsWith(".js", ignoreCase = true) ->
                    skipped += TvboxParseResult.Skipped(name, SKIP_SPIDER)
                api.endsWith(".json", ignoreCase = true) ->
                    skipped += TvboxParseResult.Skipped(name, SKIP_EXTERNAL_JSON)
                type == TYPE_XML -> skipped += TvboxParseResult.Skipped(name, SKIP_XML)
                type == TYPE_LIVE -> skipped += TvboxParseResult.Skipped(name, SKIP_LIVE)
                !isAppleCms(api) -> skipped += TvboxParseResult.Skipped(name, SKIP_NOT_CMS)
                else -> sources += SourceConfig(
                    name = name,
                    url = api,
                    detail = site.string("detail"),
                    dialect = DIALECT_CMS
                )
            }
        }

        // 直播段单独看一眼：整份配置只有直播时，要给观众一句话而不是空列表。
        val lives = root["lives"] as? JsonArray
        if (sources.isEmpty() && lives != null && lives.isNotEmpty()) {
            return TvboxParseResult.Err("配置里只有直播源，本应用只支持点播的 Apple CMS 接口")
        }

        return TvboxParseResult.Ok(sources.distinctBy { it.url }, skipped)
    }

    /** Apple CMS 接口的特征：带 vod 资源路径或 ac= 参数。 */
    private fun isAppleCms(api: String): Boolean =
        api.contains("provide/vod", ignoreCase = true) ||
            api.contains("ac=list", ignoreCase = true) ||
            api.contains("ac=detail", ignoreCase = true) ||
            api.contains("ac=videolist", ignoreCase = true)

    private fun JsonObject.string(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: return ""
        return primitive.content
    }

    private fun JsonObject.int(key: String): Int {
        val primitive = this[key] as? JsonPrimitive ?: return -1
        return primitive.content.toIntOrNull() ?: -1
    }

    /** Apple CMS 方言标记。 */
    const val DIALECT_CMS = "cms"

    private const val TYPE_XML = 1
    private const val TYPE_LIVE = 0

    const val SKIP_NO_API = "缺少接口地址"
    const val SKIP_NOT_HTTP = "不是 http(s) 地址"
    const val SKIP_SPIDER = "Spider 接口，依赖 JS 解析"
    const val SKIP_EXTERNAL_JSON = "外链 JSON 配置，暂不支持"
    const val SKIP_XML = "XML 接口，暂不支持"
    const val SKIP_LIVE = "直播源，暂不支持"
    const val SKIP_NOT_CMS = "不是 Apple CMS 接口"
}
