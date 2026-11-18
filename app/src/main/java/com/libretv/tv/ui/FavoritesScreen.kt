package com.libretv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.libretv.tv.domain.model.FavoriteEntry

/**
 * 收藏列表页（favorites spec / 4.5）。
 *
 * 网格化海报 + 标题 + 收藏时间。焦点移动不触发详情请求（TV 带宽紧张），
 * 只有真正点击条目才进入详情/选源；取消收藏即时生效并跨页面同步。
 */
@Composable
fun FavoritesScreen(
    favorites: List<FavoriteEntry>,
    onOpen: (FavoriteEntry) -> Unit,
    onRemove: (FavoriteEntry) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 44.dp, vertical = 26.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "我的收藏",
                    color = TvColors.OnSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "共 ${favorites.size} 部",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有收藏\n在详情页按「收藏」即可加入",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 14.sp
                )
            }
            return
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(favorites, key = { it.titleKey }) { entry ->
                FavoriteRow(entry = entry, onOpen = { onOpen(entry) }, onRemove = { onRemove(entry) })
            }
        }
    }
}

/** One favorite: poster + title + time; focus does not preload detail. */
@Composable
private fun FavoriteRow(
    entry: FavoriteEntry,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TvColors.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 海报仅作展示，不预加载详情；AsyncImage 只取图片本身。
        Box(
            modifier = Modifier
                .width(88.dp)
                .height(124.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TvColors.SurfaceHigh)
        ) {
            if (entry.poster.isNotBlank()) {
                AsyncImage(
                    model = entry.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title.ifBlank { entry.titleKey },
                color = TvColors.OnSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "收藏于 ${formatFavoriteTime(entry.createdAt)}" +
                    if (entry.sourceName.isNotBlank()) "　·　${entry.sourceName}" else "",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        FocusableTile(onClick = onOpen, scaleWhenFocused = 1.02f) { focused ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) TvColors.Primary else TvColors.SurfaceHigh)
                    .border(
                        width = if (focused) 1.dp else 0.dp,
                        color = TvColors.FocusRing,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    "播放",
                    color = if (focused) Color(0xFF14181D) else TvColors.OnSurface,
                    fontSize = 13.sp
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        FocusableTile(onClick = onRemove, scaleWhenFocused = 1.02f) { focused ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) TvColors.SurfaceHigh else TvColors.Surface)
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    "取消收藏",
                    color = if (focused) TvColors.Danger else TvColors.OnSurfaceMuted,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/** 收藏时间：今天显示时分，否则显示日期。 */
private fun formatFavoriteTime(ms: Long): String {
    if (ms <= 0L) return "未知时间"
    val fmt = java.text.SimpleDateFormat(
        if (ms > System.currentTimeMillis() - 86_400_000L) "HH:mm" else "yyyy-MM-dd",
        java.util.Locale.getDefault()
    )
    return fmt.format(java.util.Date(ms))
}
