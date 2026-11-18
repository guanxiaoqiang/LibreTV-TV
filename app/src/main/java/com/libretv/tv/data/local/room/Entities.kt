package com.libretv.tv.data.local.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 持久化实体（local-first-storage 1.4）。
 *
 * 结构化数据用列存储以便查询排序；HTTP 响应用 JSON 载荷存储（http_cache）。
 * 播放地址不落库——目录与缓存里都没有地址字段（D5）。
 */

@Entity(tableName = "watch_record")
data class WatchRecordEntity(
    @PrimaryKey val key: String,
    val titleKey: String = "",
    val title: String = "",
    val poster: String = "",
    val sourceName: String = "",
    val sourceUrl: String = "",
    val vodId: String = "",
    val episodeName: String = "",
    val rawUrl: String = "",
    val lineIndex: Int = 0,
    val episodeIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
    val typeName: String = ""
)

@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey val titleKey: String,
    val title: String = "",
    val poster: String = "",
    val year: String = "",
    val area: String = "",
    val typeName: String = "",
    val sourceUrl: String = "",
    val vodId: String = "",
    val createdAt: Long = 0L
)

@Entity(
    tableName = "http_cache",
    indices = [
        Index(value = ["kind", "cacheKey"], unique = true),
        Index(value = ["kind", "savedAt"])
    ]
)
data class HttpCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val cacheKey: String,
    val payload: String,
    val savedAt: Long,
    val expiresAt: Long,
    val hardExpiresAt: Long,
    val version: Int,
    val sourceSignature: String,
    val sizeBytes: Int
)

@Entity(tableName = "source_health")
data class SourceHealthEntity(
    @PrimaryKey val sourceUrl: String,
    val state: String = "HEALTHY",
    val failureTier: Int = 0,
    val cooldownUntil: Long = 0L,
    val consecutiveFailures: Int = 0,
    val totalOk: Int = 0,
    val totalFail: Int = 0,
    val lastLatencyMs: Long = 0L,
    val lastError: String? = null,
    val lastCheckedAt: Long = 0L,
    /** 目录采集优先级降级用（content-catalog 5.7）。 */
    val harvestPriority: Int = 0
)

@Entity(tableName = "subscription")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String = "",
    val url: String = "",
    val refreshDays: Int = 0,
    val status: String = "ACTIVE",
    val failureCount: Int = 0,
    val lastSyncAt: Long = 0L
)

@Entity(tableName = "skip_mark")
data class SkipMarkEntity(
    @PrimaryKey val titleKey: String,
    val title: String = "",
    /** SkipMark.segments 序列化后的 JSON。 */
    val segments: String = "[]",
    val updatedAt: Long = 0L
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val keyword: String,
    val createdAt: Long = 0L
)

@Entity(
    tableName = "catalog_title",
    indices = [Index("titleKey"), Index("updatedAt")]
)
data class CatalogTitleEntity(
    @PrimaryKey val titleKey: String,
    val title: String = "",
    /** 逗号分隔的别名。 */
    val aliases: String = "",
    val poster: String = "",
    val year: String = "",
    val area: String = "",
    val typeName: String = "",
    val doubanId: String = "",
    val offerCount: Int = 0,
    val updatedAt: Long = 0L
)

@Entity(
    tableName = "catalog_offer",
    indices = [Index("titleKey"), Index(value = ["sourceUrl", "vodId"], unique = true)]
)
data class CatalogOfferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titleKey: String,
    val sourceUrl: String,
    val sourceName: String = "",
    val vodId: String,
    val quality: String = "",
    val remark: String = "",
    val latencyMs: Long = 0L,
    val lineCount: Int = 0,
    val updatedAt: Long = 0L
)

@Entity(tableName = "catalog_episode", indices = [Index("offerId")])
data class CatalogEpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val offerId: Long,
    val lineIndex: Int = 0,
    val episodeIndex: Int = 0,
    val episodeName: String = "",
    val updatedAt: Long = 0L
)

@Entity(tableName = "catalog_alias", indices = [Index("alias")])
data class CatalogAliasEntity(
    @PrimaryKey val alias: String,
    val titleKey: String
)
