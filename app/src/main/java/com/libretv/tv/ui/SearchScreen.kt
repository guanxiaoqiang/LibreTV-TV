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

/**
 * The on-screen board: a number row on top, then the three QWERTY rows.
 * Every key types its own character into the input field.
 */
private val keyboardRows = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
    listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
    listOf("Z", "X", "C", "V", "B", "N", "M")
)

/** Board geometry in dp, used to work out the up / down focus neighbours. */
private const val KEY_WIDTH_DP = 46
private const val KEY_SPACING_DP = 6

/**
 * Horizontal centre of the key at [index] inside a row of [count] keys. The
 * rows are centred, so a key of one row lines up with the key of another
 * row that sits closest to the same centre.
 */
private fun keyCentre(index: Int, count: Int): Float {
    val rowWidth = count * KEY_WIDTH_DP + (count - 1) * KEY_SPACING_DP
    return (rowWidth - KEY_WIDTH_DP) / 2f + index * (KEY_WIDTH_DP + KEY_SPACING_DP)
}

private const val MAX_SUGGESTIONS = 8
private const val MAX_HISTORY_ROWS = 12

/**
 * Search page layout:
 *
 * ```
 *  [ focusable input field                                      ]
 *  [ live suggestion strip, directly under the field           ]
 *  [ number row + QWERTY board + action keys ] [ search history ]
 * ```
 *
 * The on-screen board (letters, digits and space) types straight into the
 * input field, so the field always shows the characters that were just
 * pressed. Suggestions never appear inside the field — they live in the
 * strip right below it, and picking one jumps straight to the results.
 *
 * A real text field with the IME is still there for Chinese / free text, and
 * one-tap recent keywords sit on the right.
 *
 * While the text field holds focus every physical key is left to the IME; the
 * root key handler below would otherwise type the same character twice.
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

    // One requester per board key plus one per action key (space, backspace,
    // clear, search).
    val boardKeys = remember { keyboardRows.flatten() }
    val boardCount = boardKeys.size
    val requesters = remember { List(boardCount + 4) { FocusRequester() } }
    val spaceRequester = requesters[boardCount]
    val backspaceRequester = requesters[boardCount + 1]
    val clearRequester = requesters[boardCount + 2]
    val searchRequester = requesters[boardCount + 3]
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
        boardKeys.forEachIndexed { index, ch -> map[ch] = index }
        map
    }

    // Row of every key, so "up" / "down" can find the neighbouring row.
    val rowOf = remember {
        val map = mutableMapOf<String, Int>()
        keyboardRows.forEachIndexed { rowIndex, row ->
            row.forEach { ch -> map[ch] = rowIndex }
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

    /**
     * The key directly above / below [ch]. The rows have different widths, so
     * the neighbour is the one whose horizontal centre is closest to the
     * centre of [ch] in the neighbouring row.
     */
    fun verticalNeighbour(ch: String, step: Int): String? {
        val rowIndex = rowOf[ch] ?: return null
        val targetRowIndex = rowIndex + step
        if (targetRowIndex < 0 || targetRowIndex >= keyboardRows.size) return null
        val row = keyboardRows[rowIndex]
        val column = row.indexOf(ch)
        val centre = keyCentre(column, row.size)
        val target = keyboardRows[targetRowIndex]
        val best = target.indices.minByOrNull { index ->
            kotlin.math.abs(keyCentre(index, target.size) - centre)
        }
        return best?.let { target[it] }
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
                    // The number row types digits into the field, exactly like
                    // the number keys of the on-screen board.
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        val digit = event.nativeKeyEvent.keyCode - KeyEvent.KEYCODE_0
                        onInput(digit.toString())
                        true
                    }
                    KeyEvent.KEYCODE_SPACE -> {
                        onInput(" ")
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
                            "上焦本框可拉起键盘直接输入；左侧键盘输入的字符会实时显示在这里，联想词只出现在下方",
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
        // They never render inside the field: the field shows the typed text
        // only, the strip below it shows what can be picked.
        Spacer(Modifier.height(8.dp))
        SuggestionStrip(
            suggestions = suggestions,
            query = query,
            requesters = suggestionRequesters,
            upTarget = inputRequester,
            onPick = onPickCandidate
        )

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // LEFT: number row, QWERTY board and action keys.
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
                        horizontalArrangement = Arrangement.spacedBy(
                            6.dp,
                            Alignment.CenterHorizontally
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        row.forEach { ch ->
                            val index = keyPositions.getValue(ch)
                            val nextIndex = index + 1
                            val prevIndex = index - 1
                            BoardKey(
                                label = ch,
                                modifier = Modifier
                                    .focusRequester(requesters[index])
                                    .focusProperties {
                                        this.next = if (nextIndex < boardCount) {
                                            requesters[nextIndex]
                                        } else {
                                            spaceRequester
                                        }
                                        this.previous = if (prevIndex >= 0) {
                                            requesters[prevIndex]
                                        } else {
                                            searchRequester
                                        }
                                        verticalNeighbour(ch, 1)?.let { below ->
                                            this.down = requesters[keyPositions.getValue(below)]
                                        }
                                        // UP from the number row reaches the
                                        // suggestion strip (or the field).
                                        if (rowIndex == 0) this.up = firstRowUp
                                        // RIGHT from the last key of a row
                                        // enters the history column.
                                        if (ch == row.last()) {
                                            val target = rightTarget
                                            if (target != null) this.right = target
                                        }
                                    },
                                onClick = { onInput(ch.lowercase()) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(
                        7.dp,
                        Alignment.CenterHorizontally
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ActionKeyCap(
                        label = "空格",
                        modifier = Modifier
                            .focusRequester(spaceRequester)
                            .focusProperties {
                                this.next = backspaceRequester
                                this.previous = requesters[boardCount - 1]
                            },
                        onClick = { onInput(" ") }
                    )
                    ActionKeyCap(
                        label = "退格",
                        modifier = Modifier
                            .focusRequester(backspaceRequester)
                            .focusProperties {
                                this.next = clearRequester
                                this.previous = spaceRequester
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
    query: String,
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
            text = when {
                query.isNotEmpty() && suggestions.isNotEmpty() ->
                    "已输入 $query · 共 ${suggestions.size} 个联想，点按直接搜索"
                query.isNotEmpty() ->
                    "已输入 $query · 暂无匹配片名，可按退格修改或上焦输入框直接输入"
                suggestions.isNotEmpty() ->
                    "联想片名 · 点按直接搜索对应片名"
                else ->
                    "联想片名 · 左侧键盘输入的字符会实时显示在上方输入框，联想词只出现在这里"
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
private fun BoardKey(
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
