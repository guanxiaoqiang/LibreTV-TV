package com.libretv.tv.domain.model

/**
 * The metadata tags that can be drawn on a poster.
 *
 * Every tag has its own switch in the settings page, so a viewer can keep
 * only the information that matters to them — for example just the quality,
 * without the year and the latency.
 */
enum class BadgeTag {
    /** Release year. */
    YEAR,

    /** Number of playable sources. */
    SOURCES,

    /** Best available quality. */
    QUALITY,

    /** Quickest response time. */
    LATENCY,

    /** Douban discovery markers ("豆瓣推荐" / "全网搜源"). */
    DOUBAN
}

/**
 * Which poster tags are visible.
 *
 * All tags are on by default. Turning every one of them off leaves a clean
 * poster that only carries the title bar.
 */
data class BadgeVisibility(
    val year: Boolean = true,
    val sources: Boolean = true,
    val quality: Boolean = true,
    val latency: Boolean = true,
    val douban: Boolean = true
) {
    /** Whether the tag with this identity should be drawn. */
    fun allows(tag: BadgeTag?): Boolean = when (tag) {
        null -> true
        BadgeTag.YEAR -> year
        BadgeTag.SOURCES -> sources
        BadgeTag.QUALITY -> quality
        BadgeTag.LATENCY -> latency
        BadgeTag.DOUBAN -> douban
    }

    /** How many tags are currently shown — used for the settings summary. */
    val enabledCount: Int
        get() = listOf(year, sources, quality, latency, douban).count { it }

    /** The same set with one tag flipped. */
    fun with(tag: BadgeTag, enabled: Boolean): BadgeVisibility = when (tag) {
        BadgeTag.YEAR -> copy(year = enabled)
        BadgeTag.SOURCES -> copy(sources = enabled)
        BadgeTag.QUALITY -> copy(quality = enabled)
        BadgeTag.LATENCY -> copy(latency = enabled)
        BadgeTag.DOUBAN -> copy(douban = enabled)
    }
}
