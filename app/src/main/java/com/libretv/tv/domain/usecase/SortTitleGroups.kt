package com.libretv.tv.domain.usecase

import com.libretv.tv.domain.model.FeedSort
import com.libretv.tv.domain.model.TitleGroup

/**
 * 按流派排序聚合标题。
 *
 * 三个排序维度各自回答观众的一个问题：最新（年价新、可播源多）、经典
 * （年份早但必须真实可播）、热门（可播源最多、画质最好、响应最快）。
 */
object SortTitleGroups {

    operator fun invoke(groups: List<TitleGroup>, sort: FeedSort): List<TitleGroup> = when (sort) {
        FeedSort.NEW -> groups.sortedWith(
            compareByDescending<TitleGroup> { yearNumber(it.year) }
                .thenByDescending { it.playableCount }
        )
        FeedSort.CLASSIC -> groups.sortedWith(
            compareBy<TitleGroup> {
                val y = yearNumber(it.year)
                if (y in 1900..2026) y else 9999
            }.thenByDescending { it.playableCount }
        )
        FeedSort.HOT -> groups.sortedWith(
            compareByDescending<TitleGroup> { it.playableCount }
                .thenByDescending { it.bestQuality.rank }
                .thenBy { if (it.fastestMs > 0L) it.fastestMs else Long.MAX_VALUE }
        )
    }

    /** 「2025」「2025-01-01」「暂无」都能归一化成年份数字；取不出返回 0。 */
    internal fun yearNumber(year: String): Int =
        year.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
}
