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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libretv.tv.domain.model.Subscription
import com.libretv.tv.domain.model.SubscriptionIntervals

/**
 * 远程订阅的展示与管理。
 *
 * 订阅放在「源设置」页的上半部分：它和采集源本来就是一件事——观众在这里
 * 决定「去哪儿找片」。订阅带来的源在下面的源列表里按归属标记出来。
 */
@Composable
fun SubscriptionSection(
    subscriptions: List<Subscription>,
    syncingIds: Set<String>,
    onAdd: () -> Unit,
    onEdit: (Subscription) -> Unit,
    onDelete: (Subscription) -> Unit,
    onSync: (Subscription) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "远程订阅",
                color = TvColors.OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "订阅内容以远端为准，同步时会覆盖同名源",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            SubscriptionButton(label = "添加订阅", onClick = onAdd, primary = true)
        }

        if (subscriptions.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "还没有订阅。添加一个源清单地址，就能一次导入一批采集源。",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
        } else {
            Spacer(Modifier.height(10.dp))
            subscriptions.forEach { subscription ->
                SubscriptionRow(
                    subscription = subscription,
                    syncing = subscription.id in syncingIds,
                    onSync = { onSync(subscription) },
                    onEdit = { onEdit(subscription) },
                    onDelete = { onDelete(subscription) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** One subscription row: name, address, schedule, state and its actions. */
@Composable
private fun SubscriptionRow(
    subscription: Subscription,
    syncing: Boolean,
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TvColors.Surface)
            .border(1.dp, TvColors.SurfaceHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    subscription.name,
                    color = TvColors.OnSurface,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(180.dp)
                )
                SubscriptionTag(
                    text = when {
                        syncing -> "同步中"
                        subscription.paused -> "已暂停"
                        else -> SubscriptionIntervals.label(subscription.refreshDays)
                    },
                    color = when {
                        syncing -> TvColors.Primary
                        subscription.paused -> TvColors.Warning
                        else -> TvColors.Info
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    subscription.url,
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                statusLine(subscription),
                color = TvColors.OnSurfaceMuted,
                fontSize = 11.sp
            )
        }

        Spacer(Modifier.width(10.dp))
        SubscriptionButton(label = if (syncing) "同步中" else "立即同步", onClick = onSync, enabled = !syncing)
        Spacer(Modifier.width(8.dp))
        SubscriptionButton(label = "编辑", onClick = onEdit)
        Spacer(Modifier.width(8.dp))
        SubscriptionButton(label = "删除", onClick = onDelete, danger = true)
    }
}
/** The line under the subscription name: last sync and failure state. */
private fun statusLine(subscription: Subscription): String {
    val last = if (subscription.lastSyncAt <= 0L) {
        "尚未同步"
    } else {
        "上次同步 " + relativeTime(subscription.lastSyncAt)
    }
    val failures = if (subscription.failureCount > 0) {
        "，连续失败 " + subscription.failureCount + " 次"
    } else {
        ""
    }
    return last + failures
}

/** "刚刚 / 3 分钟前 / 昨天" — short enough for a settings row. */
private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> (diff / 60_000L).toString() + " 分钟前"
        diff < 86_400_000L -> (diff / 3_600_000L).toString() + " 小时前"
        else -> (diff / 86_400_000L).toString() + " 天前"
    }
}

/** Small tag next to the subscription name. */
@Composable
private fun SubscriptionTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(TvColors.SurfaceHigh)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp)
    }
}

/** Small action button of the subscription section. */
@Composable
private fun SubscriptionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false
) {
    FocusableTile(onClick = onClick, enabled = enabled) { f ->
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        !enabled -> TvColors.SurfaceHigh
                        f && primary -> TvColors.Primary
                        f && danger -> TvColors.Danger
                        f -> TvColors.SurfaceHigh
                        else -> TvColors.Surface
                    }
                )
                .tvFocusable(f, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                label,
                color = when {
                    !enabled -> TvColors.OnSurfaceMuted
                    f && (primary || danger) -> Color(0xFF14181D)
                    f -> TvColors.Primary
                    danger -> TvColors.Danger
                    else -> TvColors.OnSurface
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
/**
 * Add or edit a subscription.
 *
 * The same overlay serves both: [subscription] is null when adding.
 */
@Composable
fun SubscriptionEditorDialog(
    subscription: Subscription?,
    onConfirm: (String, String, Int) -> Boolean,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(subscription?.name ?: "") }
    var url by remember { mutableStateOf(subscription?.url ?: "") }
    var days by remember { mutableStateOf(subscription?.refreshDays ?: SubscriptionIntervals.OFF) }
    var error by remember { mutableStateOf("") }

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
                if (subscription == null) "添加订阅" else "编辑订阅",
                color = TvColors.OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "填写源清单地址（JSON），支持 SourceList v2 与裸数组两种格式",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))

            SubscriptionField(label = "名称", value = name, onValueChange = { name = it })
            Spacer(Modifier.height(12.dp))
            SubscriptionField(
                label = "订阅地址",
                value = url,
                onValueChange = { url = it },
                keyboardType = KeyboardType.Uri
            )
            Spacer(Modifier.height(12.dp))
            Text("刷新周期", color = TvColors.OnSurfaceMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubscriptionIntervals.ALLOWED.forEach { option ->
                    SubscriptionButton(
                        label = SubscriptionIntervals.label(option),
                        onClick = { days = option },
                        primary = days == option
                    )
                }
            }

            if (error.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = TvColors.Danger, fontSize = 12.sp)
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SubscriptionButton(
                    label = "确定",
                    onClick = {
                        if (url.trim().startsWith("http")) {
                            if (!onConfirm(name, url, days)) {
                                error = "保存失败：地址无效或已存在"
                            }
                        } else {
                            error = "订阅地址必须以 http 开头"
                        }
                    },
                    primary = true
                )
                SubscriptionButton(label = "取消", onClick = onCancel)
            }
        }
    }
}

/** One labelled text field of the subscription dialog. */
@Composable
private fun SubscriptionField(
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
                        Text("点按后输入", color = TvColors.OnSurfaceMuted, fontSize = 14.sp)
                    }
                    inner()
                }
            }
        )
    }
}