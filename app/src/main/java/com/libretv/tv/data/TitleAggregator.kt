package com.libretv.tv.data

/**
 * Grouping the same movie across collection sources.
 *
 * A search fans out to all bundled APIs, so one title can easily come back ten
 * times with slightly different names, posters and play lines. Showing ten
 * identical posters is useless on a TV. This layer folds them into one card,
 * keeps every playable source as an "offer", and attaches the two facts a
 * viewer actually needs to choose: the quality hint the collector published
 * and how slow that collector was.
 */
enum class QualityTier(val label: String, val short: String, val rank: Int) {
    UHD_4K("4K 超清", "4K", 5),
    FULL_HD("1080P 全高清", "1080P", 4),
    HD("HD 高清", "HD", 3),
    SD("标清", "标清", 2),
    UNKNOWN("未标注", "未标注", 1)
}

enum class LatencyTier(val label: String) {
    FAST("极速"),
    SMOOTH("流畅"),
    SLOW("较慢"),
    LAGGY("很慢"),
    UNKNOWN("未知")
}

fun latencyTier(ms: Long): LatencyTier = when {
    ms <= 0L -> LatencyTier.UNKNOWN
    ms < 500L -> LatencyTier.FAST
    ms < 1200L -> LatencyTier.SMOOTH
    ms < 2500L -> LatencyTier.SLOW
    else -> LatencyTier.LAGGY
}

fun formatLatency(ms: Long): String = when {
    ms <= 0L -> "延迟未知"
    ms < 1000L -> "$ms ms"
    else -> "%.1f s".format(ms / 1000.0)
}

/**
 * One playable copy of a title on one source.
 *
 * [latencyMs] is the measured round trip of that source's search request;
 * [firstByteMs], when present, is added later by a stream probe.
 */
data class SourceOffer(
    val sourceName: String,
    val sourceUrl: String,
    val vodId: String,
    val item: VodItem,
    val quality: QualityTier,
    val remark: String,
    val latencyMs: Long,
    val isTrailer: Boolean,
    val firstByteMs: Long? = null,
    val lineCount: Int = 0,
    val episodeCount: Int = 0
) {
    /** Stable identity inside a group, used as the key for async probes. */
    val key: String get() = "$sourceName|$vodId"
}

data class TitleGroup(
    val key: String,
    val title: String,
    val poster: String,
    val year: String,
    val area: String,
    val typeName: String,
    val offers: List<SourceOffer>
) {
    val bestQuality: QualityTier
        get() = offers.maxByOrNull { it.quality.rank }?.quality ?: QualityTier.UNKNOWN

    /** Quickest playable copy; trailers are not a real choice for watching. */
    val fastestMs: Long
        get() = offers.filterNot { it.isTrailer }
            .mapNotNull { it.latencyMs.takeIf { ms -> ms > 0L } }
            .minOrNull() ?: 0L

    val playableCount: Int get() = offers.count { !it.isTrailer }
}

/** Result of a lightweight detail + stream probe for one offer. */
data class OfferProbe(
    val lineCount: Int,
    val episodeCount: Int,
    val detailLatencyMs: Long,
    val firstByteMs: Long?,
    val lineNames: List<String>
)

private val TRAILER_MARKERS = listOf("预告", "解说", "花絮", "片段", "片花", "抢先", "混剪")

/**
 * Quality hints come from three places because no collector is consistent:
 * `vod_remarks` ("HD国语", "4K修复"), `vod_play_from` ("4kline$$$lzm3u8")
 * and the parsed line names ("蓝光线路").
 */
fun detectQuality(item: VodItem, lineNames: List<String> = emptyList()): QualityTier {
    val text = buildString {
        append(item.vodRemarks).append(' ').append(item.vodPlayFrom).append(' ')
        append(item.vodName).append(' ')
        lineNames.forEach { append(it).append(' ') }
    }.uppercase()

    return when {
        text.contains("4K") || text.contains("2160") || text.contains("UHD") ||
            text.contains("超清") -> QualityTier.UHD_4K
        text.contains("1080") || text.contains("蓝光") || text.contains("FHD") ||
            Regex("""\bBD\b""").containsMatchIn(text) -> QualityTier.FULL_HD
        text.contains("HD") || text.contains("720") || text.contains("高清") ||
            text.contains("高码") -> QualityTier.HD
        text.contains("正片") || text.contains("完结") || text.contains("DVD") ||
            text.contains("TC") -> QualityTier.SD
        else -> QualityTier.UNKNOWN
    }
}

private fun isTrailer(item: VodItem): Boolean {
    val text = item.vodName + " " + item.vodRemarks
    return TRAILER_MARKERS.any { text.contains(it) }
}

/**
 * Collectors append editorial markers rather than using a metadata field.
 * Stripping them folds "某片[电影解说]" and "某片（预告片）" back into the
 * real title, where they are kept as low-priority offers rather than separate
 * posters cluttering the grid.
 */
private val TRAILER_SUFFIX = Regex(
    """[\[\(（]?\s*(电影解说|解说|预告片|片花|花絮|抢先看|抢先版)\s*[\]\)）]?"""
)

/** Loose enough to merge whitespace / punctuation differences, strict enough to keep sequels apart. */
private fun normalizeTitle(raw: String): String = raw
    .replace(TRAILER_SUFFIX, "")
    .replace(Regex("""[\s\u3000]+"""), "")
    .replace('（', '(').replace('）', ')')
    .replace('：', ':').replace('［', '[').replace('］', ']')
    .lowercase()
    .trim()

/**
 * Fold a fan-out search result into title groups.
 *
 * Offers are ordered with the best quality first, then the quickest source,
 * so the first row is always the sensible default. Trailers are kept but sunk
 * to the bottom, and never counted as a playable copy.
 */
fun aggregate(results: List<SearchResult>): List<TitleGroup> {
    val buckets = linkedMapOf<String, MutableList<SearchResult>>()
    for (result in results) {
        val title = normalizeTitle(result.item.vodName)
        if (title.isBlank()) continue
        // Year is deliberately NOT part of the key: several collectors put
        // their ingest year in vod_year, so the same film would otherwise show
        // up three times with 2019 / 2025 / 2027 labels.
        buckets.getOrPut(title) { mutableListOf() }.add(result)
    }

    return buckets.map { (key, raw) ->
        val playable = raw.filterNot { isTrailer(it.item) }.ifEmpty { raw }
        val representative = playable.maxByOrNull { r ->
            (if (r.item.vodPic.isNotBlank()) 2 else 0) +
                (if (r.item.vodContent.isNotBlank()) 1 else 0) +
                (if (r.item.vodRemarks.isNotBlank()) 1 else 0)
        }?.item ?: playable.first().item

        val offers = raw.map { r ->
            SourceOffer(
                sourceName = r.sourceName,
                sourceUrl = r.sourceUrl,
                vodId = r.item.vodId,
                item = r.item,
                quality = detectQuality(r.item),
                remark = r.item.vodRemarks.ifBlank { r.item.vodPlayFrom },
                latencyMs = r.latencyMs,
                isTrailer = isTrailer(r.item)
            )
        }.sortedWith(
            compareBy<SourceOffer> { it.isTrailer }
                .thenByDescending { it.quality.rank }
                .thenBy { if (it.latencyMs > 0L) it.latencyMs else Long.MAX_VALUE }
        )

        TitleGroup(
            key = key,
            title = representative.vodName.trim(),
            poster = representative.vodPic,
            year = representative.vodYear,
            area = representative.vodArea,
            typeName = representative.typeName,
            offers = offers
        )
    }
}
