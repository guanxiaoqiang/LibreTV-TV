package com.libretv.tv.data

import android.content.Context

/**
 * User-facing switches of the app, persisted in SharedPreferences.
 *
 * The settings screen writes here; the rest of the app only reads the values
 * that belong to it (the player needs the ad filter flag, the poster cards
 * need the badge flag).
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("libretv_settings", Context.MODE_PRIVATE)

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

    private companion object {
        const val KEY_AD_FILTER = "ad_filter_enabled"
        const val KEY_POSTER_BADGES = "poster_badges_enabled"
        const val KEY_BADGE_YEAR = "poster_badge_year"
        const val KEY_BADGE_SOURCES = "poster_badge_sources"
        const val KEY_BADGE_QUALITY = "poster_badge_quality"
        const val KEY_BADGE_LATENCY = "poster_badge_latency"
        const val KEY_BADGE_DOUBAN = "poster_badge_douban"
    }
}
