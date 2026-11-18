package com.libretv.tv.domain.model

import com.libretv.tv.data.SearchResult

/**
 * Everything needed to resume a (tab, genre) strip: the deduplicated raw
 * results (so later source pages can re-aggregate with earlier ones), the
 * already built cards, and the cursors for the two possible load-more paths.
 */
data class FeedSnapshot(
    val cards: List<FeedCard>,
    val rawResults: List<SearchResult>,
    val seenResultKeys: Set<String>,
    val seenDoubanIds: Set<String>,
    val nextSourcePage: Int,
    val nextDoubanStart: Int,
    val hasMore: Boolean,
    val totalItems: Int
)
