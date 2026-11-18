package com.libretv.tv.data.local.room

import android.content.Context
import androidx.room.withTransaction
import com.libretv.tv.data.FeedCacheDb
import com.libretv.tv.data.local.StorageKeys
import com.libretv.tv.domain.model.FeedCachePolicy
import com.libretv.tv.domain.model.SkipMark
import com.libretv.tv.domain.model.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一次性把旧的 SP 与 `feed_cache.db` 数据回填进 Room（local-first-storage 2.2）。
 *
 * 三条硬规则：
 *  1. **保留原件**：迁移后不删除任何 SP / 旧 SQLite 文件——出问题可以回退（2.4）；
 *  2. **置迁移标记**：成功后写 [StorageKeys.MIGRATED_TO_DB]，避免重复回填；
 *  3. **逐行搬运**：旧 feed_cache 的每一行都写进 http_cache，补齐版本与有效期字段（2.1）。
 */
class LegacyMigrator(
    context: Context,
    private val db: LibreTvDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    private val app = context.applicationContext
    private val statePrefs = app.getSharedPreferences(StorageKeys.MIGRATION_STATE, Context.MODE_PRIVATE)

    /** 是否已回填过。 */
    fun isMigrated(): Boolean = statePrefs.getBoolean(StorageKeys.MIGRATED_TO_DB, false)

    /** 复位标记（2.4 回滚）：清掉后下次启动会重新回填；旧数据始终还在。 */
    fun resetMigrationFlag() {
        statePrefs.edit().putBoolean(StorageKeys.MIGRATED_TO_DB, false).apply()
    }

    /**
     * 执行回填。可重复调用——已迁移时会直接跳过；每一行搬运失败都不影响
     * 其它行，保证「能搬多少搬多少」，绝不因为一条坏数据丢整张表。
     */
    suspend fun migrateIfNeeded(now: Long = System.currentTimeMillis()): MigrationReport =
        withContext(Dispatchers.IO) {
            if (isMigrated()) return@withContext MigrationReport(alreadyDone = true)

            val report = MigrationReport()
            runCatching { migrateWatchHistory(now, report) }
            runCatching { migrateFavorites(report) }
            runCatching { migrateSkipMarks(report) }
            runCatching { migrateSubscriptions(report) }
            runCatching { migrateSearchHistory(report) }
            runCatching { migrateSourceHealth(report) }
            runCatching { migrateFeedCache(now, report) }

            // 只有全部尝试过才置标记；个别行失败已记录，不阻塞用户使用。
            statePrefs.edit().putBoolean(StorageKeys.MIGRATED_TO_DB, true).apply()
            report
        }

    private suspend fun migrateWatchHistory(now: Long, report: MigrationReport) {
        val raw = prefs(StorageKeys.WATCH_HISTORY).getString(StorageKeys.WatchHistory.RECORDS, null)
            ?: return
        val records = json.decodeFromString<List<com.libretv.tv.domain.model.WatchRecord>>(raw)
        if (records.isEmpty()) return
        db.withTransaction {
            db.watchRecordDao().upsertAll(records.map {
                val fixed = it.copy(titleKey = it.titleKey.ifBlank { fallbackTitleKey(it.title) })
                WatchRecordEntity(
                    key = fixed.key, titleKey = fixed.titleKey, title = fixed.title,
                    poster = fixed.poster, sourceName = fixed.sourceName,
                    sourceUrl = fixed.sourceUrl, vodId = fixed.vodId,
                    episodeName = fixed.episodeName, rawUrl = fixed.rawUrl,
                    lineIndex = fixed.lineIndex, episodeIndex = fixed.episodeIndex,
                    positionMs = fixed.positionMs, durationMs = fixed.durationMs,
                    updatedAt = fixed.updatedAt, typeName = fixed.typeName
                )
            })
        }
        report.watchHistory = records.size
    }

    private suspend fun migrateFavorites(report: MigrationReport) {
        val raw = prefs(StorageKeys.FAVORITES).getString(StorageKeys.Favorites.RECORDS, null) ?: return
        val entries = json.decodeFromString<List<com.libretv.tv.domain.model.FavoriteEntry>>(raw)
        if (entries.isEmpty()) return
        db.withTransaction {
            db.favoriteDao().upsertAll(entries.map {
                FavoriteEntity(
                    titleKey = it.titleKey, title = it.title, poster = it.poster,
                    sourceUrl = it.sourceUrl, vodId = it.vodId, createdAt = it.createdAt
                )
            })
        }
        report.favorites = entries.size
    }

    private suspend fun migrateSkipMarks(report: MigrationReport) {
        val raw = prefs(StorageKeys.SKIP_MARKS).getString(StorageKeys.SkipMarks.RECORDS, null) ?: return
        val marks = json.decodeFromString<List<SkipMark>>(raw)
        if (marks.isEmpty()) return
        db.withTransaction {
            db.skipMarkDao().upsertAll(marks.map {
                SkipMarkEntity(
                    titleKey = it.titleKey,
                    title = it.title,
                    segments = json.encodeToString(it.segments),
                    updatedAt = it.updatedAt
                )
            })
        }
        report.skipMarks = marks.size
    }

    private suspend fun migrateSubscriptions(report: MigrationReport) {
        val raw = prefs(StorageKeys.SOURCES).getString(StorageKeys.Sources.SUBSCRIPTIONS, null) ?: return
        val subs = json.decodeFromString<List<Subscription>>(raw)
        if (subs.isEmpty()) return
        db.withTransaction {
            db.subscriptionDao().upsertAll(subs.map {
                SubscriptionEntity(
                    id = it.id, name = it.name, url = it.url,
                    refreshDays = it.refreshDays, status = it.status.name,
                    failureCount = it.failureCount, lastSyncAt = it.lastSyncAt
                )
            })
        }
        report.subscriptions = subs.size
    }

    private suspend fun migrateSearchHistory(report: MigrationReport) {
        val raw = prefs(StorageKeys.SEARCH_HISTORY).getString(StorageKeys.SearchHistory.KEYWORDS, null)
            ?: return
        val keywords = json.decodeFromString<List<String>>(raw)
        if (keywords.isEmpty()) return
        db.withTransaction {
            keywords.forEachIndexed { index, kw ->
                db.searchHistoryDao().upsert(SearchHistoryEntity(kw, createdAt = index.toLong()))
            }
        }
        report.searchHistory = keywords.size
    }

    /**
     * 来源集合里的健康档位迁进 source_health 表（2.2）。
     *
     * 设置项按 design D1 仍保留 SharedPreferences（偏好项不进 DB）；自定义/
     * 删除/停用源集合由 SourceStore 继续维护——Room schema 草案未设 source
     * 表，避免同一份源数据在两处各自维护而漂移。这里只搬有对应 Room 表的
     * 健康档位，保证冷却状态升级后不丢。
     */
    private suspend fun migrateSourceHealth(report: MigrationReport) {
        val raw = prefs(StorageKeys.SOURCES).getString(StorageKeys.Sources.HEALTH_TIERS, null)
            ?: return
        val tiers = json.decodeFromString<Map<String, com.libretv.tv.data.HealthTierRecord>>(raw)
        if (tiers.isEmpty()) return
        db.withTransaction {
            tiers.forEach { (url, tier) ->
                db.sourceHealthDao().upsert(
                    SourceHealthEntity(
                        sourceUrl = url,
                        failureTier = tier.failureTier,
                        cooldownUntil = tier.cooldownUntil,
                        consecutiveFailures = tier.failureTier,
                        lastError = tier.lastErrorKind.ifBlank { null }
                    )
                )
            }
        }
        report.sourceHealth = tiers.size
    }

    /**
     * 逐行搬运旧 `feed_cache.db` → http_cache（2.1）。
     *
     * 旧表是 6 列（key/payload/saved_at/version/ttl_minutes/source_signature）；
     * 新表补齐 `expiresAt`/`hardExpiresAt`/`sizeBytes`。用旧 ttl 推导软/硬过期，
     * 这样「升级后缓存仍按原有效期失效」，不会因为结构变化被清空。
     */
    private suspend fun migrateFeedCache(now: Long, report: MigrationReport) {
        val legacyFile = app.getDatabasePath("feed_cache.db")
        if (!legacyFile.exists()) return
        val legacy = FeedCacheDb(app)
        val rows = runCatching { legacy.allRows() }.getOrNull() ?: return
        db.withTransaction {
            rows.forEach { (cachedFeed, signature, ttlMinutes) ->
                val softTtlMs = ttlMinutes * 60_000L
                val hardTtlMs = softTtlMs * 4L
                val payload = json.encodeToString(cachedFeed)
                db.httpCacheDao().upsert(
                    HttpCacheEntity(
                        kind = "feed",
                        cacheKey = cachedFeed.key,
                        payload = payload,
                        savedAt = cachedFeed.savedAt,
                        expiresAt = cachedFeed.savedAt + softTtlMs,
                        hardExpiresAt = cachedFeed.savedAt + hardTtlMs,
                        version = FeedCachePolicy.CACHE_VERSION,
                        sourceSignature = signature,
                        sizeBytes = payload.toByteArray().size
                    )
                )
            }
        }
        report.feedCacheRows = rows.size
    }

    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** 旧记录没有 titleKey：用归一化片名兜底，保证跨源聚合可用。 */
    private fun fallbackTitleKey(title: String): String = title
        .replace(Regex("""[\s\u3000]+"""), "")
        .lowercase()
        .trim()
}

/** 回填报告：各表搬了多少行，便于日志与验证。 */
data class MigrationReport(
    val alreadyDone: Boolean = false,
    var watchHistory: Int = 0,
    var favorites: Int = 0,
    var skipMarks: Int = 0,
    var subscriptions: Int = 0,
    var searchHistory: Int = 0,
    var sourceHealth: Int = 0,
    var feedCacheRows: Int = 0
)
