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
        // local-first-storage 4.7：上限从 40 提升到 200，支撑长期积累。
        private const val MAX_RECORDS = 200

        /**
         * 按更新时间倒序保留最新的 [limit] 条（4.7）。抽成纯函数便于 JVM 单测
         * 验证「最新在前、最旧被淘汰」，不依赖 Android Context。
         */
        fun retainNewest(list: List<WatchRecord>, limit: Int = MAX_RECORDS): List<WatchRecord> =
            list.sortedByDescending { it.updatedAt }.take(limit)
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
        val sorted = retainNewest(list)
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
        // 4.1：归一化片名作为跨来源聚合键；缺省时按标题兜底。播放地址仅作快照。
        val withTitleKey = if (record.titleKey.isBlank()) {
            record.copy(titleKey = com.libretv.tv.domain.model.normalizeTitle(record.title))
        } else record
        val normalized = if (withTitleKey.isFinished()) withTitleKey.copy(positionMs = 0L) else withTitleKey
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
