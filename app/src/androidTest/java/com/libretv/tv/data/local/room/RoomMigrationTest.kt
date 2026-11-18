package com.libretv.tv.data.local.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Room 显式迁移的 instrumented 测试（local-first-storage 2.1）。
 *
 * 断言 v1 → v2 后核心表数据零丢失、目录表建立。用 MigrationTestHelper，
 * 需要真机/模拟器运行。v1 schema 由测试手动建立（本库首次引入即 v2，仓库里
 * 没有 v1.json，故不依赖 createDatabase 的 schema 文件）。
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LibreTvDatabase::class.java,
        emptyList<androidx.room.migration.AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate_1_to_2_keeps_core_data_and_creates_catalog_tables() {
        val dbName = "migration-test"

        // 手动建 v1 的核心表（与 LibreTvDatabase v2 的核心表结构一致），写入一行历史。
        helper.createDatabase(dbName, 1).use { db ->
            createCoreTablesIfMissing(db)
            db.execSQL(
                """INSERT OR REPLACE INTO watch_record
                   (key, titleKey, title, poster, sourceName, sourceUrl, vodId, episodeName,
                    rawUrl, lineIndex, episodeIndex, positionMs, durationMs, updatedAt, typeName)
                   VALUES ('k1','片','片','','源','http://s','v','第1集','',0,0,100,1000,1,'')"""
            )
        }

        helper.runMigrationsAndValidate(
            dbName, 2, false, LibreTvDatabase.MIGRATION_1_2
        ).use { db ->
            val cursor = db.query("SELECT COUNT(*) FROM watch_record")
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            cursor.close()

            val catalog = db.query("SELECT COUNT(*) FROM catalog_title")
            catalog.moveToFirst()
            assertEquals(0, catalog.getInt(0))
            catalog.close()
        }
    }

    /**
     * 缓存清理不影响历史与收藏（3.6 / 7.3）：写入历史、收藏与一条已过期缓存，
     * 调 prune 后前两者仍在、过期缓存被删。
     */
    @Test
    fun prune_removes_expired_cache_but_keeps_history_and_favorites() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val db = LibreTvDatabase.get(context)
        runBlocking {
            db.watchRecordDao().upsert(
                WatchRecordEntity(key = "k1", titleKey = "片", title = "片", updatedAt = 1L)
            )
            db.favoriteDao().upsert(FavoriteEntity(titleKey = "片", title = "片", createdAt = 1L))
            db.httpCacheDao().upsert(
                HttpCacheEntity(
                    kind = "feed", cacheKey = "old", payload = "{}",
                    savedAt = 0L, expiresAt = 1L, hardExpiresAt = 1L,
                    version = 2, sourceSignature = "s", sizeBytes = 2
                )
            )
            RoomCacheGateway(db).prune(now = 10_000L)

            assertEquals(1, db.watchRecordDao().count())
            assertEquals(1, db.favoriteDao().all().size)
            assertEquals(0, db.httpCacheDao().count())
        }
    }

    /** createDatabase 会用 v2 schema 建好全部表；这里仅需确保核心表存在可写。 */
    private fun createCoreTablesIfMissing(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS watch_record (
                `key` TEXT NOT NULL PRIMARY KEY, `titleKey` TEXT NOT NULL, `title` TEXT NOT NULL,
                `poster` TEXT NOT NULL, `sourceName` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL,
                `vodId` TEXT NOT NULL, `episodeName` TEXT NOT NULL, `rawUrl` TEXT NOT NULL,
                `lineIndex` INTEGER NOT NULL, `episodeIndex` INTEGER NOT NULL,
                `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL, `typeName` TEXT NOT NULL)"""
        )
    }
}
