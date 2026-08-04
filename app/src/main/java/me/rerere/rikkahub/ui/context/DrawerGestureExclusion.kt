package me.rerere.rikkahub.ui.context

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

internal val LocalDrawerGestureExclusion =
    staticCompositionLocalOf<MutableState<Boolean>?> { null }

internal fun Modifier.excludeDrawerGesturesWhilePressed(
    exclusion: MutableState<Boolean>?,
): Modifier = if (exclusion == null) {
    this
} else {
    pointerInput(exclusion) {
        awaitEachGesture {
            awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            exclusion.value = true
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
            } finally {
                exclusion.value = false
            }
        }
    }
}
