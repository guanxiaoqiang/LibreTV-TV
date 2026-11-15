package com.libretv.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full QWERTY layout, laid out as rows so the D-pad can walk it. */
private val keyboardRows = listOf(
    listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
    listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
    listOf("Z", "X", "C", "V", "B", "N", "M")
)

/**
 * Search with three complementary input paths:
 *
 *  - the full QWERTY board (pinyin initials -> Chinese titles) for a remote,
 *  - a real focusable text field that pulls up the IME for Chinese / free text,
 *  - persisted recent keywords, one tap to search again.
 *
 * While the text field holds focus every physical key is left to the IME; the
 * root key handler below would otherwise also fold letters into the pinyin
 * buffer and double-apply input.
 */
@Composable
fun SearchScreen(
    query: String,
    candidates: List<String>,
    searchHistory: List<String>,
    onQueryChange: (String) -> Unit,
    onInput: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onPickCandidate: (String) -> Unit,
    onPickHistory: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    // One requester per letter key plus one per action key.
    val letterCount = keyboardRows.sumOf { it.size }
    val requesters = remember { List(letterCount + 3) { FocusRequester() } }
    val backspaceRequester = requesters[letterCount]
    val clearRequester = requesters[letterCount + 1]
    val searchRequester = requesters[letterCount + 2]
    val inputRequester = remember { FocusRequester() }

    var inputFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(inputFocused) {
        // A focused BasicTextField normally asks for the IME itself; show()
        // covers TV launchers / remote-only devices that need the extra nudge.
        if (inputFocused) keyboard?.show()
    }

    // Remember where each key sits so the chain can be built.
    val keyPositions = remember {
        val map = mutableMapOf<String, Int>()
        var i = 0
        keyboardRows.forEach { row -> row.forEach { ch -> map[ch] = i; i++ } }
        map
    }

    // Column of every letter, so "down" can find the key below it even though
    // the three rows have different widths.
    val columnOf = remember {
        val map = mutableMapOf<String, Int>()
        keyboardRows.forEach { row ->
            row.forEachIndexed { colIndex, ch -> map[ch] = colIndex }
        }
        map
    }

    /** The letter directly below [ch], if the layout has one. */
    fun belowOf(ch: String): String? {
        val col = columnOf[ch] ?: return null
        val rowIndex = keyboardRows.indexOfFirst { ch in it }
        if (rowIndex < 0 || rowIndex == keyboardRows.lastIndex) return null
        return keyboardRows[rowIndex + 1].getOrNull(col)
    }

    LaunchedEffect(Unit) {
        requesters[0].requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp)
            .onKeyEvent { event ->
                if (inputFocused) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        onBackspace()
                        true
                    }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onSubmit()
                        true
                    }
                    // A physical or on-screen keyboard should type straight into
                    // the query instead of forcing the user to walk the grid.
                    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                        val letter = KeyEvent.keyCodeToString(event.nativeKeyEvent.keyCode)
                            .removePrefix("KEYCODE_")
                            .lowercase()
                        if (letter.isNotEmpty()) onInput(letter)
                        true
                    }
                    // The number row is the fastest path on a TV remote: 1-9
                    // pick the matching candidate directly, 0 submits.
                    in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                        val index = event.nativeKeyEvent.keyCode - KeyEvent.KEYCODE_1
                        candidates.getOrNull(index)?.let { onPickCandidate(it) }
                        true
                    }
                    KeyEvent.KEYCODE_0 -> {
                        onSubmit()
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Focusable query field. It is a real text field (not a styled label),
        // so focusing it pulls up the IME and Chinese input works directly.
        val fieldShape = RoundedCornerShape(10.dp)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(inputRequester)
                .onFocusChanged { inputFocused = it.isFocused },
            textStyle = TextStyle(
                color = TvColors.OnSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            ),
            singleLine = true,
            cursorBrush = SolidColor(TvColors.Primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(fieldShape)
                        .background(if (inputFocused) TvColors.SurfaceHigh else TvColors.Surface)
                        .border(
                            width = if (inputFocused) 2.dp else 0.dp,
                            color = if (inputFocused) TvColors.FocusRing else Color.Transparent,
                            shape = fieldShape
                        )
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (query.isEmpty()) {
                        Text(
                            "上焦本框可拉起键盘直接输入；也可用下方全键盘选字母，拼音首字母可搜中文片名",
                            color = TvColors.OnSurfaceMuted,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                    }
                    innerTextField()
                }
            }
        )

        Spacer(Modifier.height(12.dp))

        // Recent keywords: tap one to search again, tap its x to remove it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "搜索记录",
                color = TvColors.OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            if (searchHistory.isNotEmpty()) {
                FocusableTile(onClick = onClearHistory, scaleWhenFocused = 1.06f) { f ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (f) TvColors.SurfaceHigh else TvColors.Surface)
                            .tvFocusable(f, RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "清空记录",
                            color = if (f) TvColors.Danger else TvColors.OnSurfaceMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (searchHistory.isEmpty()) {
            Text(
                "暂无记录 · 搜索过的关键词会自动保存在这里",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(searchHistory, key = { _, kw -> kw }) { _, kw ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FocusableTile(onClick = { onPickHistory(kw) }) { f ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (f) TvColors.Primary else TvColors.SurfaceHigh)
                                    .tvFocusable(f, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    kw,
                                    color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        FocusableTile(
                            onClick = { onDeleteHistory(kw) },
                            scaleWhenFocused = 1.06f
                        ) { f ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (f) TvColors.Danger else TvColors.Surface)
                                    .tvFocusable(f, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 9.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    "×",
                                    color = if (f) Color.White else TvColors.OnSurfaceMuted,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Pinyin candidates for the current initials
        if (candidates.isNotEmpty()) {
            Text(
                "候选片名",
                color = TvColors.OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                candidates.forEach { title ->
                    FocusableTile(onClick = { onPickCandidate(title) }) { f ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (f) TvColors.Primary else TvColors.SurfaceHigh)
                                .tvFocusable(f, RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                title,
                                color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            keyboardRows.forEachIndexed { rowIndex, row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(vertical = 3.dp)
                ) {
                    row.forEach { ch ->
                        val index = keyPositions.getValue(ch)
                        val requester = requesters[index]
                        val nextIndex = index + 1
                        val prevIndex = index - 1
                        LetterKey(
                            label = ch,
                            modifier = Modifier
                                .focusRequester(requester)
                                .focusProperties {
                                    this.next = if (nextIndex < letterCount) {
                                        requesters[nextIndex]
                                    } else {
                                        backspaceRequester
                                    }
                                    this.previous = if (prevIndex >= 0) {
                                        requesters[prevIndex]
                                    } else {
                                        searchRequester
                                    }
                                    belowOf(ch)?.let { below ->
                                        this.down = requesters[keyPositions.getValue(below)]
                                    }
                                    // UP from the first letter row always
                                    // reaches the focusable text field.
                                    if (rowIndex == 0) this.up = inputRequester
                                },
                            onClick = { onInput(ch) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ActionKeyCap(
                    label = "退格",
                    modifier = Modifier
                        .focusRequester(backspaceRequester)
                        .focusProperties {
                            this.next = clearRequester
                            this.previous = requesters[letterCount - 1]
                        },
                    onClick = onBackspace
                )
                ActionKeyCap(
                    label = "清空",
                    modifier = Modifier
                        .focusRequester(clearRequester)
                        .focusProperties {
                            this.next = searchRequester
                            this.previous = backspaceRequester
                        },
                    onClick = onClear
                )
                ActionKeyCap(
                    label = "搜索",
                    modifier = Modifier
                        .focusRequester(searchRequester)
                        .focusProperties {
                            this.previous = clearRequester
                            this.next = requesters[0]
                        },
                    accent = true,
                    onClick = onSubmit
                )
            }
        }
    }
}

@Composable
private fun LetterKey(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FocusableTile(onClick = onClick, modifier = modifier) { f ->
        Box(
            modifier = Modifier
                .width(62.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (f) TvColors.SurfaceHigh else TvColors.Surface)
                .tvFocusable(f, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (f) TvColors.Primary else TvColors.OnSurface,
                fontSize = 17.sp,
                fontWeight = if (f) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ActionKeyCap(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    FocusableTile(onClick = onClick, modifier = modifier) { f ->
        Box(
            modifier = Modifier
                .width(112.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        f -> TvColors.Primary
                        accent -> TvColors.SurfaceHigh
                        else -> TvColors.Surface
                    }
                )
                .tvFocusable(f, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
