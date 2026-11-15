package com.libretv.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Everything needed to start (or resume) one playback session.
 *
 * Keeping the source coordinates on the request is what makes a history
 * entry resumable: the player only ever receives a url, while the home screen
 * needs the title, poster, line and episode to rebuild the full detail page
 * behind the player.
 */
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
    val typeName: String = ""
) {
    /** Stable identity of one watched episode across sessions. */
    val historyKey: String
        get() = if (vodId.isNotBlank()) {
            "$sourceName|$vodId|$lineIndex|$episodeIndex"
        } else {
            "$title|$episodeName|$rawUrl"
        }
}

/** A persisted "continue watching" entry. */
@Serializable
data class WatchRecord(
    val key: String = "",
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

/**
 * Tiny local history store.
 *
 * SharedPreferences plus a JSON array is enough here: the list is capped,
 * there are no queries, and a StateFlow lets the home screen refresh the
 * moment the player reports new progress.
 */
class WatchHistoryStore(context: Context) {

    companion object {
        private const val PREFS = "libretv_watch_history"
        private const val KEY = "records"
        private const val MAX_RECORDS = 40
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _records = MutableStateFlow(read())
    val records: StateFlow<List<WatchRecord>> = _records.asStateFlow()

    private fun read(): List<WatchRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<WatchRecord>>(raw).sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun commit(list: List<WatchRecord>) {
        val sorted = list.sortedByDescending { it.updatedAt }.take(MAX_RECORDS)
        prefs.edit().putString(KEY, json.encodeToString(sorted)).apply()
        _records.value = sorted
    }

    /**
     * Insert or update one episode. A title that has been watched to the end
     * restarts from zero the next time it is opened, so the history never
     * traps the viewer at the last second of a film.
     */
    fun upsert(record: WatchRecord) {
        if (record.key.isBlank()) return
        val normalized = if (record.isFinished()) record.copy(positionMs = 0L) else record
        val kept = _records.value.filterNot { it.key == normalized.key }
        commit(listOf(normalized) + kept)
    }

    fun delete(key: String) {
        commit(_records.value.filterNot { it.key == key })
    }

    fun clear() {
        commit(emptyList())
    }
}
