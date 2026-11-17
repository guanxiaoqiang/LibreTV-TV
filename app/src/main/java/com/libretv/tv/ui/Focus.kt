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
/**
 * Ask for the focus without letting a transient composition state kill the app.
 *
 * A [FocusRequester] is bound to a node that may not be attached yet when the
 * request is issued from a [androidx.compose.runtime.LaunchedEffect] that runs
 * in the very frame the node's `enabled` flag flips — the player root becoming
 * focusable right after the control panel hides is exactly that case. Compose
 * then throws `IllegalArgumentException: ActiveParent with no focused child`,
 * which is fatal on a TV: the viewer just sees the app close.
 *
 * A refused request only means "no focus on this frame". Callers retry on the
 * next key press or the next recomposition, so the failure is swallowed here
 * instead of being propagated to the main thread.
 */
fun FocusRequester.requestFocusSafely(): Boolean = try {
    requestFocus()
    true
} catch (refused: IllegalStateException) {
    false
} catch (refused: IllegalArgumentException) {
    false
}

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
