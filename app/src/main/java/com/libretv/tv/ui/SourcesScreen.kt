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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libretv.tv.data.SourceConfig

@Composable
fun SourcesScreen(
    sources: List<SourceConfig>,
    health: Map<String, Boolean>,
    checking: Boolean,
    adFilterEnabled: Boolean,
    onToggleAdFilter: () -> Unit,
    onCheck: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("源管理", color = TvColors.OnSurface, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "内置 ${sources.size} 个采集接口，已检测 ${health.size} 个" +
                        if (checking) "（检测中…）" else "",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 13.sp
                )
            }
            if (checking) {
                CircularProgressIndicator(
                    color = TvColors.Primary,
                    modifier = Modifier.width(22.dp).height(22.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(14.dp))
            }
            FocusableTile(onClick = onToggleAdFilter) { f ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (f) TvColors.Primary else TvColors.SurfaceHigh)
                        .tvFocusable(f, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "广告切片过滤",
                        color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (adFilterEnabled) "已开启" else "已关闭",
                        color = if (f) Color(0xFF14181D)
                        else if (adFilterEnabled) TvColors.Success else TvColors.Danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            FocusableTile(onClick = onCheck) { f ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (f) TvColors.Primary else TvColors.SurfaceHigh)
                        .tvFocusable(f, RoundedCornerShape(8.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        "批量测活",
                        color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                        fontSize = 14.sp
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            FocusableTile(onClick = onBack) { f ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (f) TvColors.SurfaceHigh else TvColors.Surface)
                        .tvFocusable(f, RoundedCornerShape(8.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text("返回", color = if (f) TvColors.Primary else TvColors.OnSurface, fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "广告切片过滤沿用 LibreTV 原项目规则：按广告分片 URL、片头插入、长片间超短中插识别，" +
                "只删广告段落并保留 DISCONTINUITY 时间轴与 KEY / MAP 标签",
            color = TvColors.OnSurfaceMuted,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(sources) { src ->
                FocusableTile(onClick = {}) { f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (f) TvColors.SurfaceHigh else TvColors.Surface)
                            .tvFocusable(f, RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(8.dp)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (health[src.name]) {
                                        true -> TvColors.Success
                                        false -> TvColors.Danger
                                        null -> TvColors.OnSurfaceMuted
                                    }
                                )
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            src.name,
                            color = if (f) TvColors.Primary else TvColors.OnSurface,
                            fontSize = 14.sp,
                            modifier = Modifier.width(170.dp)
                        )
                        Text(
                            src.url,
                            color = TvColors.OnSurfaceMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
