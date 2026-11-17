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

/**
 * One cached strip of cards, keyed by "TAB|genre".
 *
 * The payload is deliberately thin. A card only needs enough to draw itself
 * and to open the title again: the playable lines are refetched from the
 * collector when the viewer picks a title, so they are never stored. That
 * keeps a cached tab in the tens of kilobytes instead of megabytes.
 */
@Serializable
data class CachedFeed(
    val key: String = "",
    val savedAt: Long = 0L,
    val cards: List<CachedCard> = emptyList(),
    val nextSourcePage: Int = 1,
    val nextDoubanStart: Int = 0,
    val hasMore: Boolean = true,
    val totalItems: Int = 0
)

@Serializable
data class CachedCard(
    val key: String = "",
    val title: String = "",
    val poster: String = "",
    val badges: List<CachedBadge> = emptyList(),
    val cornerBadge: String? = null,
    val group: CachedGroup? = null,
    val subject: DoubanSubject? = null
)

@Serializable
data class CachedBadge(
    val text: String = "",
    val kind: String = "NEUTRAL",
    val tag: String? = null
)

@Serializable
data class CachedGroup(
    val key: String = "",
    val title: String = "",
    val poster: String = "",
    val year: String = "",
    val area: String = "",
    val typeName: String = "",
    val offers: List<CachedOffer> = emptyList()
)

@Serializable
data class CachedOffer(
    val sourceName: String = "",
    val sourceUrl: String = "",
    val vodId: String = "",
    val quality: String = "UNKNOWN",
    val remark: String = "",
    val latencyMs: Long = 0L,
    val isTrailer: Boolean = false
)

/**
 * The local card cache of the home tabs.
 *
 * A plain SQLite table is enough here: one row per strip, the row holds the
 * JSON payload. It gives the app a fast first paint on every start and on
 * every tab switch — the grid is on screen before the network answers — while
 * the network still refreshes the strip in the background.
 */
class FeedCacheDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "feed_cache.db", null, 1) {

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
                saved_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS feed_cache")
        onCreate(db)
    }

    /** The cached strip of [key], or null when nothing was stored for it. */
    suspend fun read(key: String): CachedFeed? = withContext(Dispatchers.IO) {
        runCatching {
            readableDatabase.query(
                "feed_cache",
                arrayOf("payload"),
                "key = ?",
                arrayOf(key),
                null,
                null,
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                json.decodeFromString<CachedFeed>(cursor.getString(0))
            }
        }.getOrNull()
    }

    /** Store (or replace) the strip of [entry.key]. */
    suspend fun write(entry: CachedFeed) = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put("key", entry.key)
                put("payload", json.encodeToString(entry))
                put("saved_at", entry.savedAt)
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

    /** Drop every cached strip (used when the sources are reconfigured). */
    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { writableDatabase.delete("feed_cache", null, null) }
        Unit
    }
}
