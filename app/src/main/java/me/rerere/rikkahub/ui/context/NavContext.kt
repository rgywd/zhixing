package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen

class Navigator(private val backStack: MutableList<NavKey>) {
    fun navigate(
        screen: Screen,
        popUpTo: Screen? = null,
        inclusive: Boolean = false,
        launchSingleTop: Boolean = false,
    ) {
        popUpTo?.let { target ->
            val targetIndex = backStack.indexOfLast { it == target }
            if (targetIndex != -1) {
                val removeFromIndex = if (inclusive) targetIndex else targetIndex + 1
                repeat(backStack.size - removeFromIndex) {
                    backStack.removeLastOrNull()
                }
            }
        }

        if (launchSingleTop && backStack.lastOrNull() == screen) {
            return
        }

        backStack.add(screen)
    }

    fun clearAndNavigate(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
    }

    fun popBackStack(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeLastOrNull()
        return true
    }
}

val LocalNavController = compositionLocalOf<Navigator> {
    error("No Navigator provided")
}
