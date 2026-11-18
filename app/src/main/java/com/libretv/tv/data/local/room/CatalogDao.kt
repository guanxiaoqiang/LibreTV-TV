package com.libretv.tv.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 内容目录 DAO（content-catalog C1）。 */
@Dao
interface CatalogDao {

    @Query("SELECT * FROM catalog_title ORDER BY updatedAt DESC")
    fun observeTitles(): Flow<List<CatalogTitleEntity>>

    @Query("SELECT * FROM catalog_title WHERE titleKey = :titleKey")
    suspend fun title(titleKey: String): CatalogTitleEntity?

    /**
     * 本地搜索索引匹配（6.1）：标题或别名模糊匹配。别名表用 alias 索引，
     * LIKE 前缀匹配足够，避免引入 FTS/中文分词依赖。
     */
    @Query(
        """
        SELECT * FROM catalog_title WHERE title LIKE '%' || :keyword || '%'
        UNION
        SELECT t.* FROM catalog_title t INNER JOIN catalog_alias a ON a.titleKey = t.titleKey
        WHERE a.alias LIKE :keyword || '%'
        """
    )
    suspend fun search(keyword: String): List<CatalogTitleEntity>

    @Query("SELECT * FROM catalog_offer WHERE titleKey = :titleKey ORDER BY latencyMs ASC")
    suspend fun offers(titleKey: String): List<CatalogOfferEntity>

    @Query("SELECT * FROM catalog_episode WHERE offerId = :offerId ORDER BY lineIndex, episodeIndex")
    suspend fun episodes(offerId: Long): List<CatalogEpisodeEntity>

    @Query("SELECT updatedAt FROM catalog_title WHERE titleKey = :titleKey")
    suspend fun updatedAt(titleKey: String): Long?

    @Query("SELECT COUNT(*) FROM catalog_title")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTitle(title: CatalogTitleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOffers(offers: List<CatalogOfferEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(episodes: List<CatalogEpisodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAliases(aliases: List<CatalogAliasEntity>)

    @Query("DELETE FROM catalog_offer WHERE titleKey = :titleKey")
    suspend fun deleteOffers(titleKey: String)

    @Query("DELETE FROM catalog_alias WHERE titleKey = :titleKey")
    suspend fun deleteAliases(titleKey: String)

    /** 采集失败时降低该来源的目录优先级（5.7）。 */
    @Query(
        """
        UPDATE catalog_offer SET updatedAt = updatedAt
        WHERE sourceUrl = :sourceUrl
        """
    )
    suspend fun touchOffers(sourceUrl: String)

    @Transaction
    suspend fun upsertAll(
        title: CatalogTitleEntity,
        offers: List<CatalogOfferEntity>,
        episodes: List<CatalogEpisodeEntity>,
        aliases: List<CatalogAliasEntity>
    ) {
        upsertTitle(title)
        // 先按 titleKey 清掉旧来源/别名，避免删除的来源残留（签名型失效）。
        deleteOffers(title.titleKey)
        deleteAliases(title.titleKey)
        if (offers.isNotEmpty()) upsertOffers(offers)
        if (episodes.isNotEmpty()) upsertEpisodes(episodes)
        if (aliases.isNotEmpty()) upsertAliases(aliases)
    }
}
