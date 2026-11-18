package com.libretv.tv.data

import android.content.Context
import com.libretv.tv.core.ApiResult
import com.libretv.tv.core.FanOut
import com.libretv.tv.core.Logger
import com.libretv.tv.core.SourceError
import com.libretv.tv.core.userMessage
import com.libretv.tv.data.remote.CmsApi
import com.libretv.tv.data.remote.DoubanApi
import com.libretv.tv.data.remote.HttpEngine
import com.libretv.tv.data.remote.ProbeEngine
import com.libretv.tv.data.remote.parsePlayLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the bundled source list from assets and performs fan-out search.
 *
 * The bundled list is a snapshot of the user's own desktop LibreTV configuration
 * (30 collection APIs, probed 2026-11-14). Nothing about the app requires a
 * running server.
 */
class SourceRepository(
    private val context: Context,
    /** 采集源 API：构造注入，方法不再接收 client 参数。 */
    private val cms: CmsApi,
    /** 豆瓣发现 API。 */
    private val douban: DoubanApi
) : com.libretv.tv.domain.gateway.SourceGateway,
    com.libretv.tv.domain.gateway.ContentGateway {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * 分类树的短期缓存。
     *
     * 探活（`?ac=list`）与目录采集读的是同一棵树，缓存后同一轮里不会对同一个
     * 源发两次相同的请求。
     */
    private val categoryCache = ConcurrentHashMap<String, CachedCategories>()

    private class CachedCategories(val classes: List<VodClass>, val expiresAt: Long)

    /** 流探活引擎：区分 HLS 与直链，HLS 会一路读到第一个分片。 */
    private val probeEngine = ProbeEngine()

    private companion object {
        const val TAG = "SourceRepository"

        /** 分类树缓存 10 分钟。 */
        const val CATEGORY_CACHE_TTL_MS = 10 * 60 * 1000L
    }

    override suspend fun loadSources(): List<SourceConfig> = withContext(Dispatchers.IO) {
        try {
            val text = context.assets.open("sources.json").bufferedReader().use { it.readText() }
            json.decodeFromString<BundledSources>(text).sources
                .filter { it.url.isNotBlank() }
                .map { it.copy(url = unwrap(it.url)) }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Some entries in the bundled list are mirror wrappers of the form
     * `https://mirror.example/?url=<real api url>`. The mirror host is often dead
     * while the wrapped Apple CMS endpoint still works, so unwrap it up front.
     */
    private fun unwrap(url: String): String = unwrapSourceUrl(url)

    /**
     * Bundled pinyin-initial index: "ldqd" -> "流浪地球".
     * Lets a TV remote reach a Chinese title without an on-screen keyboard.
     */
    override suspend fun loadTitleIndex(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val text = context.assets.open("title_index.json").bufferedReader().use { it.readText() }
            json.decodeFromString<Map<String, String>>(text)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Search every source concurrently and time each round trip.
     *
     * The measured latency travels with the result all the way to the source
     * picker, where it is shown next to the quality hint. It is free information
     * (one clock read per source) and a surprisingly good predictor of how long
     * a title takes to start on a TV.
     */
    override suspend fun searchAll(
        sources: List<SourceConfig>,
        keyword: String
    ): List<SearchResult> {
        val started = System.currentTimeMillis()
        val results = FanOut.map(sources) { source ->
            val start = System.currentTimeMillis()
            val items = cms.search(source, keyword).getOrNull()
            val latency = System.currentTimeMillis() - start
            items.orEmpty().map { item ->
                SearchResult(
                    sourceName = source.name,
                    item = item,
                    sourceUrl = source.url,
                    latencyMs = latency
                )
            }.takeIf { it.isNotEmpty() }
        }.flatten()
        Logger.i(
            TAG,
            "搜索「$keyword」：${sources.size} 个源，命中 ${results.size} 条，" +
                "耗时 ${System.currentTimeMillis() - started}ms"
        )
        return results
    }

    /**
     * 豆瓣发现条带：直接代理给注入的 [DoubanApi]，失败由调用方按空条带处理。
     */
    override suspend fun doubanRecommendations(
        tag: String,
        pageSize: Int,
        type: String,
        start: Int
    ): com.libretv.tv.core.ApiResult<List<com.libretv.tv.data.remote.DoubanSubject>> =
        douban.recommendations(tag, pageSize, type, start)

    /**
     * 读取某个源的分类树，命中短期缓存时直接复用。
     */
    private suspend fun categoriesOf(source: SourceConfig): List<VodClass> {
        val cached = categoryCache[source.url]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            return cached.classes
        }
        val classes = cms.categories(source).getOrNull().orEmpty()
        if (classes.isEmpty()) {
            Logger.d(TAG, "读取分类树失败：${source.name}")
        }
        if (classes.isNotEmpty()) {
            categoryCache[source.url] =
                CachedCategories(classes, System.currentTimeMillis() + CATEGORY_CACHE_TTL_MS)
        }
        return classes
    }

    /**
     * Read the category tree of *every* bundled source, in parallel.
     *
     * Category ids are local to each collector, so a tab can only be fanned out
     * to all sources after each source's own name -> id mapping is known. The
     * result is cached by the view model; sources without a usable tree are
     * skipped instead of blocking the whole tab.
     */
    override suspend fun allCategoryCatalogs(
        sources: List<SourceConfig>
    ): List<CategoryCatalog> {
        val started = System.currentTimeMillis()
        // A dead collector can otherwise hold the whole fan-out for three
        // consecutive 18 s call timeouts, so one tree probe is capped at 8 s.
        val catalogs = FanOut.map(sources, perItemTimeoutMs = 8_000L) { source ->
            val classes = categoriesOf(source)
            if (classes.size >= 4 && classes.any { it.parentId == 0 }) {
                CategoryCatalog(source, classes)
            } else {
                null
            }
        }
        Logger.i(
            TAG,
            "读取分类树：${sources.size} 个源，可用 ${catalogs.size} 个，" +
                "耗时 ${System.currentTimeMillis() - started}ms"
        )
        return catalogs
    }

    /**
     * Load one page of a category shelf, tagged with the source it came from
     * and the round-trip time. The latency feeds the same quality/latency
     * badges used on the search results grid.
     */
    override suspend fun categoryShelf(
        source: SourceConfig,
        typeId: Int,
        page: Int
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val items = cms.categoryItems(source, typeId, page).getOrNull().orEmpty()
        val latency = System.currentTimeMillis() - start
        items.map { SearchResult(source.name, it, source.url, latency) }
    }

    /**
     * Lightweight probe for one source offer: read its detail once to learn the
     * number of lines / episodes, then time the first byte of the actual stream.
     * Failures are kept as zeroes so a dead source simply sorts lower.
     */
    override suspend fun probeOffer(offer: SourceOffer): OfferProbe =
        withContext(Dispatchers.IO) {
            val source = offer.asSource()
            val start = System.currentTimeMillis()
            val detail = cms.detail(source, offer.vodId).getOrNull()
            val detailMs = System.currentTimeMillis() - start
            // 兜底同 fetchDetail：详情没给线路时用条目里自带的播放地址，
            // 否则探测拿不到流地址，选源页只能显示「首包未测到」。
            val lines = detail?.let { parsePlayLines(it.playUrl) }.orEmpty()
                .ifEmpty { parsePlayLines(offer.title.vodPlayUrl) }
            val episodes = lines.firstOrNull()?.episodes.orEmpty()
            val streamUrl = episodes.firstOrNull()?.url.orEmpty()
            // HLS 会一路读到第一个分片，直链只读首字节；实测画质也来自这里。
            val probe = if (streamUrl.isNotBlank()) probeEngine.probe(streamUrl) else null
            OfferProbe(
                lineCount = lines.size,
                episodeCount = episodes.size,
                detailLatencyMs = detailMs,
                firstByteMs = probe?.firstByteMs,
                lineNames = lines.map { it.name },
                measuredQuality = probe?.quality?.tier?.takeIf { it != QualityTier.UNKNOWN },
                bandwidthBps = probe?.bandwidthBps?.takeIf { it > 0 }
            )
        }

    /** Probe a single source, used by the source health screen. */
    suspend fun probe(source: SourceConfig): Boolean =
        probeWithLatency(source).reachable

    /**
     * Test one source and report both reachability and round-trip time.
     *
     * The source settings page shows this per row, so the measurement has to be
     * cheap and side-effect free: one `?ac=list` request against the collector,
     * timed. Using the category list instead of a keyword search keeps the
     * probe out of the collector's search statistics, and the tree it returns
     * is reused by the catalog loader.
     */
    override suspend fun probeWithLatency(source: SourceConfig): SourceHealth {
        val started = System.currentTimeMillis()
        return when (val result = cms.probe(source)) {
            is ApiResult.Ok -> {
                val elapsed = System.currentTimeMillis() - started
                val classes = result.value
                if (classes.isNotEmpty()) {
                    categoryCache[source.url] =
                        CachedCategories(classes, System.currentTimeMillis() + CATEGORY_CACHE_TTL_MS)
                }
                SourceHealth(
                    reachable = true,
                    latencyMs = elapsed,
                    state = HealthState.HEALTHY
                )
            }
            is ApiResult.Err -> {
                val elapsed = System.currentTimeMillis() - started
                SourceHealth(
                    reachable = false,
                    latencyMs = elapsed,
                    error = result.error.userMessage,
                    lastError = result.error,
                    state = HealthState.COOLDOWN
                )
            }
        }
    }

    /** Test every source at once and return one health record per source. */
    override suspend fun probeAll(
        sources: List<SourceConfig>
    ): Map<String, SourceHealth> = FanOut.map(sources) { source ->
        source.url to probeWithLatency(source)
    }.toMap()

    override suspend fun fetchDetail(
        source: SourceConfig,
        item: VodItem
    ): DetailBundle {
        val detail = cms.detail(source, item.vodId).getOrNull()
        // 详情请求失败或没解析出线路时，退回列表响应里自带的播放地址：
        // maccms 的搜索 / 分类响应本来就带 `vod_play_url`，别让观众
        // 明明能播却看到一个「无播放信息」的详情页。
        val lines = detail?.let { parsePlayLines(it.playUrl) }.orEmpty()
            .ifEmpty { parsePlayLines(item.vodPlayUrl) }
        val merged = detail?.item ?: item
        return DetailBundle(source.name, merged, lines)
    }
}

/** 探活/详情只需要一个最小身份。 */
fun SourceOffer.asSource(): SourceConfig = SourceConfig(
    name = sourceName,
    url = sourceUrl
)

/**
 * Some entries in the bundled list are mirror wrappers of the form
 * `https://mirror.example/?url=<real api url>`. The mirror host is often dead
 * while the wrapped Apple CMS endpoint still works, so unwrap it up front.
 */
fun unwrapSourceUrl(url: String): String {
    val idx = url.indexOf("?url=")
    if (idx < 0) return url
    val inner = url.substring(idx + 5)
    val amp = inner.indexOf('&')
    val candidate = if (amp > 0) inner.substring(0, amp) else inner
    return if (candidate.startsWith("http")) candidate else url
}
