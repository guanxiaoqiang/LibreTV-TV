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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libretv.tv.data.SourceEntry
import com.libretv.tv.data.SourceHealth

/**
 * Source settings page.
 *
 * Every collector is one row with its own controls: a switch to enable or
 * disable it, a button that tests just that source (reachability plus round
 * trip time) and a button that removes it. Custom sources added by the
 * viewer are listed together with the bundled ones.
 */
@Composable
fun SourcesScreen(
    entries: List<SourceEntry>,
    health: Map<String, SourceHealth>,
    testingUrl: String?,
    onToggleSource: (String, Boolean) -> Unit,
    onTestSource: (String) -> Unit,
    onTestAll: () -> Unit,
    onRemoveSource: (String) -> Unit,
    onAddSource: (String, String) -> Boolean,
    onResetSources: () -> Unit,
    onBack: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 44.dp, vertical = 26.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "源设置",
                        color = TvColors.OnSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "共 ${entries.size} 个采集源，已启用 ${entries.count { it.enabled }} 个" +
                            "· 禁用后的源不再参与搜索与分类聚合",
                        color = TvColors.OnSurfaceMuted,
                        fontSize = 13.sp
                    )
                }
                if (testingUrl != null) {
                    CircularProgressIndicator(
                        color = TvColors.Primary,
                        modifier = Modifier.width(20.dp).height(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                }
                HeaderButton(label = "添加源", onClick = { showAdd = true })
                Spacer(Modifier.width(10.dp))
                HeaderButton(label = "全部测试", onClick = onTestAll)
                Spacer(Modifier.width(10.dp))
                HeaderButton(label = "恢复默认", onClick = onResetSources)
                Spacer(Modifier.width(10.dp))
                HeaderButton(label = "返回", onClick = onBack)
            }

            Spacer(Modifier.height(14.dp))

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "没有可用的采集源，请点击「添加源」手动添加接口",
                        color = TvColors.OnSurfaceMuted,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(entries, key = { _, e -> e.url }) { _, entry ->
                        SourceRow(
                            entry = entry,
                            health = health[entry.url],
                            testing = testingUrl == entry.url,
                            onToggle = { onToggleSource(entry.url, !entry.enabled) },
                            onTest = { onTestSource(entry.url) },
                            onRemove = { onRemoveSource(entry.url) }
                        )
                    }
                }
            }
        }

        if (showAdd) {
            AddSourceDialog(
                onConfirm = { name, url ->
                    val ok = onAddSource(name, url)
                    if (ok) showAdd = false
                    ok
                },
                onCancel = { showAdd = false }
            )
        }
    }
}

/** One collector with its switch, test button and remove button. */
@Composable
private fun SourceRow(
    entry: SourceEntry,
    health: SourceHealth?,
    testing: Boolean,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit
) {
    FocusableTile(onClick = onTest) { f ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (f) TvColors.SurfaceHigh else TvColors.Surface)
                .border(
                    width = if (f) 2.dp else 0.dp,
                    color = if (f) TvColors.FocusRing else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .tvFocusable(f, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !entry.enabled -> TvColors.OnSurfaceMuted
                            health == null -> TvColors.Warning
                            health.reachable -> TvColors.Success
                            else -> TvColors.Danger
                        }
                    )
            )
            Spacer(Modifier.width(12.dp))
            Text(
                entry.name,
                color = if (entry.enabled) {
                    if (f) TvColors.Primary else TvColors.OnSurface
                } else {
                    TvColors.OnSurfaceMuted
                },
                fontSize = 14.sp,
                modifier = Modifier.width(160.dp)
            )
            Text(
                entry.url,
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            HealthLabel(health = health, testing = testing)
            Spacer(Modifier.width(10.dp))
            if (entry.custom) {
                MiniTag(text = "自建", color = TvColors.Info)
                Spacer(Modifier.width(8.dp))
            }
            MiniButton(label = if (entry.enabled) "禁用" else "启用", onClick = onToggle)
            Spacer(Modifier.width(8.dp))
            MiniButton(label = "测试", onClick = onTest)
            Spacer(Modifier.width(8.dp))
            MiniButton(label = "删除", onClick = onRemove, danger = true)
        }
    }
}

