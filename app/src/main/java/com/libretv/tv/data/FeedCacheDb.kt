package com.libretv.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.libretv.tv.data.remote.DoubanSubject
import com.libretv.tv.domain.model.CachedFeed
import com.libretv.tv.domain.model.FeedCachePolicy

// 缓存模型已迁至 `domain/model/Cache.kt`（`data/Models.kt` 里的 typealias 保持旧引用可用）。
// 失效判定在 `domain/model/FeedCachePolicy.kt`，本类只负责存取与迁移。

/**
 * The local card cache of the home tabs.
 *
 * A plain SQLite table is enough here: one row per strip, the row holds the
 * JSON payload. It gives the app a fast first paint on every start and on
 * every tab switch — the grid is on screen before the network answers — while
 * the network still refreshes the strip in the background.
 *
 * Schema v2 adds the natural-invalidation columns (`version` / `ttl_minutes` /
 * `source_signature`, §D.2). There is no manual wipe path any more: a source
 * set change moves the cache key and the signature check rejects old rows.
 */
class FeedCacheDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "feed_cache.db", null, 2) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS feed_cache (
                key TEXT PRIMARY KEY NOT NULL,
                payload TEXT NOT NULL,
                saved_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 0,
                ttl_minutes INTEGER NOT NULL DEFAULT 0,
                source_signature TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
    }

    /**
     * v1 → v2 只加列，不重建表：旧行原地保留（version 落到默认值 0），
     * 读取时被 [FeedCachePolicy.isValid] 判为过期，数据不丢。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE feed_cache ADD COLUMN version INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE feed_cache ADD COLUMN ttl_minutes INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE feed_cache ADD COLUMN source_signature TEXT NOT NULL DEFAULT ''"
            )
        }
    }

    /**
     * The cached strip of [key], but only when it is still valid.
     *
     * 过期 / 版本不符 / 签名不符都返回 null——调用方看不到「半新的数据」，
     * 也不需要再手工清库。旧 schema 的行（version = 0）在这里自然过期。
     */
    suspend fun read(
        key: String,
        sourceSignature: String,
        now: Long = System.currentTimeMillis()
    ): CachedFeed? = withContext(Dispatchers.IO) {
        runCatching {
            readableDatabase.query(
                "feed_cache",
                arrayOf("payload", "version", "ttl_minutes", "source_signature", "saved_at"),
                "key = ?",
                arrayOf(key),
                null,
                null,
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val version = cursor.getInt(1)
                val ttlMinutes = cursor.getLong(2)
                val savedSignature = cursor.getString(3) ?: ""
                val savedAt = cursor.getLong(4)
                if (!FeedCachePolicy.isValid(
                        version, ttlMinutes, savedSignature, sourceSignature, savedAt, now
                    )
                ) {
                    return@use null
                }
                json.decodeFromString<CachedFeed>(cursor.getString(0))
            }
        }.getOrNull()
    }

    /**
     * 逐行读出全部缓存（含失效元数据），供一次性迁移到 Room 的 http_cache 表。
     * 迁移需求 2.1：旧载荷一行不丢，版本与有效期字段在搬运时补齐。
     */
    suspend fun allRows(): List<Triple<CachedFeed, String, Long>> = withContext(Dispatchers.IO) {
        runCatching {
            readableDatabase.query(
                "feed_cache",
                arrayOf("payload", "saved_at", "ttl_minutes", "source_signature"),
                null, null, null, null, null
            ).use { cursor ->
                val rows = mutableListOf<Triple<CachedFeed, String, Long>>()
                while (cursor.moveToNext()) {
                    runCatching {
                        Triple(
                            json.decodeFromString<CachedFeed>(cursor.getString(0)),
                            cursor.getString(3) ?: "",
                            cursor.getLong(2)
                        )
                    }.getOrNull()?.let { rows += it }
                }
                rows
            }
        }.getOrDefault(emptyList())
    }

    /** Store (or replace) the strip of [entry.key] with its invalidation metadata. */
    suspend fun write(
        entry: CachedFeed,
        sourceSignature: String,
        ttlMinutes: Long = FeedCachePolicy.FEED_TTL_MINUTES
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put("key", entry.key)
                put("payload", json.encodeToString(entry))
                put("saved_at", entry.savedAt)
                put("version", FeedCachePolicy.CACHE_VERSION)
                put("ttl_minutes", ttlMinutes)
                put("source_signature", sourceSignature)
            }
            writableDatabase.insertWithOnConflict(
                "feed_cache",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        Unit
    }
}
