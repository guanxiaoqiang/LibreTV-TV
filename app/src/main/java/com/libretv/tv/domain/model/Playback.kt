package com.libretv.tv.domain.model

import com.libretv.tv.data.VodItem
import kotlinx.serialization.Serializable

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

/** 播放页的完整载荷：源元信息 + 解析出的线路。 */
data class DetailBundle(
    val sourceName: String,
    val item: VodItem,
    val lines: List<PlayLine>
)

/**
 * 一次起播请求。
 *
 * 可序列化：播放页的入口参数要经导航栈（savedStateHandle）传递，
 * 进程被回收后仍能还原出同一集、同一进度。
 */
@Serializable
data class PlayRequest(
    val title: String,
    val poster: String = "",
    val episodeName: String,
    val rawUrl: String,
    val sourceName: String = "",
    val sourceUrl: String = "",
    val vodId: String = "",
    val lineIndex: Int = 0,
    val episodeIndex: Int = 0,
    val startPositionMs: Long = 0L,
    val typeName: String = "",
    /**
     * 换源续播的进度比例（0..1）。
     *
     * 不同源的同一集时长可能不一样（片头广告长度不同），直接把毫秒数搬过去
     * 会跳到错误的位置，所以只搬**比例**，起播后按新时长换算。
     */
    val resumeRatio: Float = 0f
) {
    /** Stable identity of one watched episode across sessions. */
    val historyKey: String
        get() = WatchKeys.episodeKey(sourceName, vodId, lineIndex, episodeIndex, title, episodeName, rawUrl)

    /** 按新时长把比例换算回毫秒。 */
    fun resumePositionMs(newDurationMs: Long): Long =
        if (resumeRatio <= 0f || newDurationMs <= 0L) 0L
        else (resumeRatio * newDurationMs).toLong().coerceIn(0L, newDurationMs)
}
