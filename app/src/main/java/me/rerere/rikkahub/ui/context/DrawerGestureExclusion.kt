package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalDrawerGestureExclusion =
    staticCompositionLocalOf<MutableState<Boolean>?> { null }
