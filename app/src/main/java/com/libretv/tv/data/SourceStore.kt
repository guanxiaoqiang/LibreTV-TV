package com.libretv.tv.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent state of the collector list.
 *
 * The bundled list is read-only; everything the viewer changes on the source
 * settings page lives here:
 *
 *  - [customSources]: sources added by hand;
 *  - [removedUrls]: bundled sources the viewer deleted;
 *  - [disabledUrls]: sources the viewer switched off.
 *
 * Disabled sources stay visible in the list (so they can be re-enabled) but
 * are skipped by every fan-out search and category probe.
 */
class SourceStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("libretv_sources", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Sources added by the viewer, in insertion order. */
    var customSources: List<SourceConfig>
        get() {
            val raw = prefs.getString(KEY_CUSTOM, null) ?: return emptyList()
            return try {
                json.decodeFromString<List<SourceConfig>>(raw)
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            prefs.edit()
                .putString(KEY_CUSTOM, json.encodeToString<List<SourceConfig>>(value))
                .apply()
        }

    /** URLs of bundled sources the viewer deleted. */
    var removedUrls: Set<String>
        get() = prefs.getStringSet(KEY_REMOVED, emptySet()).orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_REMOVED, value).apply()
        }

    /** URLs of sources the viewer switched off. */
    var disabledUrls: Set<String>
        get() = prefs.getStringSet(KEY_DISABLED, emptySet()).orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_DISABLED, value).apply()
        }

    /** Drop every custom change and go back to the bundled list. */
    fun reset() {
        prefs.edit()
            .remove(KEY_CUSTOM)
            .remove(KEY_REMOVED)
            .remove(KEY_DISABLED)
            .apply()
    }

    private companion object {
        const val KEY_CUSTOM = "custom_sources"
        const val KEY_REMOVED = "removed_urls"
        const val KEY_DISABLED = "disabled_urls"
    }
}

/** One row of the source settings list. */
data class SourceEntry(
    val config: SourceConfig,
    /** False when the viewer switched this source off. */
    val enabled: Boolean = true,
    /** True for sources the viewer added by hand. */
    val custom: Boolean = false
) {
    val name: String get() = config.name
    val url: String get() = config.url
}

/**
 * Result of testing one source: is it reachable and how fast is it.
 *
 * [latencyMs] is the round-trip time of a real request to the collector, not
 * a ping, because a collector can answer TCP but fail on the API call.
 */
data class SourceHealth(
    val reachable: Boolean,
    val latencyMs: Long = 0L,
    /** Set when the last test failed; shown next to the source name. */
    val error: String? = null
)

/**
 * Merge the bundled list with the viewer's changes.
 *
 * Kept as a pure function so the merge rules can be unit tested without an
 * Android context.
 */
object SourceMerger {

    fun merge(
        bundled: List<SourceConfig>,
        custom: List<SourceConfig>,
        removedUrls: Set<String>,
        disabledUrls: Set<String>
    ): List<SourceEntry> {
        val kept = bundled.filter { it.url.isNotBlank() && it.url !in removedUrls }
        val all = (kept + custom).distinctBy { it.url }
        return all.map { entry ->
            SourceEntry(
                config = entry,
                enabled = entry.url !in disabledUrls,
                custom = custom.any { it.url == entry.url }
            )
        }
    }
}
