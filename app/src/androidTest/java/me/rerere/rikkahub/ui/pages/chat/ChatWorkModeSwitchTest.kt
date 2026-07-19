package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.rerere.rikkahub.data.work.WorkAppMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatWorkModeSwitchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compactSwitchShowsBothModesAndEmitsWorkSelection() {
        var selected = WorkAppMode.CHAT
        compose.setContent {
            MaterialTheme {
                ChatWorkModeSwitch(mode = selected, onModeChange = { selected = it })
            }
        }

        compose.onNodeWithText("Chat").performClick()
        compose.runOnIdle { assertEquals(WorkAppMode.CHAT, selected) }
        compose.onNodeWithText("Work").performClick()
        compose.runOnIdle { assertEquals(WorkAppMode.WORK, selected) }
    }
}
