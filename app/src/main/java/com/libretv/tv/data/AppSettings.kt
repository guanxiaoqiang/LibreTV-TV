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
     */
    var posterBadgesEnabled: Boolean
        get() = prefs.getBoolean(KEY_POSTER_BADGES, true)
        set(value) = prefs.edit().putBoolean(KEY_POSTER_BADGES, value).apply()

    private companion object {
        const val KEY_AD_FILTER = "ad_filter_enabled"
        const val KEY_POSTER_BADGES = "poster_badges_enabled"
    }
}
