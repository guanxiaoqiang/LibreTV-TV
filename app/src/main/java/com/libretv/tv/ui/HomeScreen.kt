package com.libretv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage

/**
 * A focusable tile that renders its own focus state.
 *
 * Two things here matter on a living-room TV:
 *
 *  - The focused tile is drawn last (`zIndex`). In a LazyRow the next card is
 *    composited on top of the previous one, so without this the enlarged card
 *    is partly covered by its neighbour and the focus looks broken.
 *  - The scale lives on this outer box, *before* the card's own `clip`. Scaling
 *    inside a clipped layer gets clipped straight back to the original bounds.
 */
@Composable
fun FocusableTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scaleWhenFocused: Float = 1.09f,
    onLongPress: (() -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .zIndex(if (focused) 10f else 0f)
            .scale(if (focused) scaleWhenFocused else 1f)
            .onFocusChanged { state -> focused = state.isFocused }
            .focusableItem(
                enabled = enabled,
                onLongPress = onLongPress,
                onClick = onClick
            )
    ) {
        content(focused)
    }
}

/**
 * Navigation entry on the left rail.
 *
 * Focus is a single focus box: a filled background plus one accent ring. No
 * accent bars or underlines — the box alone is loud enough on a TV.
 */
@Composable
fun NavItem(
    label: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector = Icons.Filled.PlayArrow
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            // Narrower rail: the old 178 dp column pushed the content grid too
            // far right and wasted a lot of TV width on empty label space.
            .width(152.dp)
            .height(42.dp)
            .clip(shape)
            .background(if (focused) TvColors.SurfaceHigh else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) TvColors.FocusRing else Color.Transparent,
                shape = shape
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (focused) TvColors.Primary else TvColors.Surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (focused) Color(0xFF14181D) else TvColors.OnSurfaceMuted,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(
                text = label,
                color = when {
                    focused -> Color.White
                    selected -> TvColors.Primary
                    else -> TvColors.OnSurfaceMuted
                },
                fontSize = if (focused) 15.sp else 14.sp,
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Poster card used in the result grid. Focus and click are owned by the parent. */
@Composable
fun PosterCard(
    title: String,
    poster: String,
    remarks: String,
    sourceName: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    cornerBadge: String? = null,
    footerNote: String? = null
) {
    // A Column, not a Box: the title must sit *below* the poster. Overlaying
    // it on top of the artwork collides with the year / quality badges and is
    // unreadable on a TV once the poster finally loads.
    Column(
        modifier = modifier
            .width(158.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(TvColors.Surface)
            .tvFocusable(focused, RoundedCornerShape(10.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(224.dp)
                .background(TvColors.SurfaceHigh)
        ) {
            if (poster.isNotBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        title.take(6),
                        color = TvColors.OnSurfaceMuted,
                        fontSize = 14.sp,
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }
            }
            if (remarks.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(remarks, color = Color.White, fontSize = 11.sp)
                }
            }
            if (cornerBadge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xDD0E1116))
                        .border(1.dp, TvColors.Primary, RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(cornerBadge, color = TvColors.Primary, fontSize = 11.sp)
                }
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                text = title,
                color = if (focused) TvColors.Primary else TvColors.OnSurface,
                fontSize = 14.sp,
                fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = footerNote ?: sourceName,
                color = TvColors.OnSurfaceMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Section header. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TvColors.OnSurface,
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(vertical = 8.dp)
    )
}
