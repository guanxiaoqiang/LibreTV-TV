package com.libretv.tv.data.local

import android.content.SharedPreferences

/**
 * 本地存储键名的唯一出处（design-detail.md §D.5）。
 *
 * SP 仍保留给设置与少量偏好项；数据库类数据（feed_cache）在各自的
 * SQLiteOpenHelper 里。所有 SP 文件名与文件内的键都集中在这里，
 * 其他任何文件里都不允许再出现字面量——「键名改了但漏了一处」
 * 这类事故靠 review 抓不住，靠约定抓得住。
 */
object StorageKeys {

    /**
     * 本地存储 schema 版本。
     *
     * 每个 SP 文件里记录自己已迁移到的版本（[SCHEMA_VERSION_KEY]）；
     * 缺失或小于 [SCHEMA_VERSION] 时，Store 初始化时执行一次迁移。
     */
    const val SCHEMA_VERSION = 2

    /** 各 SP 文件里记录 schema 版本的键。 */
    const val SCHEMA_VERSION_KEY = "schema_version"

    // ---------- SP 文件名 ----------

    /** 采集源清单的用户改动（自定义 / 删除 / 禁用 / 冷却档位）。 */
    const val SOURCES = "libretv_sources"

    /** 用户设置（广告过滤、海报角标、日志级别）。 */
    const val SETTINGS = "libretv_settings"

    /** 观看历史（JSON 数组，上限 200 条）。 */
    const val WATCH_HISTORY = "libretv_watch_history"

    /** 搜索历史（JSON 数组，上限 12 条）。 */
    const val SEARCH_HISTORY = "libretv_search_history"

    /** 跳过标记（JSON 数组，键为归一化片名）。 */
    const val SKIP_MARKS = "libretv_skip_marks"

    /** 收藏（JSON 数组，键为归一化片名）。 */
    const val FAVORITES = "libretv_favorites"

    /**
     * 一次性回填标记所在的文件（local-first-storage 2.2 / 2.4）。
     *
     * 迁移完成置 true；复位（回滚）时清掉它即可回到旧读写路径。SP 原件
     * 全程保留，不删除——出问题可以回退，旧数据一份都不能丢。
     */
    const val MIGRATION_STATE = "libretv_migration"

    /** 是否已把 SP + 旧 feed_cache.db 回填进 Room。 */
    const val MIGRATED_TO_DB = "migrated_to_db"

    // ---------- libretv_sources 内部键 ----------

    object Sources {
        /** 用户手工添加的采集源。 */
        const val CUSTOM = "custom_sources"

        /** 用户删除的内置源 URL 集合。 */
        const val REMOVED = "removed_urls"

        /** 用户停用的源 URL 集合。 */
        const val DISABLED = "disabled_urls"

        /** 阶梯冷却档位（按源地址）。 */
        const val HEALTH_TIERS = "health_tiers"

        /** 远程订阅列表（JSON 数组）。 */
        const val SUBSCRIPTIONS = "subscriptions"
    }

    // ---------- libretv_settings 内部键 ----------

    object Settings {
        const val AD_FILTER = "ad_filter_enabled"
        const val POSTER_BADGES = "poster_badges_enabled"
        const val BADGE_YEAR = "poster_badge_year"
        const val BADGE_SOURCES = "poster_badge_sources"
        const val BADGE_QUALITY = "poster_badge_quality"
        const val BADGE_LATENCY = "poster_badge_latency"
        const val BADGE_DOUBAN = "poster_badge_douban"
        const val LOG_LEVEL = "log_level"

        /** 跳过标记总开关。 */
        const val SKIP_MARKS = "skip_marks_enabled"

        /** 后台采集总开关（默认开）。 */
        const val HARVEST_ENABLED = "harvest_enabled"

        /** 采集强度：0=仅应用运行时、1=定期、2=激进（默认 0）。 */
        const val HARVEST_INTENSITY = "harvest_intensity"
    }

    // ---------- libretv_watch_history / libretv_search_history 内部键 ----------

    object WatchHistory {
        const val RECORDS = "records"
    }

    object SearchHistory {
        const val KEYWORDS = "keywords"
    }

    // ---------- libretv_skip_marks / libretv_favorites 内部键 ----------

    object SkipMarks {
        /** 标记列表（JSON 数组）。 */
        const val RECORDS = "records"
    }

    object Favorites {
        /** 收藏列表（JSON 数组）。 */
        const val RECORDS = "records"
    }
}

/** 读取一个 SP 已迁移到的 schema 版本；没有记录视为 0（需要迁移）。 */
fun readSchemaVersion(prefs: SharedPreferences): Int =
    prefs.getInt(StorageKeys.SCHEMA_VERSION_KEY, 0)

/**
 * 迁移入口：版本缺失或小于 [StorageKeys.SCHEMA_VERSION] 时执行一次 [migrate]，
 * 然后把版本号推进到当前值。
 *
 * 各 Store 在初始化时调用。当前各 Store 的数据结构都很简单，迁移主要是
 * 补默认值；未来把历史/搜索迁进 Room 时，这里就是搬运入口。
 */
fun migrateIfNeeded(prefs: SharedPreferences, migrate: () -> Unit = {}) {
    if (readSchemaVersion(prefs) >= StorageKeys.SCHEMA_VERSION) return
    runCatching { migrate() }
    prefs.edit().putInt(StorageKeys.SCHEMA_VERSION_KEY, StorageKeys.SCHEMA_VERSION).apply()
}
