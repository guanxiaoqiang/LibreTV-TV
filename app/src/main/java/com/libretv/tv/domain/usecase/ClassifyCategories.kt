package com.libretv.tv.domain.usecase

import com.libretv.tv.data.VodClass
import com.libretv.tv.domain.model.HomeTab

/**
 * 把一个采集源的分类树映射到 App 的页签。
 *
 * 各采集源的 type_id 互不相同，只能按名称匹配。顶级节点（parentId == 0）
 * 在 Apple CMS 上通常没有自己的内容，所以货架从其子节点搭建。
 */
object ClassifyCategories {

    operator fun invoke(classes: List<VodClass>): Map<HomeTab, List<VodClass>> {
        val tops = classes.filter { it.parentId == 0 }
        val kidsOf = { id: Int -> if (id <= 0) emptyList() else classes.filter { it.parentId == id } }
        val out = linkedMapOf<HomeTab, List<VodClass>>()

        val movieTop = tops.firstOrNull { it.typeName.contains("电影") }
        val movieKids = kidsOf(movieTop?.typeId ?: -1)
        val movie = buildList {
            addAll(movieKids.filterNot {
                it.typeName.contains("纪录片") || it.typeName.contains("动画")
            }.take(5))
            if (isEmpty() && movieTop != null) add(movieTop)
        }.distinctBy { it.typeId }
        if (movie.isNotEmpty()) out[HomeTab.MOVIE] = movie

        val documentary = (tops.filter { it.typeName.contains("纪录片") } +
            movieKids.filter { it.typeName.contains("纪录片") })
            .distinctBy { it.typeId }.take(3)
        if (documentary.isNotEmpty()) out[HomeTab.DOCUMENTARY] = documentary

        val animeTop = tops.firstOrNull {
            it.typeName.contains("动漫") || it.typeName.contains("动画")
        }
        val anime = buildList {
            addAll(kidsOf(animeTop?.typeId ?: -1).take(4))
            addAll(movieKids.filter { it.typeName.contains("动画") })
            if (isEmpty() && animeTop != null) add(animeTop)
        }.distinctBy { it.typeId }.take(5)
        if (anime.isNotEmpty()) out[HomeTab.ANIME] = anime

        val seriesTop = tops.firstOrNull {
            it.typeName.contains("剧") && !it.typeName.contains("短剧")
        }
        val series = buildList {
            addAll(kidsOf(seriesTop?.typeId ?: -1)
                .filterNot { it.typeName.contains("短剧") }.take(5))
            if (isEmpty() && seriesTop != null) add(seriesTop)
        }.distinctBy { it.typeId }
        if (series.isNotEmpty()) out[HomeTab.SERIES] = series

        val varietyTop = tops.firstOrNull { it.typeName.contains("综艺") }
        val variety = buildList {
            addAll(kidsOf(varietyTop?.typeId ?: -1).take(4))
            if (isEmpty() && varietyTop != null) add(varietyTop)
        }.distinctBy { it.typeId }
        if (variety.isNotEmpty()) out[HomeTab.VARIETY] = variety

        val shortTops = tops.filter { it.typeName.contains("短剧") }
        val short = buildList {
            shortTops.forEach { top -> addAll(kidsOf(top.typeId).take(4)) }
            if (isEmpty()) addAll(shortTops)
        }.distinctBy { it.typeId }.take(5)
        if (short.isNotEmpty()) out[HomeTab.SHORT] = short

        return out
    }
}
