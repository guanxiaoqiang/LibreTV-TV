package com.libretv.tv.domain.model

/**
 * 候选来源 → 采集源配置。
 *
 * 选源页只关心「哪个站、哪个地址」，所以这一步不需要额外的查表。
 */
fun SourceOffer.asSource(): SourceConfig = SourceConfig(
    name = sourceName,
    url = sourceUrl
)

/**
 * [SourceOffer] → [SearchResult]：详情页只需要身份与失败兜底用的元信息。
 */
fun SourceOffer.toSearchResult(): SearchResult = SearchResult(
    sourceName = sourceName,
    item = VodItem(
        vodId = title.vodId,
        vodName = title.vodName,
        vodPic = title.vodPic,
        vodYear = title.vodYear,
        vodArea = title.vodArea,
        typeName = title.typeName,
        vodRemarks = title.vodRemarks,
        vodContent = title.vodContent,
        // 带上条目自带的播放地址：详情接口失灵时它就是兜底线索。
        vodPlayUrl = title.vodPlayUrl
    ),
    sourceUrl = sourceUrl,
    latencyMs = latencyMs
)

/**
 * 把实测画质写回候选来源。
 *
 * 采集站写在备注里的清晰度只是声明，实测到的分辨率才可信；实测失败时保留
 * 文本推断值。
 */
fun TitleGroup.withMeasuredQuality(probes: Map<String, OfferProbe>): TitleGroup =
    copy(
        offers = offers.map { offer ->
            val probe = probes[offer.key] ?: return@map offer
            offer.copy(
                measuredQuality = probe.measuredQuality,
                bandwidthBps = probe.bandwidthBps
            )
        }
    )

/**
 * 详情兜底顺序：主来源详情拿不到线路时，按这条顺序逐个退到同组来源。
 *
 * 与主来源同源同片的条目剔除（重试它没有意义），其余按实测延迟升序——
 * 详情请求也快不到哪去，快源优先。
 */
fun fallbackDetailOrder(primary: SearchResult, offers: List<SourceOffer>): List<SearchResult> =
    offers.asSequence()
        .filterNot { it.sourceUrl == primary.sourceUrl && it.vodId == primary.item.vodId }
        .sortedBy { it.latencyMs }
        .map { it.toSearchResult() }
        .toList()
