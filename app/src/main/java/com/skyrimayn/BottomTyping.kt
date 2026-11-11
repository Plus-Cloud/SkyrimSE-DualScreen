package com.skyrimayn

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * A text box on the top screen whose keyboard opens on the bottom screen (dual-screen): the box is read-only up
 * there, and tapping it (or pressing A on it) opens typing below, which fills the box as you type.
 * Single-screen, it's an ordinary text box.
 */
class BottomTyping(val readOnly: Boolean, val interaction: MutableInteractionSource, val modifier: Modifier)

@Composable
fun rememberBottomTyping(
    label: String, value: () -> String, password: Boolean = false,
    action: Int = android.view.inputmethod.EditorInfo.IME_ACTION_DONE,
    onChange: (String) -> Unit, onDone: () -> Unit = {},
): BottomTyping {
    val interaction = remember { MutableInteractionSource() }
    val onBottom = BottomScreenHost.typesOnBottom
    val open = { BottomScreenHost.editText(label, value(), password, action, onChange, onDone) }
    LaunchedEffect(interaction, onBottom) {
        if (onBottom) interaction.interactions.collect { if (it is PressInteraction.Release) open() }
    }
    val modifier = if (!onBottom) Modifier else Modifier.onPreviewKeyEvent { e ->
        // Opens on key down only: the release of the keyboard's own Enter/Search key lands up here after typing
        // closes, and must not reopen it.
        val confirm = e.key == Key.Enter || e.key == Key.DirectionCenter || e.key == Key.ButtonA || e.key == Key.NumPadEnter
        if (confirm && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) { open(); true } else confirm
    }
    return BottomTyping(onBottom, interaction, modifier)
}
