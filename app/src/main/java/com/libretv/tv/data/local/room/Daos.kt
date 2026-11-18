package com.libretv.tv.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 观看记录 DAO。上限与排序在 SQL 层完成（4.7）。 */
@Dao
interface WatchRecordDao {
    @Query("SELECT * FROM watch_record ORDER BY updatedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<WatchRecordEntity>>

    @Query("SELECT * FROM watch_record ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun all(limit: Int): List<WatchRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WatchRecordEntity>)

    @Query("DELETE FROM watch_record WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM watch_record")
    suspend fun clear()

    @Query("DELETE FROM watch_record WHERE `key` NOT IN (:keep) ")
    suspend fun deleteExcept(keep: List<String>)

    @Query("SELECT COUNT(*) FROM watch_record")
    suspend fun count(): Int
}

/** 收藏 DAO。 */
@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite ORDER BY createdAt DESC")
    fun observe(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorite ORDER BY createdAt DESC")
    suspend fun all(): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE titleKey = :titleKey")
    suspend fun delete(titleKey: String)

    @Query("DELETE FROM favorite")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(entities: List<FavoriteEntity>) {
        clear()
        if (entities.isNotEmpty()) upsertAll(entities)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FavoriteEntity>)
}

/** HTTP 缓存 DAO。 */
@Dao
interface HttpCacheDao {
    @Query("SELECT * FROM http_cache WHERE kind = :kind AND cacheKey = :key LIMIT 1")
    suspend fun get(kind: String, key: String): HttpCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HttpCacheEntity)

    @Query("DELETE FROM http_cache WHERE kind = :kind AND cacheKey LIKE :prefix || '%'")
    suspend fun invalidatePrefix(kind: String, prefix: String): Int

    @Query("DELETE FROM http_cache WHERE version != :currentVersion")
    suspend fun invalidateByVersion(currentVersion: Int): Int

    @Query("DELETE FROM http_cache")
    suspend fun clearAll()

    @Query("DELETE FROM http_cache WHERE hardExpiresAt < :now")
    suspend fun deleteExpired(now: Long): Int

    /** 按保存时间淘汰到 size 行以内（保留最新的 limit 行）。 */
    @Query("DELETE FROM http_cache WHERE id NOT IN (SELECT id FROM http_cache ORDER BY savedAt DESC LIMIT :limit)")
    suspend fun trimTo(limit: Int): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM http_cache")
    suspend fun sizeBytes(): Long

    @Query("SELECT COUNT(*) FROM http_cache")
    suspend fun count(): Int
}

/** 订阅 DAO。 */
@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscription")
    suspend fun all(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SubscriptionEntity>)

    @Query("DELETE FROM subscription")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(entities: List<SubscriptionEntity>) {
        clear()
        if (entities.isNotEmpty()) upsertAll(entities)
    }
}

/** 跳过标记 DAO。 */
@Dao
interface SkipMarkDao {
    @Query("SELECT * FROM skip_mark")
    suspend fun all(): List<SkipMarkEntity>

    @Query("SELECT * FROM skip_mark WHERE titleKey = :titleKey")
    suspend fun get(titleKey: String): SkipMarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SkipMarkEntity)

    @Query("DELETE FROM skip_mark WHERE titleKey = :titleKey")
    suspend fun delete(titleKey: String)

    @Transaction
    suspend fun replaceAll(entities: List<SkipMarkEntity>) {
        clear()
        if (entities.isNotEmpty()) upsertAll(entities)
    }

    @Query("DELETE FROM skip_mark")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SkipMarkEntity>)
}

/** 搜索历史 DAO。 */
@Dao
interface SearchHistoryDao {
    @Query("SELECT keyword FROM search_history ORDER BY createdAt DESC LIMIT :limit")
    suspend fun all(limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE lower(keyword) = lower(:keyword)")
    suspend fun delete(keyword: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

/** 源健康 DAO。 */
@Dao
interface SourceHealthDao {
    @Query("SELECT * FROM source_health")
    suspend fun all(): List<SourceHealthEntity>

    @Query("SELECT * FROM source_health WHERE sourceUrl = :url")
    suspend fun get(url: String): SourceHealthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SourceHealthEntity)

    @Query("DELETE FROM source_health")
    suspend fun clear()
}
