package com.libretv.tv.data

import android.content.Context
import com.libretv.tv.data.local.StorageKeys
import com.libretv.tv.data.local.migrateIfNeeded
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
        .getSharedPreferences(StorageKeys.SOURCES, Context.MODE_PRIVATE)

    init {
        // 冷却档位是 v1.1 才有的：老安装里没有这个键，读出来就是空 map，
        // 迁移本身不需要搬数据，只是把版本号补上。
        migrateIfNeeded(prefs)
    }

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
            .remove(KEY_HEALTH)
            .apply()
    }

    /**
     * 阶梯冷却状态的持久化（按源地址）。
     *
     * 必须落盘：重启后 tier 归零的话，阶梯冷却就失效了——坏源每次启动
     * 都要重新踩一遍 30 分钟、24 小时的坑。
     */
    var healthTiers: Map<String, HealthTierRecord>
        get() {
            val raw = prefs.getString(KEY_HEALTH, null) ?: return emptyMap()
            return try {
                json.decodeFromString<Map<String, HealthTierRecord>>(raw)
            } catch (e: Exception) {
                emptyMap()
            }
        }
        set(value) {
            prefs.edit()
                .putString(KEY_HEALTH, json.encodeToString<Map<String, HealthTierRecord>>(value))
                .apply()
        }

    private companion object {
        val KEY_CUSTOM = StorageKeys.Sources.CUSTOM
        val KEY_REMOVED = StorageKeys.Sources.REMOVED
        val KEY_DISABLED = StorageKeys.Sources.DISABLED
        val KEY_HEALTH = StorageKeys.Sources.HEALTH_TIERS
    }
}

/** 一个源跨进程的冷却档位记录（只存参与决策的最小集）。 */
@kotlinx.serialization.Serializable
data class HealthTierRecord(
    val failureTier: Int = 0,
    val cooldownUntil: Long = 0L,
    /** [SourceHealth] 结构化的错误种类名，半开探测失败时沿用。 */
    val lastErrorKind: String = ""
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
