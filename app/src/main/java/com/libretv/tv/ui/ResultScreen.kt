package com.libretv.tv.ui

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libretv.tv.domain.model.BadgeVisibility
import com.libretv.tv.domain.model.SearchResult
import com.libretv.tv.domain.model.TitleGroup
import com.libretv.tv.domain.model.FeedCard
import com.libretv.tv.domain.model.aggregate
import com.libretv.tv.domain.model.asFeedCard

/**
 * Search results, folded by title and drawn with the exact same card as the
 * home feed: 5:7 poster, a bottom title bar with the same chip backing as
 * the year / source count / best quality / quickest latency tags.
 *
 * A fan-out search can return the same movie from ten APIs. Instead of ten
 * near-identical posters the viewer sees one card per title. Opening a card
 * goes to the source picker (or straight to the detail page for one source).
 */
@Composable
fun ResultScreen(
    keyword: String,
    results: List<SearchResult>,
    searching: Boolean,
    badgeVisibility: BadgeVisibility = BadgeVisibility(),
    onOpenGroup: (TitleGroup) -> Unit
) {
    val groups = remember(results) { aggregate(results) }
    val cards = remember(groups) { groups.map { it.asFeedCard() } }
    val sourceCount = remember(results) { results.map { it.sourceName }.distinct().size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 22.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 44.dp, end = 40.dp, bottom = 4.dp),
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
            // 页面里不放返回按钮：遥控器的返回键直接退回上一页，
            // 焦点落在卡片上时按返回键也不会误触。
            Text(
                "按遥控器返回键返回",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(8.dp))

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
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(
                // Top inset gives the 1.09x focused card room to grow without
                // being clipped by the grid viewport, exactly like the home page.
                start = 44.dp, end = 40.dp, top = 20.dp, bottom = 40.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(cards, key = { it.key }) { card ->
                FocusableTile(onClick = { card.group?.let(onOpenGroup) }) { focused ->
                    UnifiedPosterCard(
                        card = card,
                        focused = focused,
                        badgeVisibility = badgeVisibility,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
