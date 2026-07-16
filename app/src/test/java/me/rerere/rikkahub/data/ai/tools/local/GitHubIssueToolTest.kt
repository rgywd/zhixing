package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class GitHubIssueToolTest {
    private val environment = GitHubIssueEnvironment(
        appVersion = "0.1.3",
        versionCode = "4",
        androidVersion = "16 (SDK 36)",
        device = "Google Pixel",
    )

    @Test
    fun `feature request uses enhancement label and product sections`() {
        val draft = createGitHubIssueDraft(buildJsonObject {
            put("type", "feature")
            put("title", "feat: 支持一键提交需求")
            put("description", "希望助手整理需求后打开 GitHub。")
            put("use_case", "在手机里发现需求时立即提交。")
            put("acceptance_criteria", "打开已预填的需求页面。")
        }, environment)

        assertEquals("feat: 支持一键提交需求", draft.title)
        assertEquals("enhancement", draft.label)
        assertTrue(draft.body.contains("## 使用场景"))
        assertTrue(draft.body.contains("Zhixing: 0.1.3 (4)"))
        assertTrue(draft.body.contains("Google Pixel"))
        assertTrue(decodedQuery(draft.url, "body").contains("打开已预填的需求页面"))
        assertEquals("enhancement", decodedQuery(draft.url, "labels"))
    }

    @Test
    fun `bug report uses bug sections without leaking unrelated feature fields`() {
        val draft = createGitHubIssueDraft(buildJsonObject {
            put("type", "bug")
            put("title", "列表闪退")
            put("description", "打开列表后应用退出。")
            put("steps_to_reproduce", "1. 打开列表\n2. 点击条目")
            put("expected_behavior", "显示详情")
            put("actual_behavior", "应用退出")
            put("use_case", "should not appear")
        }, environment)

        assertEquals("bug: 列表闪退", draft.title)
        assertEquals("bug", draft.label)
        assertTrue(draft.body.contains("## 复现步骤"))
        assertTrue(draft.body.contains("应用退出"))
        assertFalse(draft.body.contains("should not appear"))
    }

    @Test
    fun `invalid type and blank required fields are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            createGitHubIssueDraft(buildJsonObject {
                put("type", "question")
                put("title", "问题")
                put("description", "描述")
            }, environment)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createGitHubIssueDraft(buildJsonObject {
                put("type", "feature")
                put("title", " ")
                put("description", "描述")
            }, environment)
        }
    }

    private fun decodedQuery(url: String, key: String): String {
        val rawValue = URI(url).rawQuery.split('&')
            .map { it.split('=', limit = 2) }
            .first { it.first() == key }
            .getOrElse(1) { "" }
        return URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
    }
}
