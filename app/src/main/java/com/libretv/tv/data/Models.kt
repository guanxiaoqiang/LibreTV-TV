package com.libretv.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 领域模型已迁至 `domain/model/`。旧包名保留为 typealias，存量代码零改动；
// 新代码一律 import `com.libretv.tv.domain.model.*`。
typealias SourceConfig = com.libretv.tv.domain.model.SourceConfig
typealias CategoryCatalog = com.libretv.tv.domain.model.CategoryCatalog
typealias Episode = com.libretv.tv.domain.model.Episode
typealias PlayLine = com.libretv.tv.domain.model.PlayLine
typealias DetailBundle = com.libretv.tv.domain.model.DetailBundle
typealias PlayRequest = com.libretv.tv.domain.model.PlayRequest
typealias BadgeTag = com.libretv.tv.domain.model.BadgeTag
typealias BadgeVisibility = com.libretv.tv.domain.model.BadgeVisibility
typealias FeedCard = com.libretv.tv.domain.model.FeedCard
typealias FeedBadge = com.libretv.tv.domain.model.FeedBadge
typealias FeedBadgeKind = com.libretv.tv.domain.model.FeedBadgeKind
typealias CardTarget = com.libretv.tv.domain.model.CardTarget
typealias HomeTab = com.libretv.tv.domain.model.HomeTab
typealias HomeGenre = com.libretv.tv.domain.model.HomeGenre
typealias FeedSort = com.libretv.tv.domain.model.FeedSort
typealias VodList = com.libretv.tv.domain.model.VodList
typealias VodClass = com.libretv.tv.domain.model.VodClass
typealias VodItem = com.libretv.tv.domain.model.VodItem
typealias VodDetail = com.libretv.tv.domain.model.VodDetail
typealias SearchResult = com.libretv.tv.domain.model.SearchResult
typealias WatchRecord = com.libretv.tv.domain.model.WatchRecord
typealias HealthState = com.libretv.tv.domain.model.HealthState
typealias SourceHealth = com.libretv.tv.domain.model.SourceHealth
typealias SourceEntry = com.libretv.tv.domain.model.SourceEntry
typealias DoubanSubject = com.libretv.tv.domain.model.DoubanSubject
typealias CachedFeed = com.libretv.tv.domain.model.CachedFeed
typealias CachedCard = com.libretv.tv.domain.model.CachedCard
typealias CachedBadge = com.libretv.tv.domain.model.CachedBadge
typealias CachedGroup = com.libretv.tv.domain.model.CachedGroup
typealias CachedOffer = com.libretv.tv.domain.model.CachedOffer

@Serializable
data class BundledSources(
    val sources: List<SourceConfig> = emptyList(),
    val meta: Meta = Meta()
) {
    @Serializable
    data class Meta(
        val name: String = "",
        val version: String = "",
        val source_count: Int = 0,
        val probed_at: String = "",
        val note: String = ""
    )
}
