package com.libretv.tv.data

import android.content.Context
import com.libretv.tv.core.LogLevel
import com.libretv.tv.data.local.StorageKeys
import com.libretv.tv.data.local.migrateIfNeeded

/**
 * User-facing switches of the app, persisted in SharedPreferences.
 *
 * The settings screen writes here; the rest of the app only reads the values
 * that belong to it (the player needs the ad filter flag, the poster cards
 * need the badge flag).
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(StorageKeys.SETTINGS, Context.MODE_PRIVATE)

    init {
        migrateIfNeeded(prefs)
    }

    /**
     * 日志级别。
     *
     * 默认只留 INFO 及以上：网络请求的 DEBUG 日志量很大，留给需要排查问题时
     * 再打开。
     */
    var logLevel: LogLevel
        get() = LogLevel.parse(prefs.getString(KEY_LOG_LEVEL, null))
        set(value) = prefs.edit().putString(KEY_LOG_LEVEL, value.name).apply()

    /** 调试日志总开关（设置页上的一个开关）。 */
    var debugLogEnabled: Boolean
        get() = logLevel == LogLevel.DEBUG
        set(value) {
            logLevel = if (value) LogLevel.DEBUG else LogLevel.INFO
        }

    /** Strip ad segments out of HLS playlists (LibreTV's original rule set). */
    var adFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_FILTER, true)
        set(value) = prefs.edit().putBoolean(KEY_AD_FILTER, value).apply()

    /**
     * Show the metadata tags (year / source count / best quality / quickest
     * latency) on top of the poster artwork. Turning this off leaves a clean
     * poster with only the title bar, which reads better from a distance.
     *
     * Kept for backwards compatibility: it is the master switch that hides
     * every tag at once. The per-tag switches below decide which of the tags
     * survive when the master switch is on.
     */
    var posterBadgesEnabled: Boolean
        get() = prefs.getBoolean(KEY_POSTER_BADGES, true)
        set(value) = prefs.edit().putBoolean(KEY_POSTER_BADGES, value).apply()

    /** Release year on the poster. */
    var posterBadgeYear: Boolean
        get() = prefs.getBoolean(KEY_BADGE_YEAR, posterBadgesEnabled)
        set(value) = prefs.edit().putBoolean(KEY_BADGE_YEAR, value).apply()

    /** Number of playable sources on the poster. */
    var posterBadgeSources: Boolean
        get() = prefs.getBoolean(KEY_BADGE_SOURCES, posterBadgesEnabled)
        set(value) = prefs.edit().putBoolean(KEY_BADGE_SOURCES, value).apply()

    /** Best available quality on the poster. */
    var posterBadgeQuality: Boolean
        get() = prefs.getBoolean(KEY_BADGE_QUALITY, posterBadgesEnabled)
        set(value) = prefs.edit().putBoolean(KEY_BADGE_QUALITY, value).apply()

    /** Quickest response time on the poster. */
    var posterBadgeLatency: Boolean
        get() = prefs.getBoolean(KEY_BADGE_LATENCY, posterBadgesEnabled)
        set(value) = prefs.edit().putBoolean(KEY_BADGE_LATENCY, value).apply()

    /** Douban discovery tags on the poster. */
    var posterBadgeDouban: Boolean
        get() = prefs.getBoolean(KEY_BADGE_DOUBAN, posterBadgesEnabled)
        set(value) = prefs.edit().putBoolean(KEY_BADGE_DOUBAN, value).apply()

    /**
     * The whole visibility set of the poster tags.
     *
     * The legacy master switch still acts as the default for every tag that
     * has never been written, so a viewer who turned all tags off before this
     * version keeps the clean poster.
     */
    var badgeVisibility: BadgeVisibility
        get() = BadgeVisibility(
            year = posterBadgeYear,
            sources = posterBadgeSources,
            quality = posterBadgeQuality,
            latency = posterBadgeLatency,
            douban = posterBadgeDouban
        )
        set(value) {
            posterBadgeYear = value.year
            posterBadgeSources = value.sources
            posterBadgeQuality = value.quality
            posterBadgeLatency = value.latency
            posterBadgeDouban = value.douban
        }

    /**
     * 跳过标记总开关。
     *
     * 关掉之后所有影片都不再自动跳过，但已标记的区间还留着——观众随时能
     * 再打开。
     */
    var skipMarksEnabled: Boolean
        get() = prefs.getBoolean(KEY_SKIP_MARKS, true)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_MARKS, value).apply()

    /**
     * 后台采集总开关（7.2）。关闭后所有目录/搜索补采都不再触发请求；
     * 默认开启，但仅按 [harvestIntensity] 决定的时机执行。
     */
    var harvestEnabled: Boolean
        get() = prefs.getBoolean(KEY_HARVEST_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HARVEST_ENABLED, value).apply()

    /** 采集强度：默认仅在应用运行时（RUNNING_ONLY）。 */
    var harvestIntensity: HarvestIntensity
        get() = HarvestIntensity.fromInt(prefs.getInt(KEY_HARVEST_INTENSITY, 0))
        set(value) = prefs.edit().putInt(KEY_HARVEST_INTENSITY, value.code).apply()

    private companion object {
        val KEY_AD_FILTER = StorageKeys.Settings.AD_FILTER
        val KEY_POSTER_BADGES = StorageKeys.Settings.POSTER_BADGES
        val KEY_BADGE_YEAR = StorageKeys.Settings.BADGE_YEAR
        val KEY_BADGE_SOURCES = StorageKeys.Settings.BADGE_SOURCES
        val KEY_BADGE_QUALITY = StorageKeys.Settings.BADGE_QUALITY
        val KEY_BADGE_LATENCY = StorageKeys.Settings.BADGE_LATENCY
        val KEY_BADGE_DOUBAN = StorageKeys.Settings.BADGE_DOUBAN
        val KEY_LOG_LEVEL = StorageKeys.Settings.LOG_LEVEL
        val KEY_SKIP_MARKS = StorageKeys.Settings.SKIP_MARKS
        val KEY_HARVEST_ENABLED = StorageKeys.Settings.HARVEST_ENABLED
        val KEY_HARVEST_INTENSITY = StorageKeys.Settings.HARVEST_INTENSITY
    }
}

/** 后台采集强度（7.2）：决定触发时机与频率。 */
enum class HarvestIntensity(val code: Int, val label: String) {
    RUNNING_ONLY(0, "仅应用运行时"),
    PERIODIC(1, "定期执行"),
    AGGRESSIVE(2, "积极采集");

    companion object {
        fun fromInt(code: Int): HarvestIntensity = entries.firstOrNull { it.code == code } ?: RUNNING_ONLY
    }
}
