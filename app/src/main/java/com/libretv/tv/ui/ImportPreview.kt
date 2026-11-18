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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libretv.tv.domain.model.SourceConfig
import com.libretv.tv.domain.model.TvboxParseResult

/**
 * 导入配置的预览。
 *
 * 观众点完「导入配置」看到的第一屏：能导入多少条、哪些没导以及为什么。
 * 跳过条目要写清原因——否则「导入成功但一个源都没多」会让人以为坏了。
 */
@Composable
fun TvboxImportPreview(
    sources: List<SourceConfig>,
    skipped: List<TvboxParseResult.Skipped>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(TvColors.Surface)
                .border(1.dp, TvColors.SurfaceHigh, RoundedCornerShape(14.dp))
                .padding(24.dp)
        ) {
            Text(
                "导入预览",
                color = TvColors.OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "可导入 " + sources.size + " 个源" +
                    if (skipped.isEmpty()) "" else "，跳过 " + skipped.size + " 个",
                color = TvColors.OnSurfaceMuted,
                fontSize = 13.sp
            )

            if (sources.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("将导入", color = TvColors.OnSurfaceMuted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    sources.forEach { source ->
                        Text(
                            source.name,
                            color = TvColors.OnSurface,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (skipped.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("已跳过", color = TvColors.OnSurfaceMuted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    skipped.forEach { item ->
                        Row {
                            Text(
                                item.name,
                                color = TvColors.OnSurface,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(180.dp)
                            )
                            Text(
                                item.reason,
                                color = TvColors.Warning,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ImportButton(
                    label = if (sources.isEmpty()) "没有可导入的源" else "导入 " + sources.size + " 个源",
                    onClick = onConfirm,
                    enabled = sources.isNotEmpty(),
                    primary = true
                )
                ImportButton(label = "取消", onClick = onCancel)
            }
        }
    }
}

/** Small action button of the import preview. */
@Composable
private fun ImportButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false
) {
    FocusableTile(onClick = onClick, enabled = enabled) { f ->
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        !enabled -> TvColors.SurfaceHigh
                        f && primary -> TvColors.Primary
                        f -> TvColors.SurfaceHigh
                        else -> TvColors.Surface
                    }
                )
                .tvFocusable(f, RoundedCornerShape(8.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text(
                label,
                color = when {
                    !enabled -> TvColors.OnSurfaceMuted
                    f && primary -> Color(0xFF14181D)
                    f -> TvColors.Primary
                    else -> TvColors.OnSurface
                },
                fontSize = 13.sp
            )
        }
    }
}
