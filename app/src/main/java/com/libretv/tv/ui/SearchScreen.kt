package com.libretv.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
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

private const val MAX_SUGGESTIONS = 8
private const val MAX_HISTORY_ROWS = 12

/**
 * Search page layout:
 *
 * ```
 *  [ focusable input field                                      ]
 *  [ live suggestion strip, directly under the field           ]
 *  [ QWERTY board + action keys ]   [ focusable search history  ]
 * ```
 *
 * Three complementary input paths: the on-screen board (pinyin initials),
 * a real text field with the IME for Chinese / free text, and one-tap recent
 * keywords on the right. The suggestion strip reacts to both typing paths.
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
    val suggestions = candidates.take(MAX_SUGGESTIONS)
    val history = searchHistory.take(MAX_HISTORY_ROWS)

    // One requester per letter key plus one per action key.
    val letterCount = keyboardRows.sumOf { it.size }
    val requesters = remember { List(letterCount + 3) { FocusRequester() } }
    val backspaceRequester = requesters[letterCount]
    val clearRequester = requesters[letterCount + 1]
    val searchRequester = requesters[letterCount + 2]
    val inputRequester = remember { FocusRequester() }
    val suggestionRequesters = remember { List(MAX_SUGGESTIONS) { FocusRequester() } }
    val historyRequesters = remember { List(MAX_HISTORY_ROWS) { FocusRequester() } }
    val clearHistoryRequester = remember { FocusRequester() }

    var inputFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(inputFocused) {
        // A focused BasicTextField normally asks for the IME itself; show()
        // covers TV launchers / remote-only devices that need the extra nudge.
        // Hiding it on the way out matters just as much: a full-height IME
        // covers the lower keyboard rows and the action keys on a TV.
        if (inputFocused) keyboard?.show() else keyboard?.hide()
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

    // Last key of each row: the entry point into the history column on RIGHT.
    val lastColumnRequesters = remember {
        var acc = 0
        keyboardRows.map { row ->
            acc += row.size
            requesters[acc - 1]
        }
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

    /**
     * BasicTextField consumes DPAD keys to move the caret, so directional focus
     * search never runs while the IME owns the field and a remote user gets
     * trapped in it. Intercept DOWN in the preview (capture) phase — before the
     * text field sees the event — and hand focus to the strip / board.
     */
    fun leaveFieldOnDown(): Boolean {
        val target = if (suggestions.isNotEmpty()) {
            suggestionRequesters[0]
        } else {
            requesters[0]
        }
        target.requestFocus()
        return true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 20.dp)
            .onPreviewKeyEvent { event ->
                if (!inputFocused || event.type != KeyEventType.KeyDown ||
                    event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    return@onPreviewKeyEvent false
                }
                leaveFieldOnDown()
            }
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
                    // The number row is the fastest path on a TV remote: 1-8
                    // pick the matching suggestion directly, 0 submits.
                    in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                        val index = event.nativeKeyEvent.keyCode - KeyEvent.KEYCODE_1
                        suggestions.getOrNull(index)?.let { onPickCandidate(it) }
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
                .focusProperties {
                    // DOWN always lands on the live suggestion strip when it has
                    // chips; with an empty strip the board is the next target.
                    this.down = if (suggestions.isNotEmpty()) {
                        suggestionRequesters[0]
                    } else {
                        requesters[0]
                    }
                }
                .onPreviewKeyEvent { event ->
                    // Second capture point, right on the field itself. The root
                    // preview normally wins; this keeps the behaviour if the
                    // field is ever used without that wrapper.
                    if (!inputFocused || event.type != KeyEventType.KeyDown ||
                        event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                    ) {
                        return@onPreviewKeyEvent false
                    }
                    leaveFieldOnDown()
                }
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
                        .height(62.dp)
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
                            "上焦本框可拉起键盘直接输入；也可用左侧全键盘选字母，拼音首字母可搜中文片名",
                            color = TvColors.OnSurfaceMuted,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                    }
                    innerTextField()
                }
            }
        )

        // Live suggestions sit immediately below the field, above both columns.
        Spacer(Modifier.height(8.dp))
        SuggestionStrip(
            suggestions = suggestions,
            requesters = suggestionRequesters,
            upTarget = inputRequester,
            onPick = onPickCandidate
        )

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // LEFT: QWERTY board and action keys.
            Column(modifier = Modifier.weight(1f)) {
                val firstRowUp = if (suggestions.isNotEmpty()) {
                    suggestionRequesters[0]
                } else {
                    inputRequester
                }
                keyboardRows.forEachIndexed { rowIndex, row ->
                    val rightTarget = if (history.isNotEmpty()) {
                        historyRequesters[minOf(rowIndex, history.size - 1)]
                    } else {
                        null
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(vertical = 3.dp)
                    ) {
                        row.forEach { ch ->
                            val index = keyPositions.getValue(ch)
                            val nextIndex = index + 1
                            val prevIndex = index - 1
                            LetterKey(
                                label = ch,
                                modifier = Modifier
                                    .focusRequester(requesters[index])
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
                                        // UP from the first letter row reaches
                                        // the suggestion strip (or the field).
                                        if (rowIndex == 0) this.up = firstRowUp
                                        // RIGHT from the last key of a row
                                        // enters the history column.
                                        if (ch == row.last()) {
                                            val target = rightTarget
                                            if (target != null) this.right = target
                                        }
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

            Spacer(Modifier.width(24.dp))

            // RIGHT: recent keywords, all focusable with the D-pad.
            HistoryPanel(
                history = history,
                requesters = historyRequesters,
                clearRequester = clearHistoryRequester,
                leftTargets = lastColumnRequesters,
                onPick = onPickHistory,
                onDelete = onDeleteHistory,
                onClear = onClearHistory
            )
        }
    }
}

/** Live suggestion strip, pinned directly under the input field. */
@Composable
private fun SuggestionStrip(
    suggestions: List<String>,
    requesters: List<FocusRequester>,
    upTarget: FocusRequester,
    onPick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
    ) {
        Text(
            text = if (suggestions.isEmpty()) {
                "联想片名 · 输入关键词或按左侧字母，联想结果会实时显示在这里"
            } else {
                "联想片名 · 按数字 1-${suggestions.size} 快捷选择"
            },
            color = TvColors.OnSurfaceMuted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(7.dp))
        if (suggestions.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                suggestions.forEachIndexed { index, title ->
                    val nextTarget = if (index + 1 < suggestions.size) {
                        requesters[index + 1]
                    } else {
                        null
                    }
                    val prevTarget = if (index > 0) requesters[index - 1] else upTarget
                    FocusableTile(
                        onClick = { onPick(title) },
                        scaleWhenFocused = 1.06f,
                        modifier = Modifier
                            .focusRequester(requesters[index])
                            .focusProperties {
                                this.up = upTarget
                                this.previous = prevTarget
                                val nxt = nextTarget
                                if (nxt != null) this.next = nxt
                            }
                    ) { focused ->
                        Chip(title, focused)
                    }
                }
            }
        }
    }
}

