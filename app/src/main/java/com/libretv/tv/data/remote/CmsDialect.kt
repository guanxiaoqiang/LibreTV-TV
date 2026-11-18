package com.libretv.tv.data.remote

import com.libretv.tv.data.VodClass
import com.libretv.tv.data.VodDetail
import com.libretv.tv.data.VodItem
import com.libretv.tv.data.VodList
import kotlinx.serialization.json.Json

/**
 * 采集源的「方言」。
 *
 * Apple CMS 号称统一接口，实际上每个采集站对参数的理解都不一样：
 * 有的只认 `ac=videolist`，有的只认 `ac=list`，有的把分类树挂在
 * `ac=list&t=1` 的响应里。这些差异之前散在 `CmsClient` 的 if/else 里，
 * 换一个源就要改代码。
 *
 * 方言把「一个接口该怎么问、问不通再怎么问」变成**配置**：
 * 每个方言给出一条**候选链**，调用方按顺序问，命中即止。
 *
 * 预算约定（`design-detail.md` §E.1）：**整条候选链共享一个截止时间**，
 * 不是每个候选各享一个——否则最坏情况下一个源要等 3 × 超时。
 */
interface CmsDialect {
    /** 方言标识，写入 [com.libretv.tv.data.SourceConfig.dialect]。 */
    val id: String

    /** 搜索候选链。 */
    fun searchCandidates(base: String, keyword: String, page: Int): List<Map<String, String>>

    /** 详情候选链（`vod_play_url` 只在这些响应里有）。 */
    fun detailCandidates(base: String, vodId: String): List<Map<String, String>>

    /** 分类树候选链。 */
    fun categoryCandidates(base: String): List<Map<String, String>>

    /** 分类片单候选链。 */
    fun categoryItemCandidates(base: String, typeId: Int, page: Int): List<Map<String, String>>

    /** 探活候选链：最便宜的一个请求放最前面。 */
    fun probeCandidates(base: String): List<Map<String, String>>

    /** 解析列表响应（搜索 / 分类片单共用）。 */
    fun parseList(text: String): List<VodItem>?

    /** 解析分类树。 */
    fun parseCategories(text: String): List<VodClass>?

    /** 解析详情响应。 */
    fun parseDetail(text: String): VodItem?

    /** 构造请求地址。 */
    fun url(base: String, params: Map<String, String>): String {
        val sep = if (base.contains('?')) "&" else "?"
        return base + sep + params.entries.joinToString("&") { (k, v) ->
            "${k}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
    }
}

/** 标准 maccms v10 方言：绝大多数采集源都属于这一类。 */
class StandardCmsDialect(private val json: Json = CmsDialects.defaultJson) : CmsDialect {
    override val id: String = "standard"

    override fun searchCandidates(base: String, keyword: String, page: Int) = listOf(
        mapOf("ac" to "videolist", "wd" to keyword, "pg" to page.toString())
    )

    override fun detailCandidates(base: String, vodId: String) = listOf(
        mapOf("ac" to "detail", "ids" to vodId),
        mapOf("ac" to "videolist", "ids" to vodId)
    )

    override fun categoryCandidates(base: String) = listOf(
        mapOf("ac" to "list"),
        mapOf("ac" to "list", "t" to "1"),
        mapOf("ac" to "class")
    )

    override fun categoryItemCandidates(base: String, typeId: Int, page: Int) = listOf(
        mapOf("ac" to "videolist", "t" to typeId.toString(), "pg" to page.toString()),
        mapOf("ac" to "list", "t" to typeId.toString(), "pg" to page.toString())
    )

    override fun probeCandidates(base: String) = listOf(mapOf("ac" to "list"))

    override fun parseList(text: String): List<VodItem>? = decode<VodList>(text)?.list

    override fun parseCategories(text: String): List<VodClass>? = decode<VodList>(text)?.categories

    override fun parseDetail(text: String): VodItem? = decode<VodDetail>(text)?.firstItem()

    private inline fun <reified T> decode(text: String): T? = try {
        json.decodeFromString<T>(text)
    } catch (e: Exception) {
        null
    }
}

/**
 * 宽松方言：给那些参数理解更「随性」的采集源。
 *
 * 与标准方言的差异是**候选链更长**，而不是参数更怪——多一个候选
 * 就多一次被理解的机会。代价是每个不命中的候选都要消耗一段预算，
 * 所以链上的每一项都要真正值得问。
 */
class LenientCmsDialect(private val json: Json = CmsDialects.defaultJson) : CmsDialect {
    override val id: String = "lenient"

    override fun searchCandidates(base: String, keyword: String, page: Int) = listOf(
        mapOf("ac" to "videolist", "wd" to keyword, "pg" to page.toString()),
        mapOf("ac" to "list", "wd" to keyword, "pg" to page.toString())
    )

    override fun detailCandidates(base: String, vodId: String) = listOf(
        mapOf("ac" to "detail", "ids" to vodId),
        mapOf("ac" to "videolist", "ids" to vodId),
        mapOf("ac" to "list", "ids" to vodId)
    )

    override fun categoryCandidates(base: String) = listOf(
        mapOf("ac" to "list"),
        mapOf("ac" to "list", "t" to "1"),
        mapOf("ac" to "class")
    )

    override fun categoryItemCandidates(base: String, typeId: Int, page: Int) = listOf(
        mapOf("ac" to "videolist", "t" to typeId.toString(), "pg" to page.toString()),
        mapOf("ac" to "list", "t" to typeId.toString(), "pg" to page.toString())
    )

    override fun probeCandidates(base: String) = listOf(
        mapOf("ac" to "list"),
        mapOf("ac" to "list", "t" to "1")
    )

    override fun parseList(text: String): List<VodItem>? = decode<VodList>(text)?.list

    override fun parseCategories(text: String): List<VodClass>? = decode<VodList>(text)?.categories

    override fun parseDetail(text: String): VodItem? = decode<VodDetail>(text)?.firstItem()

    private inline fun <reified T> decode(text: String): T? = try {
        json.decodeFromString<T>(text)
    } catch (e: Exception) {
        null
    }
}

object CmsDialects {
    val defaultJson: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val registry: Map<String, CmsDialect> = mapOf(
        "standard" to StandardCmsDialect(defaultJson),
        "lenient" to LenientCmsDialect(defaultJson)
    )

    /** 按配置取方言；未知或空配置回落到标准方言。 */
    fun byId(id: String?): CmsDialect = registry[id?.lowercase()?.trim()] ?: registry.getValue("standard")

    /** 全部可用方言（源管理表单用）。 */
    val all: List<CmsDialect> get() = registry.values.toList()
}
