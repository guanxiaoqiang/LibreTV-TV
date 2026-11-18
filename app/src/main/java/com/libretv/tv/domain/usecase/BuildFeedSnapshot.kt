package com.libretv.tv.domain.usecase

import com.libretv.tv.data.SearchResult
import com.libretv.tv.domain.model.FeedCard
import com.libretv.tv.domain.model.FeedSnapshot
import com.libretv.tv.domain.model.HomeGenre
import com.libretv.tv.domain.model.asFeedCard

/**
 * 把一页原始结果合并进 (页签, 流派) 的快照。
 *
 * 规则顺序：聚合 → 关键词过滤（仅当本地过滤打开）→ 流派排序 → 稳定合并。
 * [previousCards] 是已经在屏上的顺序，永远原样保留——「先加载的排前面」，
 * 新标题按流派排序追加在尾部。分页游标只进不退：后台刷新不允许把
 * 已加载到第 3 页的栅格拉回第 2 页。
 */
object BuildFeedSnapshot {

    /** 每个采集源最多翻 3 页：更多页的收益远小于多等的几秒。 */
    const val MAX_SOURCE_PAGE = 3

    operator fun invoke(
        genre: HomeGenre,
        raw: List<SearchResult>,
        seen: Set<String>,
        old: FeedSnapshot?,
        keywordFilter: Boolean,
        page: Int,
        previousCards: List<FeedCard>,
        refreshExisting: Boolean = false
    ): FeedSnapshot {
        var groups = AggregateTitles(raw).filter { it.playableCount > 0 }
        if (keywordFilter && genre.keywords.isNotEmpty()) {
            groups = groups.filter { MatchGenreKeywords(it, genre.keywords) }
        }
        groups = SortTitleGroups(groups, genre.sort)
        val cards = mergeStableCards(
            previous = previousCards,
            incoming = groups.map { it.asFeedCard() },
            refreshExisting = refreshExisting
        )

        val nextPage = maxOf(page + 1, old?.nextSourcePage ?: 0)
        return FeedSnapshot(
            cards = cards,
            rawResults = raw.toList(),
            seenResultKeys = seen,
            seenDoubanIds = old?.seenDoubanIds.orEmpty(),
            nextSourcePage = nextPage,
            nextDoubanStart = old?.nextDoubanStart ?: 0,
            hasMore = nextPage <= MAX_SOURCE_PAGE && raw.isNotEmpty(),
            totalItems = raw.size
        )
    }
}