/** Reachability and latency of one source. */
@Composable
private fun HealthLabel(health: SourceHealth?, testing: Boolean) {
    when {
        testing -> Text(
            "测试中…",
            color = TvColors.Primary,
            fontSize = 12.sp,
            modifier = Modifier.width(110.dp)
        )
        health == null -> Text(
            "未测试",
            color = TvColors.OnSurfaceMuted,
            fontSize = 12.sp,
            modifier = Modifier.width(110.dp)
        )
        health.reachable -> Text(
            "正常 · ${shortLatency(health.latencyMs)}",
            color = TvColors.Success,
            fontSize = 12.sp,
            modifier = Modifier.width(110.dp)
        )
        else -> Text(
            "失败 · ${shortLatency(health.latencyMs)}",
            color = TvColors.Danger,
            fontSize = 12.sp,
            modifier = Modifier.width(110.dp)
        )
    }
}

/** Small informational tag, e.g. the "自建" marker. */
@Composable
private fun MiniTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(TvColors.SurfaceHigh)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp)
    }
}

/** Small action button inside a source row. */
@Composable
private fun MiniButton(label: String, onClick: () -> Unit, danger: Boolean = false) {
    FocusableTile(onClick = onClick, scaleWhenFocused = 1.06f) { f ->
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        f -> if (danger) TvColors.Danger else TvColors.Primary
                        else -> TvColors.SurfaceHigh
                    }
                )
                .tvFocusable(f, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                label,
                color = when {
                    f -> Color(0xFF14181D)
                    danger -> TvColors.Danger
                    else -> TvColors.OnSurface
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** Button in the page header. */
@Composable
private fun HeaderButton(label: String, onClick: () -> Unit) {
    FocusableTile(onClick = onClick) { f ->
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (f) TvColors.SurfaceHigh else TvColors.Surface)
                .tvFocusable(f, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                label,
                color = if (f) TvColors.Primary else TvColors.OnSurface,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * Overlay for adding a collector by hand.
 *
 * Both fields are real text fields, so a soft keyboard works and a physical
 * keyboard can type into them as well.
 */
@Composable
private fun AddSourceDialog(
    onConfirm: (String, String) -> Boolean,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
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
                "添加采集源",
                color = TvColors.OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "填写 Apple CMS 接口地址，名称可留空自动取域名",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))

            DialogField(label = "名称", value = name, onValueChange = { name = it })
            Spacer(Modifier.height(12.dp))
            DialogField(
                label = "接口地址",
                value = url,
                onValueChange = { url = it },
                keyboardType = KeyboardType.Uri
            )

            if (error.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = TvColors.Danger, fontSize = 12.sp)
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusableTile(onClick = { onConfirm(name, url) }) { f ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (f) TvColors.Primary else TvColors.SurfaceHigh)
                            .tvFocusable(f, RoundedCornerShape(8.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "添加",
                            color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                            fontSize = 14.sp
                        )
                    }
                }
                FocusableTile(onClick = onCancel) { f ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (f) TvColors.SurfaceHigh else TvColors.Surface)
                            .tvFocusable(f, RoundedCornerShape(8.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "取消",
                            color = if (f) TvColors.Primary else TvColors.OnSurface,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

/** One labelled text field of the add-source dialog. */
@Composable
private fun DialogField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Column {
        Text(label, color = TvColors.OnSurfaceMuted, fontSize = 12.sp)
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = TvColors.OnSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(TvColors.Primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TvColors.SurfaceHigh)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            "点按后输入",
                            color = TvColors.OnSurfaceMuted,
                            fontSize = 14.sp
                        )
                    }
                    inner()
                }
            }
        )
    }
}
