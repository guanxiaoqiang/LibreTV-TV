package com.libretv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.libretv.tv.domain.model.BadgeTag
import com.libretv.tv.domain.model.BadgeVisibility
import com.libretv.tv.domain.model.FeedBadge
import com.libretv.tv.domain.model.FeedBadgeKind
import com.libretv.tv.domain.model.FeedCard

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
 * Poster card shared by 推荐 / 电影 / 剧集 / ... pages and the search results.
 *
 * Everything lives *inside* the 5:7 artwork: the metadata tags (year, source
 * count, best quality, quickest response) sit above a full-width title bar at
 * the very bottom. Both use the same dark rounded backing colour, so nothing
 * sticks out of the poster when a focused card scales up.
 */
@Composable
fun UnifiedPosterCard(
    card: FeedCard,
    focused: Boolean,
    badgeVisibility: BadgeVisibility = BadgeVisibility(),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
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

        // Scrim behind tags + title so they stay legible on bright artwork.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(96.dp)
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
        // card. The tags stay chips with their own inset; the title below them
        // is not a chip — its backing fills the whole caption strip, so the
        // name reads as one bar under the artwork instead of a floating tag.
        // Every tag has its own switch in the settings, so the block can be
        // trimmed down to just the information the viewer cares about.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                card.badges
                    .filter { badgeVisibility.allows(it.tag) }
                    .chunked(2)
                    .forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { BadgeChip(it) }
                        }
                    }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (focused) Color(0xF2241A20) else Color(0xF0070A0E))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(
                    text = card.title,
                    color = if (focused) TvColors.Primary else TvColors.OnSurface,
                    fontSize = 12.sp,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
