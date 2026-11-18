package com.libretv.tv.domain.usecase

import com.libretv.tv.domain.model.TitleGroup

/**
 * 判断一个聚合标题是否命中流派的关键词。
 *
 * 分类树扁平的源答不出具名流派，这时按关键词做本地过滤。 haystack 取组
 * 元信息加上前两个 offer 的备注与简介——同一个标题在不同源上的元信息
 * 详略不同，多看几条能显著减少误杀。
 */
object MatchGenreKeywords {

    operator fun invoke(group: TitleGroup, keywords: List<String>): Boolean {
        val haystack = buildString {
            append(group.typeName).append(' ').append(group.area).append(' ')
            append(group.title).append(' ')
            group.offers.take(2).forEach { offer ->
                append(offer.title.typeName).append(' ')
                append(offer.title.vodArea).append(' ')
                append(offer.title.vodRemarks).append(' ')
                append(offer.title.vodContent.take(60)).append(' ')
            }
        }.lowercase()
        return keywords.any { haystack.contains(it.lowercase()) }
    }
}
