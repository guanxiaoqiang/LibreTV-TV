package com.libretv.tv.domain.model

/**
 * Pages in left-rail order. [doubanType] selects the movie / tv feed used by
 * the recommend page and by the discovery fallback when a collector has no
 * matching sub-category for the selected genre.
 */
enum class HomeTab(val label: String, val doubanType: String) {
    RECOMMEND("推荐", "movie"),
    HISTORY("历史", "movie"),
    MOVIE("电影", "movie"),
    SERIES("剧集", "tv"),
    VARIETY("综艺", "tv"),
    ANIME("动漫", "movie"),
    DOCUMENTARY("纪录片", "movie"),
    SHORT("短剧", "tv")
}

/**
 * Second-level "type" filters shown at the top of every content page.
 *
 * A genre is three things at once:
 *  - a label in the chip strip,
 *  - a set of names used to locate the matching node in each collector's own
 *    category tree (ids differ between the 30 bundled APIs, so names are the
 *    only portable key),
 *  - a Douban tag used by the recommend page and as a fallback when a collector
 *    has no such sub-category.
 *
 * [keywords] are also used to filter the aggregated titles locally, so a
 * genre still changes the grid even on sources with a flat category tree.
 */
enum class FeedSort { HOT, NEW, CLASSIC }

data class HomeGenre(
    val label: String,
    val categoryAliases: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val doubanTag: String = label,
    val doubanFallbackTag: String = "热门",
    val sort: FeedSort = FeedSort.HOT
)

private fun genre(
    label: String,
    aliases: List<String> = emptyList(),
    keywords: List<String> = aliases,
    doubanTag: String = label,
    doubanFallbackTag: String = "热门",
    sort: FeedSort = FeedSort.HOT
) = HomeGenre(label, aliases, keywords, doubanTag, doubanFallbackTag, sort)

private val hot = genre("热门")
private val latest = genre("最新", sort = FeedSort.NEW)
private val classic = genre("经典", doubanFallbackTag = "高分", sort = FeedSort.CLASSIC)

/** Chip strips per page. 推荐 is driven purely by Douban tags. */
fun HomeTab.genres(): List<HomeGenre> = when (this) {
    HomeTab.RECOMMEND -> listOf(
        hot,
        latest,
        classic,
        genre("动作", listOf("动作")),
        genre("喜剧", listOf("喜剧")),
        genre("科幻", listOf("科幻")),
        genre("爱情", listOf("爱情")),
        genre("悬疑", listOf("悬疑")),
        genre("动画", listOf("动画")),
        genre("战争", listOf("战争")),
        genre("恐怖", listOf("恐怖")),
        genre("高分", listOf("高分"))
    )

    HomeTab.MOVIE -> listOf(
        hot,
        latest,
        classic,
        genre("动作", listOf("动作", "武侠", "枪战")),
        genre("喜剧", listOf("喜剧", "搞笑")),
        genre("科幻", listOf("科幻", "魔幻")),
        genre("爱情", listOf("爱情", "言情")),
        genre("悬疑", listOf("悬疑", "推理", "惊悚")),
        genre("战争", listOf("战争", "军事")),
        genre("犯罪", listOf("犯罪", "警匪")),
        genre("动画", listOf("动画", "动漫")),
        genre("恐怖", listOf("恐怖", "惊吓"))
    )

    HomeTab.SERIES -> listOf(
        hot,
        latest,
        classic,
        genre("国产剧", listOf("国产", "大陆", "内地", "华语"), keywords = listOf("国产", "大陆", "内地", "中国大陆", "华语")),
        genre("美剧", listOf("美剧", "欧美", "美国"), keywords = listOf("美剧", "美国", "欧美")),
        genre("英剧", listOf("英剧", "英国"), keywords = listOf("英剧", "英国")),
        genre("韩剧", listOf("韩剧", "韩国"), keywords = listOf("韩剧", "韩国")),
        genre("日剧", listOf("日剧", "日本"), keywords = listOf("日剧", "日本")),
        genre("港剧", listOf("港剧", "香港", "港台"), keywords = listOf("港剧", "中国香港", "香港")),
        genre("泰剧", listOf("泰剧", "泰国"), keywords = listOf("泰剧", "泰国")),
        genre("古装", listOf("古装", "武侠"), keywords = listOf("古装", "武侠")),
        genre("都市", listOf("都市", "现代", "情感"), keywords = listOf("都市", "现代", "情感"))
    )

    HomeTab.VARIETY -> listOf(
        hot,
        latest,
        classic,
        genre("大陆综艺", listOf("大陆", "内地", "国产"), keywords = listOf("大陆", "内地", "国产")),
        genre("港台综艺", listOf("港台", "香港", "台湾"), keywords = listOf("港台", "香港", "台湾")),
        genre("日韩综艺", listOf("日韩", "韩国", "日本"), keywords = listOf("日韩", "韩国", "日本")),
        genre("欧美综艺", listOf("欧美", "美国", "英国"), keywords = listOf("欧美", "美国", "英国")),
        genre("真人秀", listOf("真人秀", "选秀")),
        genre("脱口秀", listOf("脱口秀", "访谈")),
        genre("音乐", listOf("音乐", "演唱会"))
    )

    HomeTab.ANIME -> listOf(
        hot,
        latest,
        classic,
        genre("国产动画", listOf("国产", "国漫", "大陆"), keywords = listOf("国产", "国漫", "大陆", "中国")),
        genre("日漫", listOf("日本", "日漫", "日韩"), keywords = listOf("日本", "日漫")),
        genre("欧美动画", listOf("欧美", "美国", "英国"), keywords = listOf("欧美", "美国", "英国")),
        genre("剧场版", listOf("剧场", "电影"), keywords = listOf("剧场版", "电影版")),
        genre("热血", listOf("热血", "冒险")),
        genre("治愈", listOf("治愈", "日常"))
    )

    HomeTab.DOCUMENTARY -> listOf(
        hot,
        latest,
        classic,
        genre("自然", listOf("自然", "动物", "地理")),
        genre("历史", listOf("历史", "人文")),
        genre("科技", listOf("科技", "科学")),
        genre("美食", listOf("美食", "饮食")),
        genre("军事", listOf("军事", "战争")),
        genre("人物", listOf("人物", "传记"))
    )

    HomeTab.SHORT -> listOf(
        hot,
        latest,
        classic,
        genre("都市", listOf("都市", "现代")),
        genre("甜宠", listOf("甜宠", "爱情")),
        genre("古装", listOf("古装", "穿越")),
        genre("逆袭", listOf("逆袭", "爽剧")),
        genre("悬疑", listOf("悬疑", "推理")),
        genre("重生", listOf("重生", "复仇"))
    )

    HomeTab.HISTORY -> emptyList()
}

/** Whether a genre is purely a re-ordering of the whole tab (no name filter). */
fun HomeGenre.isSortOnly(): Boolean = categoryAliases.isEmpty() && keywords.isEmpty()
