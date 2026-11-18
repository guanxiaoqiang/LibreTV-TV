package com.libretv.tv.ui

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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libretv.tv.domain.model.SkipKind
import com.libretv.tv.domain.model.SkipMark

/**
 * 跳过标记管理页。
 *
 * 观众在这里能看到自己标过哪些片子、每部片子的哪些区间会被跳过，并逐条
 * 删除。删掉之后标记立刻失效——这条规则在设置规格里写死了。
 */
@Composable
fun SkipMarksScreen(
    marks: List<SkipMark>,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 44.dp, vertical = 26.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "跳过标记",
                    color = TvColors.OnSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "共 ${marks.size} 部影片",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 13.sp
                )
            }
            Text(
                "按遥控器返回键返回",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(18.dp))

        if (marks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "还没有标记过片头或片尾\n在播放页按「标记」按钮即可添加",
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
            items(marks, key = { it.titleKey }) { mark ->
                SkipMarkRow(mark = mark, onDelete = { onDelete(mark.titleKey) })
            }
        }
    }
}

/** One marked title. */
@Composable
private fun SkipMarkRow(mark: SkipMark, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TvColors.Surface)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                mark.title.ifBlank { mark.titleKey },
                color = TvColors.OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                mark.segments.joinToString("　") { segment ->
                    kindLabel(segment.kind) + " " + formatMs(segment.startMs) + " - " + formatMs(segment.endMs)
                },
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(16.dp))
        FocusableTile(onClick = onDelete) { f ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (f) TvColors.SurfaceHigh else TvColors.SurfaceHigh)
                    .border(
                        width = if (f) 1.dp else 0.dp,
                        color = if (f) TvColors.FocusRing else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .tvFocusable(f, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    "删除",
                    color = if (f) Color.White else TvColors.OnSurfaceMuted,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/** 区间类型的短标签。 */
private fun kindLabel(kind: SkipKind): String = when (kind) {
    SkipKind.INTRO -> "片头"
    SkipKind.MIDDLE -> "中段"
    SkipKind.OUTRO -> "片尾"
}

/** 毫秒 → 分:秒。 */
internal fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
