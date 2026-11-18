package com.libretv.tv.domain.gateway

import com.libretv.tv.domain.model.CatalogEpisode
import com.libretv.tv.domain.model.CatalogOffer
import com.libretv.tv.domain.model.CatalogTitle
import kotlinx.coroutines.flow.Flow

/**
 * 内容目录的存取契约（local-first-storage 1.3 / content-catalog C1）。
 *
 * 领域层纯接口。目录只保存元数据与集名，不含播放地址——播放地址按
 * 「来源 + 影片 ID + 线路 + 剧集」实时解析（D5）。
 */
interface CatalogGateway {

    /** 观察全部目录标题（按更新时间倒序）。 */
    fun observeTitles(): Flow<List<CatalogTitle>>

    /** 按归一化片名读一条标题。 */
    suspend fun title(titleKey: String): CatalogTitle?

    /** 标题 + 别名关键词匹配（本地搜索索引）。 */
    suspend fun search(keyword: String): List<CatalogTitle>

    /** 某标题的全部来源。 */
    suspend fun offers(titleKey: String): List<CatalogOffer>

    /** 某来源的全部集名。 */
    suspend fun episodes(offerId: Long): List<CatalogEpisode>

    /**  upsert 一条标题及其来源、集名（采集器写入）。 */
    suspend fun upsert(
        title: CatalogTitle,
        offers: List<CatalogOffer>,
        episodes: List<CatalogEpisode>
    )

    /** 该标题缓存是否仍在有效期内。 */
    suspend fun isFresh(titleKey: String, now: Long, ttlMs: Long): Boolean

    /** 采集失败时更新来源健康度（5.7）。 */
    suspend fun recordHarvestFailure(sourceUrl: String, now: Long)

    /** 目录条目总数（设置页展示占用）。 */
    suspend fun count(): Int
}
