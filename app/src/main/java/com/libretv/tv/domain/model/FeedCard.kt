package com.libretv.tv.domain.model

import com.libretv.tv.data.remote.DoubanSubject

/** Colour role of a small tag drawn on the poster. */
enum class FeedBadgeKind { NEUTRAL, ACCENT, QUALITY, LATENCY }

/**
 * One small tag on the poster.
 *
 * [tag] is the identity of the information the tag carries, so the settings
 * page can switch the year, the source count, the quality and the latency
 * independently.
 */
data class FeedBadge(
    val text: String,
    val kind: FeedBadgeKind = FeedBadgeKind.NEUTRAL,
    val tag: BadgeTag? = null
)

/**
 * 点击一张卡片后的去处。
 *
 * [FeedCard] 曾用 `group: TitleGroup?` 与 `subject: DoubanSubject?` 两个
 * 可空字段表达两个目的地——两者同时为 null 的非法状态编译器无法拦截。
 * 密封类让「要么有聚合组、要么有豆瓣线索、要么纯展示」成为类型事实。
 */
sealed class CardTarget {
    /** 聚合标题：多源进选源页，单源直接进详情。 */
    data class Aggregated(val group: TitleGroup) : CardTarget()

    /** 豆瓣发现：点击后到全部采集源里搜这个标题。 */
    data class Douban(val subject: DoubanSubject) : CardTarget()
}

/**
 * One card in the unified home grid.
 *
 * Douban discoveries and all-source aggregated titles share exactly the same
 * poster + title layout; only the badges differ. [target] decides where a tap
 * goes: an aggregated title opens the comparison page (multi-source) or the
 * detail page (single-source), a Douban discovery searches every bundled
 * source for that title.
 */
data class FeedCard(
    val key: String,
    val title: String,
    val poster: String,
    val badges: List<FeedBadge> = emptyList(),
    val cornerBadge: String? = null,
    val target: CardTarget? = null
) {
    /** 兼容读取：聚合组（`target` 是 [CardTarget.Aggregated] 时非空）。 */
    val group: TitleGroup? get() = (target as? CardTarget.Aggregated)?.group

    /** 兼容读取：豆瓣线索（`target` 是 [CardTarget.Douban] 时非空）。 */
    val subject: DoubanSubject? get() = (target as? CardTarget.Douban)?.subject
}

fun shortLatency(ms: Long): String = when {
    ms <= 0L -> "延时未知"
    ms < 1000L -> "$ms ms"
    else -> "%.1f s".format(ms / 1000.0)
}

/**
 * The one canonical renderer data for an aggregated title. Both the home feed
 * and the search results call this, so a card looks identical everywhere and
 * the year / source count / best quality / quickest latency tags never drift.
 */
fun TitleGroup.asFeedCard(): FeedCard = FeedCard(
    key = "g|$key",
    title = title,
    poster = poster,
    badges = listOf(
        FeedBadge(year.ifBlank { "年份未知" }, FeedBadgeKind.NEUTRAL, BadgeTag.YEAR),
        FeedBadge("$playableCount 源", FeedBadgeKind.ACCENT, BadgeTag.SOURCES),
        FeedBadge(bestQuality.short, FeedBadgeKind.QUALITY, BadgeTag.QUALITY),
        FeedBadge(shortLatency(fastestMs), FeedBadgeKind.LATENCY, BadgeTag.LATENCY)
    ),
    target = CardTarget.Aggregated(this)
)

/** 豆瓣发现卡的统一渲染数据。 */
fun doubanCard(subject: DoubanSubject): FeedCard = FeedCard(
    key = "d|${subject.id.ifBlank { subject.title }}",
    title = subject.title,
    poster = subject.cover,
    cornerBadge = subject.rate.takeIf { it.isNotBlank() },
    badges = listOf(
        FeedBadge(subject.year.ifBlank { "新片" }, FeedBadgeKind.NEUTRAL, BadgeTag.YEAR),
        FeedBadge("豆瓣推荐", FeedBadgeKind.ACCENT, BadgeTag.DOUBAN),
        FeedBadge("全网搜源", FeedBadgeKind.LATENCY, BadgeTag.SOURCES)
    ),
    target = CardTarget.Douban(subject)
)
