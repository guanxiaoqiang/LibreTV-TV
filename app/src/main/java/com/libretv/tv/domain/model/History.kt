package com.libretv.tv.domain.model

import kotlinx.serialization.Serializable

/** A persisted "continue watching" entry. */
@Serializable
data class WatchRecord(
    val key: String = "",
    /** 归一化片名（local-first-storage 4.1）：跨来源聚合的键。 */
    val titleKey: String = "",
    val title: String = "",
    val poster: String = "",
    val sourceName: String = "",
    val sourceUrl: String = "",
    val vodId: String = "",
    val episodeName: String = "",
    val rawUrl: String = "",
    val lineIndex: Int = 0,
    val episodeIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
    val typeName: String = ""
) {
    /** 0-100, used for the progress bar drawn on the history card. */
    fun progressPercent(): Int =
        if (durationMs > 0L) (positionMs * 100 / durationMs).toInt().coerceIn(0, 100) else 0

    fun isFinished(): Boolean =
        durationMs > 0L && positionMs * 100 >= durationMs * 95
}
