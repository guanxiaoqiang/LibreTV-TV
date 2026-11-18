package com.libretv.tv.domain.usecase

import com.libretv.tv.data.CategoryCatalog
import com.libretv.tv.data.SourceConfig
import com.libretv.tv.data.VodClass
import com.libretv.tv.domain.model.FeedSort
import com.libretv.tv.domain.model.HomeGenre
import com.libretv.tv.domain.model.HomeTab
import com.libretv.tv.domain.model.QualityTier
import com.libretv.tv.domain.model.SourceOffer
import com.libretv.tv.domain.model.TitleGroup
import com.libretv.tv.domain.model.TitleRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用例层的纯函数行为：分类映射、目标解析、关键词过滤、排序。 */
class UseCasesTest {

    private fun cls(id: Int, parent: Int, name: String) = VodClass(id, parent, name)

    // ---------- ClassifyCategories ----------

    @Test
    fun `top level nodes map onto tabs by name and shelves prefer children`() {
        val classes = listOf(
            cls(1, 0, "电影"),
            cls(11, 1, "动作片"),
            cls(12, 1, "科幻片"),
            cls(2, 0, "电视剧"),
            cls(21, 2, "国产剧"),
            cls(3, 0, "综艺"),
            cls(4, 0, "动漫"),
            cls(41, 4, "日漫"),
            cls(5, 0, "纪录片"),
            cls(6, 0, "短剧")
        )
        val tabs = ClassifyCategories(classes)

        assertEquals(listOf("动作片", "科幻片"), tabs[HomeTab.MOVIE]!!.map { it.typeName })
        assertEquals(listOf("国产剧"), tabs[HomeTab.SERIES]!!.map { it.typeName })
        assertTrue(tabs.containsKey(HomeTab.ANIME))
        assertTrue(tabs.containsKey(HomeTab.VARIETY))
        assertTrue(tabs.containsKey(HomeTab.SHORT))
    }

    @Test
    fun `documentary and anime are excluded from the movie shelf`() {
        val classes = listOf(
            cls(1, 0, "电影"),
            cls(11, 1, "纪录片专区"),
            cls(12, 1, "动画片"),
            cls(2, 0, "纪录片")
        )
        val tabs = ClassifyCategories(classes)
        // 子分类里只剩纪录片/动画片：货架退回到顶级「电影」节点兜底。
        assertEquals(listOf("电影"), tabs[HomeTab.MOVIE]!!.map { it.typeName })
        assertTrue(tabs.containsKey(HomeTab.DOCUMENTARY))
    }

    @Test
    fun `a flat tree yields an empty mapping`() {
        assertTrue(ClassifyCategories(emptyList()).isEmpty())
    }

    // ---------- ResolveGenreTargets ----------

    private fun catalog(source: String, classes: List<VodClass>) = CategoryCatalog(
        source = SourceConfig(name = source, url = "https://$source.invalid"),
        classes = classes
    )

    @Test
    fun `a named genre matches each source's own tree without local filtering`() {
        val catalogs = listOf(
            catalog("a", listOf(cls(1, 0, "电影"), cls(11, 1, "科幻片"))),
            catalog("b", listOf(cls(7, 0, "科幻")))
        )
        val (targets, keywordFilter) = ResolveGenreTargets(
            catalogs, HomeTab.MOVIE, HomeGenre("科幻", listOf("科幻"))
        )
        assertFalse(keywordFilter)
        assertEquals(2, targets.size)
    }

    @Test
    fun `a genre no source exposes falls back to defaults with local filtering`() {
        val catalogs = listOf(
            catalog("a", listOf(cls(1, 0, "电影"), cls(11, 1, "动作片")))
        )
        val (targets, keywordFilter) = ResolveGenreTargets(
            catalogs, HomeTab.MOVIE, HomeGenre("西部", listOf("西部"))
        )
        assertTrue("无人匹配时应打开关键词过滤", keywordFilter)
        assertEquals(listOf(11), targets.map { it.second })
    }

