package com.libretv.tv.data

import android.content.Context
import com.libretv.tv.data.local.StorageKeys
import com.libretv.tv.data.local.migrateIfNeeded
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SEARCH_HISTORY_LIMIT = 12

/**
 * Put [keyword] first, drop an older duplicate (case-insensitively) and cap the
 * list. Kept as a pure function so the ordering rules can be unit tested
 * without an Android context.
 */
fun mergeSearchHistory(
    existing: List<String>,
    keyword: String,
    limit: Int = SEARCH_HISTORY_LIMIT
): List<String> {
    val kw = keyword.trim()
    if (kw.isEmpty()) return existing
    val kept = existing.filterNot { it.equals(kw, ignoreCase = true) }
    return (listOf(kw) + kept).take(limit.coerceAtLeast(1))
}

/**
 * Recent search keywords, newest first. SharedPreferences + a JSON array is
 * plenty for a dozen strings; a StateFlow lets the search screen update the
 * moment a search is submitted or a record is removed.
 */
class SearchHistoryStore(context: Context) {

    companion object {
        private val PREFS = StorageKeys.SEARCH_HISTORY
        private val KEY = StorageKeys.SearchHistory.KEYWORDS
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateIfNeeded(prefs)
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _keywords = MutableStateFlow(read())
    val keywords: StateFlow<List<String>> = _keywords.asStateFlow()

    private fun read(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun commit(list: List<String>) {
        val capped = list.take(SEARCH_HISTORY_LIMIT)
        prefs.edit().putString(KEY, json.encodeToString(capped)).apply()
        _keywords.value = capped
    }

    fun add(keyword: String) {
        commit(mergeSearchHistory(_keywords.value, keyword))
    }

    fun delete(keyword: String) {
        commit(_keywords.value.filterNot { it == keyword })
    }

    fun clear() {
        commit(emptyList())
    }
}
