package com.libretv.tv.ui

import com.libretv.tv.data.BadgeTag
import com.libretv.tv.data.CachedBadge
import com.libretv.tv.data.CachedCard
import com.libretv.tv.data.CachedFeed
import com.libretv.tv.data.CachedGroup
import com.libretv.tv.data.CachedOffer
import com.libretv.tv.data.QualityTier
import com.libretv.tv.data.SourceOffer
import com.libretv.tv.data.TitleGroup
import com.libretv.tv.data.VodItem

/**
 * Mapping between the live feed model and the thin payload kept in the local
 * cache database.
 *
 * Only what a card needs to draw itself survives the round trip: the poster,
 * the badges and the identity of the title. The playable lines are refetched
 * from the collector when the title is opened, so they are dropped here.
 */

/** Cards persisted per strip. Enough for two screens of content. */
internal const val FEED_CACHE_CARDS = 48

internal fun FeedCard.toCached(): CachedCard = CachedCard(
    key = key,
    title = title,
    poster = poster,
    badges = badges.map {
        CachedBadge(text = it.text, kind = it.kind.name, tag = it.tag?.name)
    },
    cornerBadge = cornerBadge,
    group = group?.toCached(),
    subject = subject
)

internal fun CachedCard.toFeedCard(): FeedCard = FeedCard(
    key = key,
    title = title,
    poster = poster,
    badges = badges.map {
        FeedBadge(
            text = it.text,
            kind = runCatching { FeedBadgeKind.valueOf(it.kind) }.getOrDefault(FeedBadgeKind.NEUTRAL),
            tag = it.tag?.let { name -> runCatching { BadgeTag.valueOf(name) }.getOrNull() }
        )
    },
    cornerBadge = cornerBadge,
    group = group?.toTitleGroup(),
    subject = subject
)

private fun TitleGroup.toCached(): CachedGroup = CachedGroup(
    key = key,
    title = title,
    poster = poster,
    year = year,
    area = area,
    typeName = typeName,
    offers = offers.map {
        CachedOffer(
            sourceName = it.sourceName,
            sourceUrl = it.sourceUrl,
            vodId = it.vodId,
            quality = it.quality.name,
            remark = it.remark,
            latencyMs = it.latencyMs,
            isTrailer = it.isTrailer
        )
    }
)

private fun CachedGroup.toTitleGroup(): TitleGroup = TitleGroup(
    key = key,
    title = title,
    poster = poster,
    year = year,
    area = area,
    typeName = typeName,
    offers = offers.map {
        SourceOffer(
            sourceName = it.sourceName,
            sourceUrl = it.sourceUrl,
            vodId = it.vodId,
            item = VodItem(
                vodId = it.vodId,
                vodName = title,
                vodPic = poster,
                vodYear = year,
                vodArea = area,
                typeName = typeName,
                vodRemarks = it.remark
            ),
            quality = runCatching { QualityTier.valueOf(it.quality) }.getOrDefault(QualityTier.UNKNOWN),
            remark = it.remark,
            latencyMs = it.latencyMs,
            isTrailer = it.isTrailer
        )
    }
)

/** The strip as it is written to the local cache. */
internal fun FeedSnapshot.toCachedFeed(key: String, savedAt: Long): CachedFeed = CachedFeed(
    key = key,
    savedAt = savedAt,
    cards = cards.take(FEED_CACHE_CARDS).map { it.toCached() },
    nextSourcePage = nextSourcePage,
    nextDoubanStart = nextDoubanStart,
    hasMore = hasMore,
    totalItems = totalItems
)

/**
 * A restored strip.
 *
 * The raw collector results are not cached, so the deduplication sets start
 * empty: the next page is merged into the restored cards by title, which is
 * exactly what the live pipeline does.
 */
internal fun CachedFeed.toSnapshot(): FeedSnapshot = FeedSnapshot(
    cards = cards.map { it.toFeedCard() },
    rawResults = emptyList(),
    seenResultKeys = emptySet(),
    seenDoubanIds = emptySet(),
    nextSourcePage = nextSourcePage,
    nextDoubanStart = nextDoubanStart,
    hasMore = hasMore,
    totalItems = totalItems
)