    @Test
    fun `sort-only genres use the tab defaults and never filter locally`() {
        val catalogs = listOf(
            catalog("a", listOf(cls(1, 0, "电影"), cls(11, 1, "动作片")))
        )
        val (targets, keywordFilter) = ResolveGenreTargets(
            catalogs, HomeTab.MOVIE, HomeGenre("热门")
        )
        assertFalse(keywordFilter)
        assertEquals(listOf(11), targets.map { it.second })
    }

    // ---------- MatchGenreKeywords ----------

    private fun group(
        title: String,
        typeName: String = "",
        area: String = "",
        remarks: String = "",
        synopsis: String = ""
    ) = TitleGroup(
        key = title,
        title = title,
        poster = "",
        year = "",
        area = area,
        typeName = typeName,
        offers = listOf(
            SourceOffer(
                sourceName = "a",
                sourceUrl = "https://a.invalid",
                vodId = "1",
                title = TitleRef(
                    vodId = "1",
                    vodName = title,
                    typeName = typeName,
                    vodArea = area,
                    vodRemarks = remarks,
                    vodContent = synopsis
                ),
                quality = QualityTier.UNKNOWN,
                remark = remarks,
                latencyMs = 0L,
                isTrailer = false
            )
        )
    )

    @Test
    fun `type name area remarks and synopsis all feed the haystack`() {
        assertTrue(MatchGenreKeywords(group("某片", typeName = "科幻"), listOf("科幻")))
        assertTrue(MatchGenreKeywords(group("某片", area = "美国"), listOf("美国")))
        assertTrue(MatchGenreKeywords(group("某片", remarks = "悬疑惊悚"), listOf("悬疑")))
        assertTrue(MatchGenreKeywords(group("某片", synopsis = "这是一个太空故事"), listOf("太空")))
        assertFalse(MatchGenreKeywords(group("某片", typeName = "爱情"), listOf("科幻")))
    }

    @Test
    fun `keyword matching ignores case`() {
        assertTrue(MatchGenreKeywords(group("某片", typeName = "Sci-Fi"), listOf("sci-fi")))
    }

    // ---------- SortTitleGroups ----------

    private fun sortedGroup(
        title: String,
        year: String = "",
        playable: Int = 1,
        quality: QualityTier = QualityTier.UNKNOWN,
        latency: Long = 0L
    ) = TitleGroup(
        key = title,
        title = title,
        poster = "",
        year = year,
        area = "",
        typeName = "",
        offers = (1..playable).map { i ->
            SourceOffer(
                sourceName = "s$i",
                sourceUrl = "https://s$i.invalid",
                vodId = "$i",
                title = TitleRef(vodId = "$i"),
                quality = quality,
                remark = "",
                latencyMs = latency,
                isTrailer = false
            )
        }
    )

    @Test
    fun `new sort prefers the freshest year then the playable count`() {
        val sorted = SortTitleGroups(
            listOf(
                sortedGroup("old", year = "1999"),
                sortedGroup("new-single", year = "2025"),
                sortedGroup("new-multi", year = "2024", playable = 4)
            ),
            FeedSort.NEW
        )
        assertEquals(listOf("new-single", "new-multi", "old"), sorted.map { it.title })
    }

    @Test
    fun `classic sort puts the oldest real year first`() {
        val sorted = SortTitleGroups(
            listOf(
                sortedGroup("mid", year = "1980"),
                sortedGroup("ancient", year = "1930"),
                sortedGroup("no-year", year = "暂无")
            ),
            FeedSort.CLASSIC
        )
        assertEquals(listOf("ancient", "mid", "no-year"), sorted.map { it.title })
    }

    @Test
    fun `hot sort prefers the most sources then quality then latency`() {
        val sorted = SortTitleGroups(
            listOf(
                sortedGroup("few", playable = 1, quality = QualityTier.UHD_4K),
                sortedGroup("many", playable = 3, quality = QualityTier.SD),
                sortedGroup("many-slow", playable = 3, quality = QualityTier.HD, latency = 5000)
            ),
            FeedSort.HOT
        )
        assertEquals(listOf("many-slow", "many", "few"), sorted.map { it.title })
    }

    @Test
    fun `an unparsable year counts as year zero`() {
        assertEquals(0, SortTitleGroups.yearNumber("暂无"))
        assertEquals(2025, SortTitleGroups.yearNumber("2025-01-01"))
    }
}
