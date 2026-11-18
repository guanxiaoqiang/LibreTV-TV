package com.libretv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.libretv.tv.domain.model.DoubanSubject

/**
 * Douban recommendation shelf.
 *
 * These posters are discovery only: tapping one runs its title through the
 * bundled collection sources to find a playable stream, so the shelf never
 * links to anything outside the app.
 */
@Composable
fun DoubanShelf(
    label: String,
    subjects: List<DoubanSubject>,
    onPick: (DoubanSubject) -> Unit,
    onLabelFocus: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 40.dp, bottom = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 18.dp)
                    .background(TvColors.Primary, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = TvColors.OnSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 40.dp, end = 40.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(subjects, key = { it.id }) { subject ->
                DoubanPoster(subject = subject, onPick = onPick)
            }
        }
    }
}

@Composable
private fun DoubanPoster(
    subject: DoubanSubject,
    onPick: (DoubanSubject) -> Unit
) {
    FocusableTile(onClick = { onPick(subject) }) { f ->
        Column(modifier = Modifier.width(126.dp)) {
            Box(
                modifier = Modifier
                    .width(126.dp)
                    .height(178.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TvColors.SurfaceHigh)
                    .tvFocusable(f, RoundedCornerShape(8.dp))
            ) {
                if (subject.cover.isNotBlank()) {
                    AsyncImage(
                        model = subject.cover,
                        contentDescription = subject.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            subject.title.take(6),
                            color = TvColors.OnSurfaceMuted,
                            fontSize = 12.sp,
                            maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                if (subject.rate.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xDDF5A623))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            subject.rate,
                            color = Color(0xFF1A1A1A),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                subject.title,
                color = if (f) TvColors.Primary else TvColors.OnSurface,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Placeholder shown while the first shelf is still loading. */
@Composable
fun DoubanLoadingHint(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 40.dp, top = 12.dp)
    ) {
        CircularProgressIndicator(
            color = TvColors.Primary,
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(10.dp))
        Text("正在加载豆瓣推荐…", color = TvColors.OnSurfaceMuted, fontSize = 13.sp)
    }
}
