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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.libretv.tv.domain.model.DetailBundle
import com.libretv.tv.domain.model.SourceOffer
import com.libretv.tv.domain.model.formatLatency
import com.libretv.tv.domain.model.latencyTier

@Composable
fun DetailScreen(
    detail: DetailBundle?,
    loading: Boolean,
    error: String?,
    activeLine: Int,
    onSelectLine: (Int) -> Unit,
    onPlay: (lineIndex: Int, episodeIndex: Int) -> Unit,
    offer: SourceOffer? = null,
    onSwitchSource: (() -> Unit)? = null,
    alternatives: List<SourceOffer> = emptyList(),
    onPickAlternative: ((SourceOffer) -> Unit)? = null,
    /** 是否已收藏（按归一化片名，跨来源同步）。 */
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null
) {
    if (detail == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (loading) "正在获取详情…" else (error ?: "暂无详情"),
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 16.sp
                )
            }
        }
        return
    }

    val item = detail.item
    val lines = detail.lines

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp)
    ) {
        // Left: poster + metadata
        Column(Modifier.width(280.dp)) {
            Box(
                modifier = Modifier
                    .width(240.dp)
                    .height(340.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TvColors.SurfaceHigh)
            ) {
                if (item.vodPic.isNotBlank()) {
                    AsyncImage(
                        model = item.vodPic,
                        contentDescription = item.vodName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 不放返回按钮：遥控器返回键直接退回上一页。
            Text(
                "按遥控器返回键返回",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.width(36.dp))

        // Right: metadata + lines + episodes
        Column(Modifier.weight(1f)) {
            Text(
                item.vodName,
                color = TvColors.OnSurface,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                listOf(
                    item.vodYear.takeIf { it.isNotBlank() },
                    item.vodArea.takeIf { it.isNotBlank() },
                    item.typeName.takeIf { it.isNotBlank() },
                    "来源 ${detail.sourceName}"
                ).filterNotNull().joinToString(" · "),
                color = TvColors.OnSurfaceMuted,
                fontSize = 13.sp
            )
            if (offer != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(offer.quality.label, qualityColor(offer.quality))
                    Spacer(Modifier.width(8.dp))
                    Badge(
                        "${latencyTier(offer.latencyMs).label} · ${formatLatency(offer.latencyMs)}",
                        latencyColor(latencyTier(offer.latencyMs))
                    )
                    if (onSwitchSource != null) {
                        Spacer(Modifier.width(8.dp))
                        FocusableTile(onClick = onSwitchSource) { f ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (f) TvColors.Primary else TvColors.SurfaceHigh)
                                    .tvFocusable(f, RoundedCornerShape(5.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "切换源",
                                    color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                cleanSynopsis(item.vodContent),
                color = TvColors.OnSurfaceMuted,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(20.dp))

            if (lines.isEmpty()) {
                Text(
                    error ?: "该源未提供播放信息",
                    color = TvColors.Danger,
                    fontSize = 14.sp
                )
                // 没有线路不该是死胡同：把同片的其它源直接摆出来，
                // 观众点一下就换源重试，不用退回搜索结果重来。
                if (alternatives.isNotEmpty() && onPickAlternative != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "试试这些源（点击切换）",
                        color = TvColors.OnSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(10.dp))
                    alternatives.forEach { alt ->
                        FocusableTile(onClick = { onPickAlternative(alt) }) { f ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (f) TvColors.Primary else TvColors.Surface)
                                    .tvFocusable(f, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    alt.sourceName,
                                    color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                                    fontSize = 14.sp,
                                    fontWeight = if (f) FontWeight.Medium else FontWeight.Normal
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${alt.quality.label} · ${formatLatency(alt.latencyMs)}",
                                    color = if (f) Color(0xFF14181D) else TvColors.OnSurfaceMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                return@Column
            }

            // 收藏按钮（4.6）：收藏状态按归一化片名跨来源同步，取消即时生效。
            if (onToggleFavorite != null) {
                FocusableTile(onClick = onToggleFavorite) { f ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isFavorite && f -> TvColors.Primary
                                    isFavorite -> TvColors.Primary.copy(alpha = 0.75f)
                                    f -> TvColors.SurfaceHigh
                                    else -> TvColors.Surface
                                }
                            )
                            .tvFocusable(f, RoundedCornerShape(8.dp))
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                    ) {
                        Text(
                            if (isFavorite) "★ 已收藏" else "☆ 收藏",
                            color = if (isFavorite) Color(0xFF14181D) else TvColors.OnSurface,
                            fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Text("播放线路（左右键切换）", color = TvColors.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(lines.size) { i ->
                    val selected = i == activeLine
                    FocusableTile(onClick = { onSelectLine(i) }) { f ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        f -> TvColors.Primary
                                        selected -> TvColors.SurfaceHigh
                                        else -> TvColors.Surface
                                    }
                                )
                                .tvFocusable(f, RoundedCornerShape(8.dp))
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                lines[i].name,
                                color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                                fontSize = 14.sp,
                                fontWeight = if (f) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            val episodes = lines.getOrNull(activeLine)?.episodes.orEmpty()
            Text(
                "选集（${episodes.size}）",
                color = TvColors.OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(episodes.size) { i ->
                    FocusableTile(onClick = { onPlay(activeLine, i) }) { f ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (f) TvColors.Primary else TvColors.Surface)
                                .tvFocusable(f, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                episodes[i].name,
                                color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Collectors routinely wrap the plot in `<p>` and glue a comma-separated SEO
 * keyword list onto the end ("...生死之战艾旦影视,海外影院,海外影视,...").
 * Strip the markup and drop that tail so the TV detail page stays readable.
 */
private fun cleanSynopsis(raw: String): String {
    val text = raw
        .replace(Regex("""<[^>]*>"""), " ")
        .replace(Regex("""&(nbsp|amp|quot|#\d+);"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (text.isEmpty()) return "暂无简介"
    val firstLatinComma = text.indexOf(',')
    if (firstLatinComma > 10 && text.substring(firstLatinComma).count { it == ',' } >= 5) {
        return text.substring(0, firstLatinComma).trim().take(180)
    }
    return text.take(180)
}
