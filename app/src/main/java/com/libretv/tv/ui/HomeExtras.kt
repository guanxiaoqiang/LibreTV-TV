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
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.libretv.tv.data.TitleGroup
import com.libretv.tv.data.WatchRecord
import com.libretv.tv.data.formatLatency

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

/**
 * One shelf of titles aggregated across every source. A card represents one
 * film, no matter how many collectors carry it; the badge shows the offer count
 * and the footer shows the best quality / quickest response.
 */
@Composable
fun CategoryShelf(
    label: String,
    groups: List<TitleGroup>,
    onPick: (TitleGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        ShelfTitle(label)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            lazyItems(groups, key = { it.key }) { group ->
                FocusableTile(onClick = { onPick(group) }) { focused ->
                    PosterCard(
                        title = group.title,
                        poster = group.poster,
                        remarks = group.year.ifBlank { group.typeName },
                        sourceName = "",
                        cornerBadge = "${group.playableCount} 源",
                        footerNote = "${group.bestQuality.short} · ${formatLatency(group.fastestMs)}",
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

/** Full history tab: a grid of resumable cards. */
@Composable
fun HistoryPage(
    records: List<WatchRecord>,
    onResume: (WatchRecord) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    leftTarget: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    // Only the left-most column goes back to the rail slot the viewer came
    // from; other columns keep their normal in-grid LEFT navigation.
    val firstColumnIndices: Set<Int> by remember(gridState) {
        derivedStateOf<Set<Int>> {
            val visible = gridState.layoutInfo.visibleItemsInfo
            val leftX = visible.minOfOrNull { it.offset.x } ?: return@derivedStateOf emptySet()
            visible.filter { it.offset.x == leftX }.map { it.index }.toSet()
        }
    }

    // The removal entry is hidden by default. A long press on the card or the
    // remote's MENU key opens a small confirmation overlay for that one
    // record; the grid itself stays clean.
    var pendingDelete by remember { mutableStateOf<WatchRecord?>(null) }
    // Which card owns the focus right now — the MENU key acts on it.
    var focusedKey by remember { mutableStateOf<String?>(null) }

    // BACK closes the removal overlay first; only when no overlay is up does
    // the normal "leave the tab" behaviour apply.
    BackHandler(enabled = pendingDelete != null) {
        pendingDelete = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // The remote's MENU key opens the removal overlay for the focused
            // card. Pressing it again closes the overlay.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Menu || event.key == Key.Settings || event.key == Key.Function)
                ) {
                    val current = pendingDelete
                    if (current != null) {
                        pendingDelete = null
                        true
                    } else {
                        val key = focusedKey
                        val target = records.firstOrNull { it.key == key }
                        if (target != null) {
                            pendingDelete = target
                            true
                        } else {
                            false
                        }
                    }
                } else {
                    false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 44.dp, end = 40.dp, top = 8.dp, bottom = 14.dp)
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
                        "点击从上次位置继续播放 · 长按卡片或按菜单键可删除该条记录 · 共 ${records.size} 条",
                        color = TvColors.OnSurfaceMuted,
                        fontSize = 13.sp
                    )
                }
                if (records.isNotEmpty()) {
                    val rail = leftTarget
                    FocusableTile(
                        onClick = onClear,
                        modifier = Modifier.focusProperties {
                            if (rail != null) left = rail
                        }
                    ) { focused ->
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
                return@Column
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(HISTORY_CARD_WIDTH + 12.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 44.dp, end = 40.dp, top = 12.dp, bottom = 40.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItemsIndexed(records, key = { _, record -> record.key }) { index, record ->
                    val rail = leftTarget
                    val toRail = Modifier.focusProperties {
                        if (rail != null && index in firstColumnIndices) left = rail
                    }
                    FocusableTile(
                        onClick = { onResume(record) },
                        onLongPress = { pendingDelete = record },
                        modifier = toRail.onFocusChanged { state ->
                            if (state.isFocused) focusedKey = record.key
                        }
                    ) { focused ->
                        HistoryCard(record = record, focused = focused)
                    }
                }
            }
        }

        // MENU / 长按 confirmation overlay.
        pendingDelete?.let { target ->
            HistoryDeleteOverlay(
                record = target,
                onConfirm = {
                    onDelete(target.key)
                    pendingDelete = null
                },
                    onDismiss = { pendingDelete = null }
            )
        }
    }
}

/**
 * Small modal that asks whether the highlighted record should be removed.
 * The overlay owns the focus while it is up, so the D-pad stays inside it
 * and BACK dismisses it instead of leaving the history tab.
 */
@Composable
private fun HistoryDeleteOverlay(
    record: WatchRecord,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val deleteRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC0B0E12)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(TvColors.Surface)
                .padding(20.dp)
        ) {
            Text(
                "删除这条记录？",
                color = TvColors.OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                record.title,
                color = TvColors.OnSurfaceMuted,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusableTile(
                    onClick = onConfirm,
                    modifier = Modifier
                        .focusRequester(deleteRequester)
                        .focusProperties { right = cancelRequester }
                ) { focused ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (focused) TvColors.Danger else TvColors.SurfaceHigh)
                            .tvFocusable(focused, RoundedCornerShape(8.dp))
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "删除",
                            color = if (focused) Color.White else TvColors.Danger,
                            fontSize = 14.sp
                        )
                    }
                }
                FocusableTile(
                    onClick = onDismiss,
                    modifier = Modifier
                        .focusRequester(cancelRequester)
                        .focusProperties { left = deleteRequester }
                ) { focused ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (focused) TvColors.SurfaceHigh else TvColors.Surface)
                            .tvFocusable(focused, RoundedCornerShape(8.dp))
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "取消",
                            color = if (focused) TvColors.Primary else TvColors.OnSurfaceMuted,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(record.key) {
        runCatching { deleteRequester.requestFocus() }
    }
}

/** Shared empty / loading hint for category tabs. */
@Composable
fun CategoryHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 44.dp, end = 40.dp, top = 24.dp, bottom = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, color = TvColors.OnSurfaceMuted, fontSize = 14.sp)
    }
}
