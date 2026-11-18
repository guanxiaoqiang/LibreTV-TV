package com.libretv.tv.data

import android.content.Context
import com.libretv.tv.data.local.StorageKeys
import com.libretv.tv.data.local.migrateIfNeeded
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 起播请求已迁至 `domain/model/Playback.kt`（可序列化，供导航栈传递）；
// `data/Models.kt` 里的 typealias 保证旧引用继续可用。

// 观看记录已迁至 `domain/model/History.kt`（`data/Models.kt` 里的 typealias 保持旧引用可用）。

/**
 * Tiny local history store.
 *
 * SharedPreferences plus a JSON array is enough here: the list is capped,
 * there are no queries, and a StateFlow lets the home screen refresh the
 * moment the player reports new progress.
 */
class WatchHistoryStore(context: Context) : com.libretv.tv.domain.gateway.HistoryGateway {

    companion object {
        private val PREFS = StorageKeys.WATCH_HISTORY
        private val KEY = StorageKeys.WatchHistory.RECORDS
        private const val MAX_RECORDS = 40
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        // 老安装可能存了超上限的记录：迁移时按更新时间截断到上限。
        migrateIfNeeded(prefs) {
            val capped = read().take(MAX_RECORDS)
            prefs.edit().putString(KEY, json.encodeToString(capped)).apply()
        }
    }

    private val _records = MutableStateFlow(read())
    override val records: StateFlow<List<WatchRecord>> = _records.asStateFlow()

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
    override fun upsert(record: WatchRecord) {
        if (record.key.isBlank()) return
        val normalized = if (record.isFinished()) record.copy(positionMs = 0L) else record
        val kept = _records.value.filterNot { it.key == normalized.key }
        commit(listOf(normalized) + kept)
    }

    override fun delete(key: String) {
        commit(_records.value.filterNot { it.key == key })
    }

    override fun clear() {
        commit(emptyList())
    }
}
