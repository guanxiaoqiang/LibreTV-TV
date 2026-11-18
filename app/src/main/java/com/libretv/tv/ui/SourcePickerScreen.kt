package com.libretv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.libretv.tv.domain.model.LatencyTier
import com.libretv.tv.domain.model.OfferProbe
import com.libretv.tv.domain.model.QualityTier
import com.libretv.tv.domain.model.SourceOffer
import com.libretv.tv.domain.model.TitleGroup
import com.libretv.tv.domain.model.formatLatency
import com.libretv.tv.domain.model.latencyTier

fun qualityColor(tier: QualityTier): Color = when (tier) {
    QualityTier.UHD_4K -> Color(0xFF8B7BF0)
    QualityTier.FULL_HD -> TvColors.Primary
    QualityTier.HD -> TvColors.Success
    QualityTier.SD -> TvColors.Info
    QualityTier.UNKNOWN -> TvColors.OnSurfaceMuted
}

fun latencyColor(tier: LatencyTier): Color = when (tier) {
    LatencyTier.FAST -> TvColors.Success
    LatencyTier.SMOOTH -> TvColors.Info
    LatencyTier.SLOW -> TvColors.Warning
    LatencyTier.LAGGY -> TvColors.Danger
    LatencyTier.UNKNOWN -> TvColors.OnSurfaceMuted
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Compare every available source of one title before playing.
 *
 * The quality badge comes from the collector's own remarks / line names; the
 * latency badge is the measured API round trip, and the first-byte / line and
 * episode counts are filled in by an asynchronous probe. The first row is the
 * recommended default (best quality, then quickest).
 */
@Composable
fun SourcePickerScreen(
    group: TitleGroup,
    probes: Map<String, OfferProbe>,
    onPick: (SourceOffer) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 26.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FocusableTile(onClick = onBack) { f ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (f) TvColors.Primary else TvColors.SurfaceHigh)
                        .tvFocusable(f, RoundedCornerShape(8.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        "返回",
                        color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                        fontSize = 14.sp
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${group.title} · 选择来源",
                    color = TvColors.OnSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "已聚合 ${group.offers.size} 个源 · 画质按源站标注推断，延迟为实测响应时间",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(modifier = Modifier.weight(1f)) {
            // Left: poster and metadata, pinned while the offer list scrolls.
            Column(modifier = Modifier.width(200.dp)) {
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .height(280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TvColors.SurfaceHigh)
                ) {
                    if (group.poster.isNotBlank()) {
                        AsyncImage(
                            model = group.poster,
                            contentDescription = group.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                listOf(
                    "年份" to group.year,
                    "地区" to group.area,
                    "类型" to group.typeName,
                    "最佳画质" to group.bestQuality.label,
                    "最快响应" to formatLatency(group.fastestMs)
                ).forEach { (label, value) ->
                    if (value.isNotBlank() && value != "延迟未知") {
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(
                                label,
                                color = TvColors.OnSurfaceMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.width(64.dp)
                            )
                            Text(
                                value,
                                color = TvColors.OnSurface,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(28.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // The key carries the slot as well as the offer identity: two
                // collectors can publish the same name + vod id, and a repeated
                // LazyColumn key is fatal rather than merely ugly.
                itemsIndexed(
                    group.offers,
                    key = { index, offer -> "${offer.key}#$index" }
                ) { index, offer ->
                    val recommended = index == 0 && !offer.isTrailer
                    FocusableTile(onClick = { onPick(offer) }) { focused ->
                        OfferRow(
                            offer = offer,
                            probe = probes[offer.key],
                            recommended = recommended,
                            focused = focused
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferRow(
    offer: SourceOffer,
    probe: OfferProbe?,
    recommended: Boolean,
    focused: Boolean
) {
    val qColor = qualityColor(offer.quality)
    val lTier = latencyTier(offer.latencyMs)
    val lColor = latencyColor(lTier)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) TvColors.SurfaceHigh else TvColors.Surface)
            .tvFocusable(focused, RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                offer.sourceName,
                color = if (focused) Color.White else TvColors.OnSurface,
                fontSize = 16.sp,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (recommended) {
                Badge("推荐", TvColors.Primary)
                Spacer(Modifier.width(8.dp))
            }
            if (offer.isTrailer) {
                Badge("预告 / 非正片", TvColors.Warning)
                Spacer(Modifier.width(8.dp))
            }
            Badge(offer.quality.label, qColor)
        }

        Spacer(Modifier.height(9.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge("${lTier.label} · ${formatLatency(offer.latencyMs)}", lColor)
            Spacer(Modifier.width(8.dp))
            when {
                probe == null -> Badge("正在探测线路…", TvColors.OnSurfaceMuted)
                probe.firstByteMs != null -> Badge(
                    "首包 ${formatLatency(probe.firstByteMs!!)}",
                    latencyColor(latencyTier(probe.firstByteMs!!))
                )
                else -> Badge("首包未测到", TvColors.OnSurfaceMuted)
            }
            Spacer(Modifier.width(8.dp))
            if (probe != null && probe.lineCount > 0) {
                Text(
                    "${probe.lineCount} 条线路 · ${probe.episodeCount} 集",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                offer.remark.ifBlank { "无备注" },
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
