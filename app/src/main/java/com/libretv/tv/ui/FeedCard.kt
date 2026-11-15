package com.libretv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.libretv.tv.data.DoubanSubject
import com.libretv.tv.data.TitleGroup

/** Colour role of a small tag drawn on the poster. */
enum class FeedBadgeKind { NEUTRAL, ACCENT, QUALITY, LATENCY }

data class FeedBadge(val text: String, val kind: FeedBadgeKind = FeedBadgeKind.NEUTRAL)

/**
 * One card in the unified home grid.
 *
 * Douban discoveries and all-source aggregated titles share exactly the same
 * poster + title layout; only the badges differ. [group] is set for aggregated
 * titles (multi-source -> picker, single-source -> detail), [subject] for
 * Douban discoveries (tap -> search every bundled source for that title).
 */
data class FeedCard(
    val key: String,
    val title: String,
    val poster: String,
    val badges: List<FeedBadge> = emptyList(),
    val cornerBadge: String? = null,
    val group: TitleGroup? = null,
    val subject: DoubanSubject? = null
)

internal fun shortLatency(ms: Long): String = when {
    ms <= 0L -> "延时未知"
    ms < 1000L -> "$ms ms"
    else -> "%.1f s".format(ms / 1000.0)
}

/**
 * The one canonical renderer data for an aggregated title. Both the home feed
 * and the search results call this, so a card looks identical everywhere and
 * the year / source count / best quality / quickest latency tags never drift.
 */
fun TitleGroup.asFeedCard(): FeedCard = FeedCard(
    key = "g|$key",
    title = title,
    poster = poster,
    badges = listOf(
        FeedBadge(year.ifBlank { "年份未知" }, FeedBadgeKind.NEUTRAL),
        FeedBadge("$playableCount 源", FeedBadgeKind.ACCENT),
        FeedBadge(bestQuality.short, FeedBadgeKind.QUALITY),
        FeedBadge(shortLatency(fastestMs), FeedBadgeKind.LATENCY)
    ),
    group = this
)

private fun badgeColor(kind: FeedBadgeKind): Color = when (kind) {
    FeedBadgeKind.NEUTRAL -> Color(0xFFE6EDF3)
    FeedBadgeKind.ACCENT -> TvColors.Primary
    FeedBadgeKind.QUALITY -> Color(0xFFFFC46B)
    FeedBadgeKind.LATENCY -> Color(0xFF7FD1FF)
}

@Composable
private fun BadgeChip(badge: FeedBadge) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xD10B0E13))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = badge.text,
            color = badgeColor(badge.kind),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

/**
 * Poster card shared by 推荐 / 电影 / 剧集 / ... pages.
 *
 * The artwork keeps the 5:7 shape of the original Douban recommendation card,
 * but the metadata (release year, source count, best quality, quickest
 * response) now lives in tags *on* the poster instead of a text row below it.
 * That keeps all grids visually identical no matter where the titles came from.
 */
@Composable
fun UnifiedPosterCard(
    card: FeedCard,
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.70f)
                .clip(RoundedCornerShape(9.dp))
                .background(TvColors.SurfaceHigh)
                .tvFocusable(focused, RoundedCornerShape(9.dp))
        ) {
            if (card.poster.isNotBlank()) {
                AsyncImage(
                    model = card.poster,
                    contentDescription = card.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        card.title.take(6),
                        color = TvColors.OnSurfaceMuted,
                        fontSize = 13.sp,
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Scrim behind the tags so they stay legible on bright artwork.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(74.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xB8070A0E), Color(0xEE070A0E))
                        )
                    )
            )

            if (card.cornerBadge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xDDF5A623))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        card.cornerBadge,
                        color = Color(0xFF1A1A1A),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Two badges per row keeps four metadata tags readable on a 150 dp
            // card without covering the whole poster.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                card.badges.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { BadgeChip(it) }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = card.title,
            color = if (focused) TvColors.Primary else TvColors.OnSurface,
            fontSize = 13.sp,
            fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
