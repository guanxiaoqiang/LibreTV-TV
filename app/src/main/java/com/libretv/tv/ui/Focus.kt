package com.libretv.tv.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * TV focus helper.
 *
 * Two independent paths reach the same action:
 *
 *  - the remote / keyboard path goes through [onKeyEvent], which reacts to the
 *    select keys and lets Compose move the focus spatially;
 *  - the touch path goes through [pointerInput], so a finger tap lands on the
 *    very same callback.
 *
 * [Modifier.clickable] is deliberately not used here: it registers its own key
 * handling for the select keys, so an OK press would fire the action twice
 * (once from the key handler, once from the click). Splitting the two paths
 * keeps a single tap and a single OK press mapped to exactly one activation.
 *
 * [onLongPress] is optional. When it is supplied the long press wins over the
 * tap: Compose consumes the long press, so the normal click never fires. That
 * is what lets a touch user reveal a hidden context action instead of opening
 * the item straight away.
 */
@Composable
fun Modifier.focusableItem(
    enabled: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    // The lambdas are read through rememberUpdatedState so a recomposition with
    // a new callback does not restart the pointer coroutine (which would drop
    // an in-flight gesture).
    val currentClick by rememberUpdatedState(onClick)
    val currentLongPress by rememberUpdatedState(onLongPress)
    return this
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.ButtonA -> {
                    if (enabled) currentClick()
                    true
                }
                else -> false
            }
        }
        .focusable(enabled = enabled)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            if (currentLongPress != null) {
                detectTapGestures(
                    onTap = { currentClick() },
                    onLongPress = { currentLongPress?.invoke() }
                )
            } else {
                detectTapGestures { currentClick() }
            }
        }
}
