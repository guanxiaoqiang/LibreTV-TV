package com.libretv.tv.ui

import android.view.KeyEvent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full QWERTY layout, laid out as rows so the D-pad can walk it. */
private val keyboardRows = listOf(
    listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
    listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
    listOf("Z", "X", "C", "V", "B", "N", "M")
)

/** Action keys that sit under the letter rows. */
private enum class ActionKey { BACKSPACE, CLEAR, SEARCH }

/**
 * Full keyboard search.
 *
 * The previous T9 keypad was faster for a single known title but it could not
 * type a name that is not already in the bundled pinyin index. A full
 * QWERTY board covers every case, and on a remote it stays usable because:
 *
 *  - the focus chain is explicit, so the D-pad never guesses,
 *  - the letters are pinyin initials, so "ldqd" resolves to 流浪地球,
 *  - the physical number keys still work and jump straight to a letter,
 *  - every key is also a tap target for touch users.
 */
@Composable
fun SearchScreen(
    query: String,
    candidates: List<String>,
    onInput: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onPickCandidate: (String) -> Unit
) {
    // One requester per letter key plus one per action key.
    val letterCount = keyboardRows.sumOf { it.size }
    val requesters = remember { List(letterCount + 3) { FocusRequester() } }
    val backspaceRequester = requesters[letterCount]
    val clearRequester = requesters[letterCount + 1]
    val searchRequester = requesters[letterCount + 2]

    var flatIndex by remember { mutableStateOf(0) }

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
        keyboardRows.forEachIndexed { rowIndex, row ->
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
        // Query display
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(TvColors.Surface)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(
                    "点击或用方向键选字母，OK 输入；输入拼音首字母即可搜中文片名",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 14.sp
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        query,
                        color = TvColors.Primary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(26.dp)
                            .background(TvColors.Primary)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

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
                                },
                            onClick = {
                                flatIndex = index
                                onInput(ch)
                            }
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

/** Horizontal scroll helper kept readable at the call site. */
@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.horizontalScroll(rememberScrollState())

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
