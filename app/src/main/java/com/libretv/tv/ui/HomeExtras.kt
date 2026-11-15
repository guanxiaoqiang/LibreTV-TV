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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.libretv.tv.data.SearchResult
import com.libretv.tv.data.WatchRecord

/** Card size shared by the shelf and the full history grid. */
private val HISTORY_CARD_WIDTH = 260.dp
private val HISTORY_CARD_HEIGHT = 146.dp

private fun formatWatchTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * The fixed tab strip at the top of the home screen.
 *
 * Two TV-specific behaviours live here:
 *
 *  - Moving the focus onto a tab switches to it immediately. A select press is
 *    no longer required, which is how Android TV category rails are expected
 *    to behave. Touch taps still work through the tile's click path.
 *  - A search chip is pinned to the right of the strip at all times, so the
 *    viewer can reach search without walking back to the left rail.
 *
 * `down` is left to spatial focus so the D-pad can walk into the shelves.
 */
@Composable
fun CategoryTabRow(
    tabs: List<HomeTab>,
    selected: HomeTab,
    requesters: List<FocusRequester>,
    railRequester: FocusRequester,
    onSelect: (HomeTab) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val searchRequester = remember { FocusRequester() }
    val lastIndex = tabs.lastIndex

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 36.dp, top = 6.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tab ->
            FocusableTile(
                onClick = { onSelect(tab) },
                scaleWhenFocused = 1.07f,
                modifier = Modifier
                    .focusRequester(requesters[index])
                    .focusProperties {
                        previous = requesters[(index - 1 + tabs.size) % tabs.size]
                        next = if (index == lastIndex) searchRequester
                        else requesters[(index + 1) % tabs.size]
                        left = if (index == 0) railRequester
                        else requesters[(index - 1 + tabs.size) % tabs.size]
                        right = if (index == lastIndex) searchRequester
                        else requesters[(index + 1) % tabs.size]
                    }
            ) { focused ->
                // Focus alone is the selection on a TV. The effect is keyed on
                // `focused` so a recomposition caused by the tab switch itself
                // cannot start a second network load.
                LaunchedEffect(focused) {
                    if (focused) onSelect(tab)
                }
                val active = tab == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            when {
                                focused -> TvColors.Primary
                                active -> TvColors.SurfaceHigh
                                else -> TvColors.Surface
                            }
                        )
                        .tvFocusable(focused, RoundedCornerShape(20.dp))
                        .padding(horizontal = 17.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        color = when {
                            focused -> Color(0xFF14181D)
                            active -> TvColors.Primary
                            else -> TvColors.OnSurfaceMuted
                        },
                        fontSize = 14.sp,
                        fontWeight = if (active || focused) FontWeight.Bold else FontWeight.Normal
                    )
                    if (active && !focused) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .width(22.dp)
                                .height(3.dp)
                                .background(TvColors.Primary, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        FocusableTile(
            onClick = onSearch,
            scaleWhenFocused = 1.08f,
            modifier = Modifier
                .focusRequester(searchRequester)
                .focusProperties {
                    left = requesters[lastIndex]
                    previous = requesters[lastIndex]
                    right = requesters.first()
                    next = requesters.first()
                }
        ) { focused ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (focused) TvColors.Primary else TvColors.SurfaceHigh)
                    .tvFocusable(focused, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = if (focused) Color(0xFF14181D) else TvColors.Primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "搜索",
                    color = if (focused) Color(0xFF14181D) else TvColors.OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** Small heading with the accent bar, shared by all shelves. */
@Composable
fun ShelfTitle(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 40.dp, end = 40.dp, bottom = 8.dp, top = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 18.dp)
                .background(TvColors.Primary, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            color = TvColors.OnSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** One shelf of real, playable titles from the collector's category tree. */
@Composable
fun CategoryShelf(
    row: CategoryRow,
    onPick: (SearchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ShelfTitle("${row.label} · ${row.items.size} 部")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            lazyItems(row.items, key = { "${row.label}-${it.sourceName}-${it.item.vodId}" }) { result ->
                FocusableTile(onClick = { onPick(result) }) { focused ->
                    PosterCard(
                        title = result.item.vodName,
                        poster = result.item.vodPic,
                        remarks = result.item.vodRemarks,
                        sourceName = result.sourceName,
                        focused = focused
                    )
                }
            }
        }
    }
}

/** A 16:9 continue-watching card with a progress bar burned into the bottom. */
@Composable
fun HistoryCard(
    record: WatchRecord,
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    val percent = record.progressPercent()
    Column(modifier = modifier.width(HISTORY_CARD_WIDTH)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HISTORY_CARD_HEIGHT)
                .clip(RoundedCornerShape(8.dp))
                .background(TvColors.SurfaceHigh)
                .tvFocusable(focused, RoundedCornerShape(8.dp))
        ) {
            if (record.poster.isNotBlank()) {
                AsyncImage(
                    model = record.poster,
                    contentDescription = record.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        record.title.take(8),
                        color = TvColors.OnSurfaceMuted,
                        fontSize = 13.sp,
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (focused) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xCC0E1116))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text("继续播放", color = TvColors.Primary, fontSize = 12.sp)
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(0x88000000))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((percent / 100f).coerceAtLeast(0.01f))
                        .height(4.dp)
                        .background(TvColors.Primary)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = record.title,
            color = if (focused) TvColors.Primary else TvColors.OnSurface,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = buildString {
                append(record.episodeName.ifBlank { "正片" })
                append(" · ")
                append(
                    if (percent > 0) "已看 ${formatWatchTime(record.positionMs)} / ${formatWatchTime(record.durationMs)}"
                    else "从头播放"
                )
            },
            color = TvColors.OnSurfaceMuted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The top shelf on the recommend tab. */
@Composable
fun HistoryShelf(
    records: List<WatchRecord>,
    onResume: (WatchRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        ShelfTitle("继续观看 · ${records.size} 条")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            lazyItems(records, key = { it.key }) { record ->
                FocusableTile(onClick = { onResume(record) }) { focused ->
                    HistoryCard(record = record, focused = focused)
                }
            }
        }
    }
}

/** Full history tab: a grid of resumable cards plus per-item removal. */
@Composable
fun HistoryPage(
    records: List<WatchRecord>,
    onResume: (WatchRecord) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 8.dp, bottom = 14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "历史播放记录",
                    color = TvColors.OnSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "点击任意记录从上次位置继续播放 · 共 ${records.size} 条",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 13.sp
                )
            }
            if (records.isNotEmpty()) {
                FocusableTile(onClick = onClear) { focused ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (focused) TvColors.SurfaceHigh else TvColors.Surface)
                            .tvFocusable(focused, RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Text(
                            "清空记录",
                            color = if (focused) TvColors.Danger else TvColors.OnSurfaceMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "还没有播放记录，去看一部片子吧",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 15.sp
                )
            }
            return
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(HISTORY_CARD_WIDTH + 12.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            gridItems(records, key = { it.key }) { record ->
                Column {
                    FocusableTile(onClick = { onResume(record) }) { focused ->
                        HistoryCard(record = record, focused = focused)
                    }
                    Spacer(Modifier.height(6.dp))
                    FocusableTile(onClick = { onDelete(record.key) }) { focused ->
                        Box(
                            modifier = Modifier
                                .width(HISTORY_CARD_WIDTH)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (focused) TvColors.SurfaceHigh else TvColors.Surface)
                                .tvFocusable(focused, RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "移除这条记录",
                                color = if (focused) TvColors.Danger else TvColors.OnSurfaceMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Shared empty / loading hint for category tabs. */
@Composable
fun CategoryHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, color = TvColors.OnSurfaceMuted, fontSize = 14.sp)
    }
}
