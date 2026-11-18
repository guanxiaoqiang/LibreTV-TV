package com.libretv.tv.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 豆瓣推荐条带里的一部影片。 */
@Serializable
data class DoubanSubject(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("rate") val rate: String = "",
    @SerialName("cover") val cover: String = "",
    @SerialName("url") val url: String = "",
    @SerialName("year") val year: String = ""
)
