package com.libretv.tv.domain.model

import kotlinx.serialization.Serializable

/**
 * One cached strip of cards, keyed by "v2|TAB|genre|<sourceSignature>"
 * (see [FeedCachePolicy.cacheKey] and design-detail.md §D.2).
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
