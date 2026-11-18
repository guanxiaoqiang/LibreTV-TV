package com.libretv.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 详情兜底顺序的规则锚点：去重、剔除主来源、按延迟升序。 */
class DetailFallbackOrderTest {

    private fun offer(
        name: String,
        url: String,
        vodId: String = "4021",
        latencyMs: Long,
        isTrailer: Boolean = false
    ) = SourceOffer(
        sourceName = name,
        sourceUrl = url,
        vodId = vodId,
        title = TitleRef(vodId = vodId, vodName = "流浪地球"),
        quality = QualityTier.HD,
        remark = "",
        latencyMs = latencyMs,
        isTrailer = isTrailer
    )

    private val primary = offer("片库", "http://a", latencyMs = 500L).toSearchResult()

    @Test
    fun `primary source is excluded and the rest are sorted by latency`() {
        val offers = listOf(
            offer("云播", "http://c", latencyMs = 900L),
            primary.sourceName.let { offer(it, "http://a", latencyMs = 100L) }, // 主来源本身
            offer("豆瓣源", "http://b", latencyMs = 200L)
        )
        val order = fallbackDetailOrder(primary, offers)
        assertEquals(listOf("豆瓣源", "云播"), order.map { it.sourceName })
    }

    @Test
    fun `same source and vod id but different url still counts as fallback`() {
        // 镜像地址包装：源名相同、vodId 相同、URL 不同 → 不能被误剔。
        val mirror = offer("片库", "http://mirror/?url=http://a", latencyMs = 50L)
        val order = fallbackDetailOrder(primary, listOf(mirror))
        assertEquals(listOf(mirror.sourceUrl), order.map { it.sourceUrl })
    }

    @Test
    fun `trailer offers stay in the tail of the order`() {
        val real = offer("云播", "http://c", latencyMs = 900L)
        val trailer = offer("预告", "http://t", latencyMs = 10L, isTrailer = true)
        val order = fallbackDetailOrder(primary, listOf(real, trailer))
        // 兜底只看延迟：预告片排在最前也没关系，拿不到线路才会轮到它。
        assertEquals(listOf("预告", "云播"), order.map { it.sourceName })
    }

    @Test
    fun `empty or single-source groups yield no fallback`() {
        assertTrue(fallbackDetailOrder(primary, emptyList()).isEmpty())
        assertTrue(
            fallbackDetailOrder(primary, listOf(offer("片库", "http://a", latencyMs = 1L)))
                .isEmpty()
        )
    }
}
