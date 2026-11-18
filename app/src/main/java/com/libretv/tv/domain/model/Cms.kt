package com.libretv.tv.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Apple CMS 的响应模型。
 *
 * 这些结构直接对应采集接口的字段名，所以带 `@SerialName`；它们是
 * 「远端长什么样」的描述，不是业务概念——业务侧用 [TitleRef] / [TitleGroup]。
 */
@Serializable
data class VodList(
    @SerialName("list") val list: List<VodItem>? = null,
    @SerialName("total") val total: Int = 0,
    @SerialName("page") val page: Int = 1,
    @SerialName("pagecount") val pagecount: Int = 0,
    @SerialName("limit") val limit: Int = 0,
    // Apple CMS returns the category tree in the `class` field of a
    // `ac=list&t=<parent>` response. It is optional because plain searches
    // and category listings do not carry it.
    @SerialName("class") val categories: List<VodClass>? = null
)

/** One node of an Apple CMS category tree (type_id / type_pid / type_name). */
@Serializable
data class VodClass(
    @SerialName("type_id") val typeId: Int = 0,
    @SerialName("type_pid") val parentId: Int = 0,
    @SerialName("type_name") val typeName: String = ""
)

@Serializable
data class VodItem(
    @SerialName("vod_id") val vodId: String = "",
    @SerialName("vod_name") val vodName: String = "",
    @SerialName("vod_pic") val vodPic: String = "",
    @SerialName("vod_year") val vodYear: String = "",
    @SerialName("vod_area") val vodArea: String = "",
    @SerialName("type_name") val typeName: String = "",
    @SerialName("vod_remarks") val vodRemarks: String = "",
    @SerialName("vod_content") val vodContent: String = "",
    @SerialName("vod_actor") val vodActor: String = "",
    @SerialName("vod_director") val vodDirector: String = "",
    @SerialName("vod_play_url") val vodPlayUrl: String = "",
    // Apple CMS names the play lines here, e.g. "4K线路$$$蓝光线路". It is one
    // of the few honest signals about the quality a source actually serves.
    @SerialName("vod_play_from") val vodPlayFrom: String = ""
)

@Serializable
data class VodDetail(
    @SerialName("vod") val vod: List<VodItem>? = null,
    /**
     * 很多 maccms 变种在 `ac=detail` 时把条目数组放在 `list` 而不是 `vod`。
     * 只认 `vod` 的代价是：这类源的详情全部解析失败，表现为「所有影片
     * 都没有播放源」——而它们的 `vod_play_url` 其实一直在响应里。
     */
    @SerialName("list") val list: List<VodItem>? = null,
    /** 还有一种写法是整体再裹一层 `data`。 */
    @SerialName("data") val data: VodDetail? = null
) {
    /** 三种包装都认：谁先有值用谁。 */
    fun firstItem(): VodItem? =
        vod?.firstOrNull() ?: list?.firstOrNull() ?: data?.firstItem()
}

/**
 * 一次检索命中的结果：某源上的某部片子。
 *
 * 字段名沿用旧实现，避免大面积改名带来的回归风险。
 */
data class SearchResult(
    val sourceName: String,
    val item: VodItem,
    /** Base API of the source, so a picked offer can be opened without a name lookup. */
    val sourceUrl: String = "",
    /** Round-trip time of that source's search request, in milliseconds. */
    val latencyMs: Long = 0L
)
