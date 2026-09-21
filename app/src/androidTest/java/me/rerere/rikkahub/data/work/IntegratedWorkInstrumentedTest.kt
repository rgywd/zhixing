package me.rerere.rikkahub.data.work

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import me.rerere.rikkahub.RouteActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IntegratedWorkInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()

    @Test fun notificationEntryOpensWorkInsideChatAndSettingsReturnToWork() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, RouteActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("workSessionId", ""))
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Work 设置").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("dev.sundby.zhixing.staging", compose.activity.packageName)
        compose.onNodeWithContentDescription("Work 设置").performClick()
        compose.onAllNodesWithText("打开 Work 独立应用").assertCountEquals(0)
        compose.onNodeWithContentDescription(compose.activity.getString(me.rerere.rikkahub.R.string.back)).performClick()
        compose.onNodeWithContentDescription("Work 设置").assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(me.rerere.rikkahub.R.string.back)).assertExists()
    }
}
