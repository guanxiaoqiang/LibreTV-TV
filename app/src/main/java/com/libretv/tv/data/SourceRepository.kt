package com.libretv.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Loads the bundled source list from assets and performs fan-out search.
 *
 * The bundled list is a snapshot of the user's own desktop LibreTV configuration
 * (30 collection APIs, probed 2026-11-14). Nothing about the app requires a
 * running server.
 */
class SourceRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun loadSources(): List<SourceConfig> = withContext(Dispatchers.IO) {
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
    suspend fun loadTitleIndex(): Map<String, String> = withContext(Dispatchers.IO) {
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
    suspend fun searchAll(
        sources: List<SourceConfig>,
        client: CmsClient,
        keyword: String
    ): List<SearchResult> = coroutineScope {
        sources.map { source ->
            async(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                val items = try {
                    client.search(source.url, keyword)
                } catch (e: Exception) {
                    null
                }
                val latency = System.currentTimeMillis() - start
                items.orEmpty().map { item ->
                    SearchResult(
                        sourceName = source.name,
                        item = item,
                        sourceUrl = source.url,
                        latencyMs = latency
                    )
                }
            }
        }.awaitAll().flatten()
    }

    /**
     * Find one source that exposes a usable category tree.
     *
     * Each collector numbers its categories differently, so the home screen
     * works from a single catalog and maps names onto its own tabs. Probing a
     * handful of sources concurrently keeps this to one network round while
     * still tolerating dead mirrors.
     */
    suspend fun loadCategoryCatalog(
        sources: List<SourceConfig>,
        client: CmsClient
    ): CategoryCatalog? = coroutineScope {
        sources.take(4).map { source ->
            async(Dispatchers.IO) {
                val classes = try {
                    client.categories(source.url)
                } catch (e: Exception) {
                    emptyList()
                }
                source to classes
            }
        }.awaitAll()
            .firstOrNull { (_, classes) ->
                classes.size >= 4 && classes.any { it.parentId == 0 }
            }
            ?.let { (source, classes) -> CategoryCatalog(source, classes) }
    }

    /** Load one page of a category shelf, tagged with the source it came from. */
    suspend fun categoryShelf(
        source: SourceConfig,
        typeId: Int,
        client: CmsClient
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            client.categoryItems(source.url, typeId).map {
                SearchResult(source.name, it, source.url, 0L)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Lightweight probe for one source offer: read its detail once to learn the
     * number of lines / episodes, then time the first byte of the actual stream.
     * Failures are kept as zeroes so a dead source simply sorts lower.
     */
    suspend fun probeOffer(offer: SourceOffer, client: CmsClient): OfferProbe =
        withContext(Dispatchers.IO) {
            val source = SourceConfig(offer.sourceName, offer.sourceUrl)
            val start = System.currentTimeMillis()
            val bundle = try {
                fetchDetail(source, offer.item, client)
            } catch (e: Exception) {
                null
            }
            val detailMs = System.currentTimeMillis() - start
            val lines = bundle?.lines.orEmpty()
            val episodes = lines.firstOrNull()?.episodes.orEmpty()
            val streamUrl = episodes.firstOrNull()?.url.orEmpty()
            val firstByte = if (streamUrl.isNotBlank()) client.probeStream(streamUrl) else null
            OfferProbe(
                lineCount = lines.size,
                episodeCount = episodes.size,
                detailLatencyMs = detailMs,
                firstByteMs = firstByte,
                lineNames = lines.map { it.name }
            )
        }

    /** Probe a single source, used by the source health screen. */
    suspend fun probe(source: SourceConfig, client: CmsClient): Boolean {
        return try {
            val items = client.search(source.url, "test")
            items != null
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fetchDetail(
        source: SourceConfig,
        item: VodItem,
        client: CmsClient
    ): DetailBundle? {
        return try {
            val result = client.detail(source.url, item.vodId)
            if (result == null) {
                DetailBundle(source.name, item, emptyList())
            } else {
                val (full, playRaw) = result
                DetailBundle(source.name, full, client.parsePlayLines(playRaw))
            }
        } catch (e: Exception) {
            null
        }
    }
}

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
