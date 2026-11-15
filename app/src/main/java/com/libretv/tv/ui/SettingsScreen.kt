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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libretv.tv.data.BadgeTag
import com.libretv.tv.data.BadgeVisibility

/**
 * Settings page.
 *
 * Every switch of the app is laid out flat on this page — nothing hides
 * behind a sub-menu — except the collector list, which has its own page
 * because it is a long, editable list.
 *
 * The poster tags are configured one by one: the viewer can keep the quality
 * but drop the latency, and so on.
 */
@Composable
fun SettingsScreen(
    adFilterEnabled: Boolean,
    badgeVisibility: BadgeVisibility,
    sourceCount: Int,
    enabledSourceCount: Int,
    onToggleAdFilter: () -> Unit,
    onToggleBadge: (BadgeTag, Boolean) -> Unit,
    onOpenSources: () -> Unit,
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
                    "设置",
                    color = TvColors.OnSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "共 $sourceCount 个采集源，已启用 $enabledSourceCount 个",
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

        Spacer(Modifier.height(18.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            SettingsToggleRow(
                title = "广告切片过滤",
                description = "按广告分片 URL、片头插入、超短中插识别 HLS 广告段落，只删广告并保留时间轴",
                value = adFilterEnabled,
                onToggle = onToggleAdFilter
            )

            SectionLabel("海报标签", "已开启 ${badgeVisibility.enabledCount} / 5 项")

            SettingsToggleRow(
                title = "上映年份",
                description = "在海报上显示影片上映年份",
                value = badgeVisibility.year,
                onToggle = { onToggleBadge(BadgeTag.YEAR, !badgeVisibility.year) },
                compact = true
            )
            SettingsToggleRow(
                title = "源数量",
                description = "在海报上显示该片可播放的源数量",
                value = badgeVisibility.sources,
                onToggle = { onToggleBadge(BadgeTag.SOURCES, !badgeVisibility.sources) },
                compact = true
            )
            SettingsToggleRow(
                title = "最高清晰度",
                description = "在海报上显示所有源里最好的画质",
                value = badgeVisibility.quality,
                onToggle = { onToggleBadge(BadgeTag.QUALITY, !badgeVisibility.quality) },
                compact = true
            )
            SettingsToggleRow(
                title = "最小延时",
                description = "在海报上显示响应最快的那个源的实测延迟",
                value = badgeVisibility.latency,
                onToggle = { onToggleBadge(BadgeTag.LATENCY, !badgeVisibility.latency) },
                compact = true
            )
            SettingsToggleRow(
                title = "豆瓣推荐标签",
                description = "在豆瓣推荐卡片上显示「豆瓣推荐」与「全网搜源」标记",
                value = badgeVisibility.douban,
                onToggle = { onToggleBadge(BadgeTag.DOUBAN, !badgeVisibility.douban) },
                compact = true
            )

            SectionLabel("采集源", "$enabledSourceCount / $sourceCount 已启用")

            SettingsEntryRow(
                title = "源设置",
                description = "添加 / 删除 / 启用禁用采集源，并可单独测试每个源的连通性与延迟",
                badge = "$enabledSourceCount/$sourceCount",
                onClick = onOpenSources
            )
        }
    }
}

/** Small heading that separates groups of settings. */
@Composable
private fun SectionLabel(title: String, hint: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    ) {
        Text(
            title,
            color = TvColors.Primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(10.dp))
        Text(hint, color = TvColors.OnSurfaceMuted, fontSize = 12.sp)
    }
}

/** One settings row with a switch. */
@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    value: Boolean,
    onToggle: () -> Unit,
    compact: Boolean = false
) {
    FocusableTile(onClick = onToggle, scaleWhenFocused = 1.02f) { f ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (f) TvColors.SurfaceHigh else TvColors.Surface)
                .border(
                    width = if (f) 2.dp else 0.dp,
                    color = if (f) TvColors.FocusRing else Color.Transparent,
                    shape = RoundedCornerShape(10.dp)
                )
                .tvFocusable(f, RoundedCornerShape(10.dp))
                .padding(
                    horizontal = 18.dp,
                    vertical = if (compact) 10.dp else 14.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (f) Color.White else TvColors.OnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    description,
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(16.dp))
            SwitchPill(value = value, focused = f)
        }
    }
}

/** One settings row that opens another page. */
@Composable
private fun SettingsEntryRow(
    title: String,
    description: String,
    badge: String,
    onClick: () -> Unit
) {
    FocusableTile(onClick = onClick, scaleWhenFocused = 1.02f) { f ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (f) TvColors.SurfaceHigh else TvColors.Surface)
                .border(
                    width = if (f) 2.dp else 0.dp,
                    color = if (f) TvColors.FocusRing else Color.Transparent,
                    shape = RoundedCornerShape(10.dp)
                )
                .tvFocusable(f, RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (f) Color.White else TvColors.OnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    description,
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (f) Color(0x33FFFFFF) else TvColors.SurfaceHigh)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    badge,
                    color = if (f) Color.White else TvColors.OnSurfaceMuted,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "›",
                color = if (f) TvColors.Primary else TvColors.OnSurfaceMuted,
                fontSize = 20.sp
            )
        }
    }
}

/** On / off pill used by the toggle rows. */
@Composable
private fun SwitchPill(value: Boolean, focused: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    value -> TvColors.Primary
                    else -> TvColors.SurfaceHigh
                }
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            if (value) "已开启" else "已关闭",
            color = if (value) Color(0xFF14181D) else TvColors.OnSurfaceMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
