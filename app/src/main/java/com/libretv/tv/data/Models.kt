package com.libretv.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SourceConfig(
    val name: String = "",
    val url: String = "",
    val detail: String = ""
)

@Serializable
data class BundledSources(
    val sources: List<SourceConfig> = emptyList(),
    val meta: Meta = Meta()
) {
    @Serializable
    data class Meta(
        val name: String = "",
        val version: String = "",
        val source_count: Int = 0,
        val probed_at: String = "",
        val note: String = ""
    )
}

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
    @SerialName("vod") val vod: List<VodItem>? = null
)

/** A playable episode parsed out of an Apple CMS vod_play_url field. */
data class Episode(
    val index: Int,
    val name: String,
    val url: String
)

/** One playback line (线路) of a title. */
data class PlayLine(
    val index: Int,
    val name: String,
    val episodes: List<Episode>
)

data class SearchResult(
    val sourceName: String,
    val item: VodItem,
    /** Base API of the source, so a picked offer can be opened without a name lookup. */
    val sourceUrl: String = "",
    /** Round-trip time of that source's search request, in milliseconds. */
    val latencyMs: Long = 0L
)

data class DetailBundle(
    val sourceName: String,
    val item: VodItem,
    val lines: List<PlayLine>
)

/** The source whose category tree drives the home screen, plus that tree. */
data class CategoryCatalog(
    val source: SourceConfig,
    val classes: List<VodClass>
)
