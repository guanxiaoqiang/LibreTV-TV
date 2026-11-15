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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libretv.tv.data.SearchResult
import com.libretv.tv.data.TitleGroup
import com.libretv.tv.data.aggregate
import com.libretv.tv.data.formatLatency

/**
 * Search results, folded by title.
 *
 * A fan-out search can return the same movie from ten APIs. Instead of ten
 * near-identical posters the viewer sees one card per title, badged with how
 * many sources carry it, the best quality hint and the quickest response time.
 * Opening a card goes to the source picker.
 */
@Composable
fun ResultScreen(
    keyword: String,
    results: List<SearchResult>,
    searching: Boolean,
    onOpenGroup: (TitleGroup) -> Unit,
    onBack: () -> Unit
) {
    val groups = remember(results) { aggregate(results) }
    val sourceCount = remember(results) { results.map { it.sourceName }.distinct().size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "「$keyword」的结果",
                    color = TvColors.OnSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (searching) "正在搜索…"
                    else "已聚合 ${groups.size} 部 · ${results.size} 条资源 · 来自 $sourceCount 个源（按画质与延迟择优）",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 13.sp
                )
            }
            FocusableTile(onClick = onBack) { f ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (f) TvColors.SurfaceHigh else TvColors.Surface)
                        .tvFocusable(f, RoundedCornerShape(8.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        "返回",
                        color = if (f) TvColors.Primary else TvColors.OnSurface,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (searching && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TvColors.Primary)
            }
            return@Column
        }

        if (!searching && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("未找到相关内容", color = TvColors.OnSurface, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "可尝试更换关键词，或在「源管理」中检查失效的源",
                        color = TvColors.OnSurfaceMuted,
                        fontSize = 13.sp
                    )
                }
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(158.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(groups, key = { it.key }) { group ->
                FocusableTile(onClick = { onOpenGroup(group) }) { f ->
                    PosterCard(
                        title = group.title,
                        poster = group.poster,
                        remarks = group.year.ifBlank { group.typeName },
                        sourceName = "",
                        cornerBadge = "${group.offers.size} 源",
                        footerNote = "${group.bestQuality.short} · ${formatLatency(group.fastestMs)}",
                        focused = f
                    )
                }
            }
        }
    }
}
