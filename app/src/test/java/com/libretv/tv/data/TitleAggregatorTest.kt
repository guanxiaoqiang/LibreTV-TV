package com.libretv.tv.data

import com.libretv.tv.domain.model.LatencyTier
import com.libretv.tv.domain.model.QualityTier
import com.libretv.tv.domain.model.SourceOffer
import com.libretv.tv.domain.model.TitleGroup
import com.libretv.tv.domain.model.TitleRef
import com.libretv.tv.domain.model.aggregate
import com.libretv.tv.domain.model.detectQuality
import com.libretv.tv.domain.model.formatLatency
import com.libretv.tv.domain.model.latencyTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the search-result folding, quality hints and latency tiers. */
class TitleAggregatorTest {

    private fun ref(
        vodId: String,
        remarks: String = ""
    ) = TitleRef(vodId = vodId, vodRemarks = remarks)

    private fun result(
        source: String,
        name: String,
        remarks: String = "",
        year: String = "2019",
        latency: Long = 0L
    ) = SearchResult(
        sourceName = source,
        item = VodItem(
            vodId = "$source-$name",
            vodName = name,
            vodYear = year,
            vodRemarks = remarks
        ),
        sourceUrl = "https://$source.invalid/api.php/provide/vod",
        latencyMs = latency
    )

    @Test
    fun `same title across sources becomes one group`() {
        val groups = aggregate(
            listOf(
                result("量子", "流浪地球", "HD", latency = 280),
                result("猫眼", "流浪地球", "正片", latency = 220),
                result("红牛", " 流浪地球 ", "HD国语", latency = 610)
            )
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].offers.size)
        assertEquals("HD 高清", groups[0].bestQuality.label)
        assertEquals(220L, groups[0].fastestMs)
    }

    @Test
    fun `sequels stay separate but inconsistent years merge`() {
        val groups = aggregate(
            listOf(
                result("a", "流浪地球", year = "2019"),
                result("b", "流浪地球", year = "2025"),
                result("c", "流浪地球2", year = "2023"),
                result("d", "流浪地球3", year = "2027")
            )
        )
        assertEquals(3, groups.size)
        assertEquals(2, groups.first().offers.size)
    }

    @Test
    fun `commentary and trailer markers fold back into the main title`() {
        val groups = aggregate(
            listOf(
                result("a", "流浪地球"),
                result("b", "流浪地球[电影解说]"),
                result("c", "流浪地球2[预告片]"),
                result("d", "流浪地球2")
            )
        )
        assertEquals(2, groups.size)
        val main = groups.first { it.title == "流浪地球" }
        assertEquals(2, main.offers.size)
        assertEquals(1, main.playableCount)
    }

    @Test
    fun `offers are ordered by quality then latency and trailers sink`() {
        val groups = aggregate(
            listOf(
                result("slow", "某片", "正片", latency = 2400),
                result("fast", "某片", "HD国语", latency = 300),
                result("uhd", "某片", "4K修复", latency = 900),
                result("trailer", "某片", "预告片 4K", latency = 100)
            )
        )
        val first = groups.single()
        assertEquals("uhd", first.offers.first().sourceName)
        assertEquals("fast", first.offers[1].sourceName)
        assertTrue(first.offers.last().isTrailer)
        assertEquals(300L, first.fastestMs)
    }

    @Test
    fun `quality hints come from remarks and play line names`() {
        assertEquals(
            QualityTier.UHD_4K,
            detectQuality(ref("1", remarks = "4K修复"))
        )
        assertEquals(
            QualityTier.FULL_HD,
            detectQuality(ref("2", remarks = "正片"), listOf("蓝光线路"))
        )
        assertEquals(
            QualityTier.HD,
            detectQuality(ref("3", remarks = "HD国语"))
        )
        assertEquals(
            QualityTier.UNKNOWN,
            detectQuality(ref("4"))
        )
    }

    @Test
    fun `latency tiers and formatting`() {
        assertEquals(LatencyTier.FAST, latencyTier(228))
        assertEquals(LatencyTier.SMOOTH, latencyTier(993))
        assertEquals(LatencyTier.SLOW, latencyTier(1895))
        assertEquals(LatencyTier.LAGGY, latencyTier(2685))
        assertEquals(LatencyTier.UNKNOWN, latencyTier(0))
        assertEquals("228 ms", formatLatency(228))
        assertEquals("2.7 s", formatLatency(2685))
    }

    @Test
    fun `measured quality wins over the declared one`() {
        val offer = SourceOffer(
            sourceName = "a",
            sourceUrl = "https://a.invalid",
            vodId = "1",
            title = TitleRef(vodId = "1", vodRemarks = "4K修复"),
            quality = QualityTier.UHD_4K,
            remark = "4K修复",
            latencyMs = 100L,
            isTrailer = false,
            measuredQuality = QualityTier.SD,
            bandwidthBps = 800_000
        )
        // 声明说是 4K，实测只有标清——以实测为准。
        assertEquals(QualityTier.SD, offer.effectiveQuality)
    }

    @Test
    fun `the text hint is the fallback when nothing was measured`() {
        val offer = SourceOffer(
            sourceName = "a",
            sourceUrl = "https://a.invalid",
            vodId = "1",
            title = TitleRef(vodId = "1", vodRemarks = "HD国语"),
            quality = QualityTier.HD,
            remark = "HD国语",
            latencyMs = 100L,
            isTrailer = false
        )
        assertEquals(QualityTier.HD, offer.effectiveQuality)
    }

    @Test
    fun `the group reports the best effective quality`() {
        val group = TitleGroup(
            key = "k",
            title = "某片",
            poster = "",
            year = "2024",
            area = "",
            typeName = "电影",
            offers = listOf(
                SourceOffer(
                    sourceName = "declared4k",
                    sourceUrl = "https://a.invalid",
                    vodId = "1",
                    title = TitleRef(vodId = "1"),
                    quality = QualityTier.UHD_4K,
                    remark = "",
                    latencyMs = 10L,
                    isTrailer = false,
                    measuredQuality = QualityTier.SD
                ),
                SourceOffer(
                    sourceName = "measured1080",
                    sourceUrl = "https://b.invalid",
                    vodId = "2",
                    title = TitleRef(vodId = "2"),
                    quality = QualityTier.UNKNOWN,
                    remark = "",
                    latencyMs = 20L,
                    isTrailer = false,
                    measuredQuality = QualityTier.FULL_HD
                )
            )
        )
        assertEquals(QualityTier.FULL_HD, group.bestQuality)
    }
}
