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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libretv.tv.domain.model.BackupMergeOptions
import com.libretv.tv.domain.model.BackupMergePlan

/**
 * 备份导入的预览。
 *
 * 导入是破坏性操作，所以先把差异摊开给观众看：会新增什么、会因为重复跳过
 * 什么、哪些东西默认不动。历史与收藏默认**不覆盖**本地已有数据——那是观众
 * 自己的资产，要换也得观众自己点头。
 */
@Composable
fun BackupImportPreview(
    plan: BackupMergePlan,
    options: BackupMergeOptions,
    onOptionsChange: (BackupMergeOptions) -> Unit,
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
                .width(640.dp)
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
            Spacer(Modifier.height(10.dp))
            Text(
                "新增源 ${plan.addedSourceCount} 个" +
                    "，重复跳过 ${plan.duplicateSourceCount} 个" +
                    "，新增订阅 ${plan.addedSubscriptionCount} 个",
                color = TvColors.OnSurfaceMuted,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "新增历史 ${plan.addedHistoryCount} 条，新增收藏 ${plan.addedFavoriteCount} 条",
                color = TvColors.OnSurfaceMuted,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(16.dp))
            Text("合并方式", color = TvColors.OnSurfaceMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            BackupOptionRow(
                label = "用备份覆盖本地观看历史",
                value = options.overwriteHistory,
                onToggle = { onOptionsChange(options.copy(overwriteHistory = !options.overwriteHistory)) }
            )
            BackupOptionRow(
                label = "用备份覆盖本地收藏",
                value = options.overwriteFavorites,
                onToggle = { onOptionsChange(options.copy(overwriteFavorites = !options.overwriteFavorites)) }
            )
            BackupOptionRow(
                label = "同时导入应用设置",
                value = options.importSettings,
                onToggle = { onOptionsChange(options.copy(importSettings = !options.importSettings)) }
            )

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackupButton(label = "确认导入", onClick = onConfirm, primary = true)
                BackupButton(label = "取消", onClick = onCancel)
            }
        }
    }
}

/** One checkbox-like row of the import preview. */
@Composable
private fun BackupOptionRow(label: String, value: Boolean, onToggle: () -> Unit) {
    FocusableTile(onClick = onToggle) { f ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(22.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (value) TvColors.Primary else TvColors.SurfaceHigh)
                    .border(
                        width = 1.dp,
                        color = if (value) TvColors.Primary else TvColors.SurfaceHigh,
                        shape = RoundedCornerShape(5.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (value) {
                    Text("✓", color = Color(0xFF14181D), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                color = if (f) Color.White else TvColors.OnSurface,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * 备份读不出来时的提示。
 *
 * 说不清原因就没法让人处理：版本不支持、文件不是 JSON、文件是空的——三种
 * 情况给三种说法。
 */
@Composable
fun BackupErrorDialog(message: String, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(TvColors.Surface)
                .border(1.dp, TvColors.SurfaceHigh, RoundedCornerShape(14.dp))
                .padding(24.dp)
        ) {
            Text(
                "导入失败",
                color = TvColors.Warning,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))
            Text(message, color = TvColors.OnSurface, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            BackupButton(label = "知道了", onClick = onClose, primary = true)
        }
    }
}

/** Small action button of the import preview. */@Composable
private fun BackupButton(label: String, onClick: () -> Unit, primary: Boolean = false) {
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
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text(
                label,
                color = when {
                    f && primary -> Color(0xFF14181D)
                    f -> TvColors.Primary
                    else -> TvColors.OnSurface
                },
                fontSize = 13.sp
            )
        }
    }
}
