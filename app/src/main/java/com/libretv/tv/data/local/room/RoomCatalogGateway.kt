package com.libretv.tv.data.local.room

import com.libretv.tv.domain.gateway.CatalogGateway
import com.libretv.tv.domain.model.CatalogEpisode
import com.libretv.tv.domain.model.CatalogOffer
import com.libretv.tv.domain.model.CatalogTitle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 基于 Room 的内容目录网关（content-catalog C1 / C3）。 */
class RoomCatalogGateway(private val db: LibreTvDatabase) : CatalogGateway {

    override fun observeTitles(): Flow<List<CatalogTitle>> =
        db.catalogDao().observeTitles().map { rows -> rows.map { it.toDomain() } }

    override suspend fun title(titleKey: String): CatalogTitle? =
        db.catalogDao().title(titleKey)?.toDomain()

    override suspend fun search(keyword: String): List<CatalogTitle> =
        db.catalogDao().search(keyword).map { it.toDomain() }

    override suspend fun offers(titleKey: String): List<CatalogOffer> =
        db.catalogDao().offers(titleKey).map { it.toDomain() }

    override suspend fun episodes(offerId: Long): List<CatalogEpisode> =
        db.catalogDao().episodes(offerId).map { it.toDomain() }

    override suspend fun upsert(
        title: CatalogTitle,
        offers: List<CatalogOffer>,
        episodes: List<CatalogEpisode>
    ) {
        db.catalogDao().upsertAll(
            title = CatalogTitleEntity(
                titleKey = title.titleKey,
                title = title.title,
                aliases = title.aliases.joinToString(","),
                poster = title.poster,
                year = title.year,
                area = title.area,
                typeName = title.typeName,
                doubanId = title.doubanId,
                offerCount = offers.size,
                updatedAt = title.updatedAt
            ),
            offers = offers.map {
                CatalogOfferEntity(
                    id = it.id, titleKey = it.titleKey, sourceUrl = it.sourceUrl,
                    sourceName = it.sourceName, vodId = it.vodId, quality = it.quality,
                    remark = it.remark, latencyMs = it.latencyMs,
                    lineCount = it.lineCount, updatedAt = it.updatedAt
                )
            },
            episodes = episodes.map {
                CatalogEpisodeEntity(
                    id = it.id, offerId = it.offerId, lineIndex = it.lineIndex,
                    episodeIndex = it.episodeIndex, episodeName = it.episodeName,
                    updatedAt = it.updatedAt
                )
            },
            aliases = (listOf(title.title) + title.aliases)
                .filter { it.isNotBlank() }
                .map { CatalogAliasEntity(it.trim().lowercase(), title.titleKey) }
        )
    }

    override suspend fun isFresh(titleKey: String, now: Long, ttlMs: Long): Boolean {
        val updatedAt = db.catalogDao().updatedAt(titleKey) ?: return false
        return now - updatedAt < ttlMs
    }

    override suspend fun recordHarvestFailure(sourceUrl: String, now: Long) {
        db.catalogDao().touchOffers(sourceUrl)
    }

    override suspend fun count(): Int = db.catalogDao().count()
}

private fun CatalogTitleEntity.toDomain() = CatalogTitle(
    titleKey = titleKey, title = title,
    aliases = aliases.split(",").filter { it.isNotBlank() },
    poster = poster, year = year, area = area, typeName = typeName,
    doubanId = doubanId, updatedAt = updatedAt
)

private fun CatalogOfferEntity.toDomain() = CatalogOffer(
    id = id, titleKey = titleKey, sourceUrl = sourceUrl, sourceName = sourceName,
    vodId = vodId, quality = quality, remark = remark, latencyMs = latencyMs,
    lineCount = lineCount, updatedAt = updatedAt
)

private fun CatalogEpisodeEntity.toDomain() = CatalogEpisode(
    id = id, offerId = offerId, lineIndex = lineIndex,
    episodeIndex = episodeIndex, episodeName = episodeName, updatedAt = updatedAt
)
