package com.libretv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libretv.tv.domain.model.SkipEdge
import com.libretv.tv.domain.model.SkipKind
import com.libretv.tv.domain.model.SkipMark
import com.libretv.tv.domain.model.SkipSegment

/**
 * 标记面板。
 *
 * 观众在这里做四件事：标片头 / 片尾 / 中段，微调边界，试跳看看效果，以及
 * 撤销刚才那次标记。每一步都直接作用于当前播放位置——「这里是片头结束」
 * 这句话只有在当前播放位置才有意义。
 */
@Composable
fun SkipMarkSheet(
    title: String,
    positionMs: Long,
    durationMs: Long,
    mark: SkipMark?,
    onMarkIntro: () -> Unit,
    onMarkOutro: () -> Unit,
    onMarkMiddle: (Long, Long) -> Unit,
    onAdjust: (SkipSegment, SkipEdge, Long) -> Unit,
    onUndo: (SkipKind) -> Unit,
    onPreview: (SkipSegment) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 中段区间需要两个端点：先记起点，再在另一个位置结束。
    var middleStartMs by remember { mutableStateOf<Long?>(null) }
    val segments = mark?.segments.orEmpty()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(TvColors.Surface)
                .border(1.dp, TvColors.SurfaceHigh, RoundedCornerShape(14.dp))
                .padding(24.dp)
        ) {
            Text(
                "跳过标记 · $title",
                color = TvColors.OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "当前位置 ${formatMs(positionMs)} / ${formatMs(durationMs)}",
                color = TvColors.OnSurfaceMuted,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(16.dp))

            if (segments.isEmpty()) {
                Text(
                    "还没有标记。选一个「片头结束 / 片尾开始 / 中段区间」，播放到那里时就会自动跳过去。",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 13.sp
                )
            } else {
                segments.forEach { segment ->
                    SkipSegmentRow(
                        segment = segment,
                        onAdjust = { edge, delta -> onAdjust(segment, edge, delta) },
                        onUndo = { onUndo(segment.kind) },
                        onPreview = { onPreview(segment) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("标记当前位置", color = TvColors.OnSurfaceMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("片头结束", onClick = onMarkIntro)
                SheetButton("片尾开始", onClick = onMarkOutro)
                SheetButton(
                    label = middleStartMs?.let { "中段结束" } ?: "中段起点",
                    onClick = {
                        val start = middleStartMs
                        if (start == null) {
                            middleStartMs = positionMs
                        } else {
                            onMarkMiddle(start, positionMs)
                            middleStartMs = null
                        }
                    }
                )
            }
            if (middleStartMs != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "中段起点已记在 ${formatMs(middleStartMs!!)}，再选一次「中段结束」确定区间",
                    color = TvColors.Primary,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("关闭", onClick = onClose, primary = true)
            }
        }
    }
}

/** One marked segment with its boundary controls. */
@Composable
private fun SkipSegmentRow(
    segment: SkipSegment,
    onAdjust: (SkipEdge, Long) -> Unit,
    onUndo: () -> Unit,
    onPreview: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TvColors.SurfaceHigh.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                skipKindLabel(segment.kind),
                color = TvColors.OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "${formatMs(segment.startMs)} - ${formatMs(segment.endMs)}",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
        }
        SheetButton("起点-5s", onClick = { onAdjust(SkipEdge.START, -5_000L) })
        SheetButton("起点+5s", onClick = { onAdjust(SkipEdge.START, 5_000L) })
        SheetButton("终点-5s", onClick = { onAdjust(SkipEdge.END, -5_000L) })
        SheetButton("终点+5s", onClick = { onAdjust(SkipEdge.END, 5_000L) })
        SheetButton("试跳", onClick = onPreview)
        SheetButton("撤销", onClick = onUndo)
    }
}

/** 区间类型标签。 */
internal fun skipKindLabel(kind: SkipKind): String = when (kind) {
    SkipKind.INTRO -> "片头"
    SkipKind.MIDDLE -> "中段"
    SkipKind.OUTRO -> "片尾"
}

/** A small action button of the mark sheet. */
@Composable
private fun SheetButton(label: String, onClick: () -> Unit, primary: Boolean = false) {
    FocusableTile(onClick = onClick) { f ->
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        f && primary -> TvColors.Primary
                        f -> TvColors.SurfaceHigh
                        else -> TvColors.Surface
                    }
                )
                .tvFocusable(f, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                label,
                color = when {
                    f && primary -> Color(0xFF14181D)
                    f -> TvColors.Primary
                    else -> TvColors.OnSurface
                },
                fontSize = 12.sp
            )
        }
    }
}
