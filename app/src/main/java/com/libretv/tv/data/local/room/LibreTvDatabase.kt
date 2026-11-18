package com.libretv.tv.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 统一持久化数据库（local-first-storage 1.4）。
 *
 * 版本约定：
 *  - v1：7 张核心表（历史/收藏/缓存/健康/订阅/跳过标记/搜索历史）
 *  - v2：新增 4 张内容目录表（catalog_*）
 *
 * **禁止破坏性迁移**（2.3）：不调用 fallbackToDestructiveMigration，所有结构
 * 变化都用显式 [Migration]，保证观影记录与收藏在升级后零丢失。
 */
@Database(
    version = LibreTvDatabase.VERSION,
    exportSchema = true,
    entities = [
        WatchRecordEntity::class,
        FavoriteEntity::class,
        HttpCacheEntity::class,
        SourceHealthEntity::class,
        SubscriptionEntity::class,
        SkipMarkEntity::class,
        SearchHistoryEntity::class,
        CatalogTitleEntity::class,
        CatalogOfferEntity::class,
        CatalogEpisodeEntity::class,
        CatalogAliasEntity::class
    ]
)
abstract class LibreTvDatabase : RoomDatabase() {

    abstract fun watchRecordDao(): WatchRecordDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun httpCacheDao(): HttpCacheDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun skipMarkDao(): SkipMarkDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun sourceHealthDao(): SourceHealthDao
    abstract fun catalogDao(): CatalogDao

    companion object {
        const val VERSION = 2
        const val DB_NAME = "libretv.db"

        @Volatile
        private var instance: LibreTvDatabase? = null

        fun get(context: Context): LibreTvDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibreTvDatabase::class.java,
                    DB_NAME
                )
                    // 2.1 / 2.3：显式迁移，绝不 fallbackToDestructiveMigration。
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }

        /** v1 → v2：新增内容目录表（2.1）。核心表原样保留，数据零丢失。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_title` (
                        `titleKey` TEXT NOT NULL, `title` TEXT NOT NULL,
                        `aliases` TEXT NOT NULL, `poster` TEXT NOT NULL,
                        `year` TEXT NOT NULL, `area` TEXT NOT NULL,
                        `typeName` TEXT NOT NULL, `doubanId` TEXT NOT NULL,
                        `offerCount` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`titleKey`))
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_title_titleKey` ON `catalog_title` (`titleKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_title_updatedAt` ON `catalog_title` (`updatedAt`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_offer` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `titleKey` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL,
                        `sourceName` TEXT NOT NULL, `vodId` TEXT NOT NULL,
                        `quality` TEXT NOT NULL, `remark` TEXT NOT NULL,
                        `latencyMs` INTEGER NOT NULL, `lineCount` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL)
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_offer_titleKey` ON `catalog_offer` (`titleKey`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_catalog_offer_sourceUrl_vodId` ON `catalog_offer` (`sourceUrl`, `vodId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_episode` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `offerId` INTEGER NOT NULL, `lineIndex` INTEGER NOT NULL,
                        `episodeIndex` INTEGER NOT NULL, `episodeName` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL)
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_episode_offerId` ON `catalog_episode` (`offerId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catalog_alias` (
                        `alias` TEXT NOT NULL, `titleKey` TEXT NOT NULL,
                        PRIMARY KEY(`alias`))
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_alias_alias` ON `catalog_alias` (`alias`)")
            }
        }
    }
}
