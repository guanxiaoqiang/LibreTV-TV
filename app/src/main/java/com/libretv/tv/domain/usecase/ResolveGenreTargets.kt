package com.libretv.tv.domain.usecase

import com.libretv.tv.data.CategoryCatalog
import com.libretv.tv.data.SourceConfig
import com.libretv.tv.domain.model.HomeGenre
import com.libretv.tv.domain.model.HomeTab
import com.libretv.tv.domain.model.isSortOnly

/**
 * 把一个页签 + 流派解析成 (源, type_id) 采集目标。
 *
 * 纯排序型流派直接用页签的常规子分类；具名流派到**每个源自己的分类树**
 * 里找名称匹配的节点。没有任何源暴露该节点时返回 `keywordFilter = true`，
 * 由调用方在合并结果后按 [HomeGenre.keywords] 本地过滤——分类树扁平的源
 * 也能参与具名流派。
 */
object ResolveGenreTargets {

    /**
     * @return 采集目标列表，以及是否需要按关键词做本地过滤。
     */
    operator fun invoke(
        catalogs: List<CategoryCatalog>,
        tab: HomeTab,
        genre: HomeGenre
    ): Pair<List<Pair<SourceConfig, Int>>, Boolean> {
        val defaultTargets = catalogs.flatMap { catalog ->
            ClassifyCategories(catalog.classes)[tab].orEmpty().take(2).map { c ->
                catalog.source to c.typeId
            }
        }.distinctBy { (source, typeId) -> "${source.url}|$typeId" }

        if (genre.isSortOnly()) return defaultTargets to false
        val matched = catalogs.flatMap { catalog ->
            catalog.classes
                .filter { c -> genre.categoryAliases.any { c.typeName.contains(it) } }
                .take(2)
                .map { c -> catalog.source to c.typeId }
        }.distinctBy { (source, typeId) -> "${source.url}|$typeId" }

        return if (matched.isNotEmpty()) matched to false else defaultTargets to true
    }
}
