package me.rerere.rikkahub.work

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.work.WorkConfigTransfer
import me.rerere.rikkahub.data.work.PhoneWorkCredentialStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.koin.core.context.GlobalContext

class WorkAppInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkActivity>()

    /** Run explicitly after seeding the paired Chat through its connection UI on a disposable emulator. */
    @Test fun configuredSourceTransfersCoreToken() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("configuredSource") == "true")
        compose.onNodeWithContentDescription("Work 设置").performClick()
        compose.onNodeWithText("一键导入").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("配置已导入，返回首页即可同步 Work 会话。").fetchSemanticsNodes().isNotEmpty()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val exported = context.contentResolver.call("content://dev.sundby.zhixing.staging.work-config".toUri(), "import-config-v1", null, null)
        val source = Json.decodeFromString<WorkConfigTransfer>(requireNotNull(exported?.getString("config")))
        assertTrue(source.token.isNotBlank())
        val reloaded = PhoneWorkCredentialStore(context)
        assertEquals("https://work-import.invalid", reloaded.connection.value.baseUrl)
        assertTrue(reloaded.token() == source.token)
        assertFalse(context.noBackupFilesDir.resolve("phone_work_credential").readText().contains(source.token))
    }

    @Test fun independentHomeImportsFromPairedChatAndSurvivesRecreation() {
        compose.onNodeWithText("导入或连接").performClick()
        compose.onNodeWithText("一键导入").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("语音和主题已导入", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Work 设置").assertExists()
        compose.onNodeWithText("语音设置").performClick()
        compose.waitForIdle()
        assertEquals("dev.sundby.zhixing.work.staging", compose.activity.packageName)
    }

    @Test fun exportOnlyWorksThroughVersionedCallAndDatabaseContainsNoChatTables() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = "content://dev.sundby.zhixing.staging.work-config".toUri()
        val bundle = context.contentResolver.call(uri, "import-config-v1", null, null)
        val config = Json.decodeFromString<WorkConfigTransfer>(requireNotNull(bundle?.getString("config")))
        assertEquals(1, config.version)
        assertTrue(GlobalContext.get().get<me.rerere.highlight.Highlighter>().highlight("val answer = 42", "kotlin").isNotEmpty())
        try {
            context.contentResolver.call(uri, "unsupported", null, null)
            fail("Unknown protocol version must be rejected")
        } catch (_: IllegalArgumentException) { }
        val db = GlobalContext.get().get<WorkDatabase>().openHelper.readableDatabase
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
            val names = buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            assertTrue(names.contains("phone_work_sessions"))
            assertFalse(names.contains("conversations"))
            assertFalse(names.contains("message_nodes"))
        }
    }

    @Test fun workCredentialsRemainEncryptedAndSessionDeepLinkOpensWorkOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = GlobalContext.get().get<PhoneWorkCredentialStore>()
        val previous = credentials.connection.value
        val previousToken = credentials.token()
        try {
            credentials.save("https://work-import.invalid", "integration-test-only")
            val reloaded = PhoneWorkCredentialStore(context)
            assertEquals("integration-test-only", reloaded.token())
            assertFalse(context.noBackupFilesDir.resolve("phone_work_credential").readText().contains("integration-test-only"))
            compose.onNodeWithContentDescription("Work 设置").performClick()
            compose.onNodeWithText("一键导入").performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("保留现有 Work 连接", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("integration-test-only", credentials.token())
            compose.activityRule.scenario.onActivity {
                it.startActivity(requireNotNull(it.packageManager.getLaunchIntentForPackage(it.packageName))
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("workSessionId", "integration-session"))
            }
            compose.waitUntil(15_000) {
                compose.activityRule.scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED &&
                    compose.onAllNodesWithText("Work 设置").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
            compose.onAllNodesWithText("新聊天").assertCountEquals(0)
        } finally {
            if (previous.configured && previousToken != null) credentials.save(previous.baseUrl, previousToken) else credentials.clear()
        }
    }
}