/** Recent keywords on the right, each row focusable (keyword + delete). */
@Composable
private fun HistoryPanel(
    history: List<String>,
    requesters: List<FocusRequester>,
    clearRequester: FocusRequester,
    leftTargets: List<FocusRequester>,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(TvColors.Surface.copy(alpha = 0.45f))
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "搜索记录",
                color = TvColors.OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            if (history.isNotEmpty()) {
                FocusableTile(
                    onClick = onClear,
                    scaleWhenFocused = 1.06f,
                    modifier = Modifier.focusRequester(clearRequester)
                ) { focused ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (focused) TvColors.SurfaceHigh else TvColors.Surface)
                            .tvFocusable(focused, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "清空",
                            color = if (focused) TvColors.Danger else TvColors.OnSurfaceMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (history.isEmpty()) {
            Text(
                "暂无记录 · 搜索过的关键词会自动保存在这里，上焦即可一键重搜",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                history.forEachIndexed { index, keyword ->
                    val leftTarget = leftTargets[minOf(index, leftTargets.size - 1)]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FocusableTile(
                            onClick = { onPick(keyword) },
                            scaleWhenFocused = 1.04f,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(requesters[index])
                                .focusProperties { this.left = leftTarget }
                        ) { focused ->
                            Chip(keyword, focused)
                        }
                        Spacer(Modifier.width(6.dp))
                        FocusableTile(
                            onClick = { onDelete(keyword) },
                            scaleWhenFocused = 1.06f
                        ) { focused ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (focused) TvColors.Danger else TvColors.Surface
                                    )
                                    .tvFocusable(focused, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 9.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    "×",
                                    color = if (focused) Color.White else TvColors.OnSurfaceMuted,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, focused: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) TvColors.Primary else TvColors.SurfaceHigh)
            .tvFocusable(focused, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            color = if (focused) Color(0xFF14181D) else TvColors.OnSurface,
            fontSize = 13.sp,
            maxLines = 1
        )
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
                .width(46.dp)
                .height(50.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (f) TvColors.SurfaceHigh else TvColors.Surface)
                .tvFocusable(f, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (f) TvColors.Primary else TvColors.OnSurface,
                fontSize = 16.sp,
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
                .width(88.dp)
                .height(50.dp)
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
