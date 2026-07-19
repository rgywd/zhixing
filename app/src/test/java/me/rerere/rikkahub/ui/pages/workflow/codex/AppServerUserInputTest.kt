package me.rerere.rikkahub.ui.pages.workflow.codex

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.work.AppServerCompatibilityLevel
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class AppServerUserInputTest {
    @Test
    fun `document uses official structured mention input`() {
        val input = appServerDocumentMention(
            name = "requirements.md",
            path = "C:\\workspace\\uploads\\requirements.md",
        )

        assertEquals("mention", input.getValue("type").jsonPrimitive.content)
        assertEquals("requirements.md", input.getValue("name").jsonPrimitive.content)
        assertEquals(
            "C:\\workspace\\uploads\\requirements.md",
            input.getValue("path").jsonPrimitive.content,
        )
    }

    @Test
    fun `image stays in draft when selected model is text only`() {
        assertEquals(
            "当前模型不支持图片，请切换支持视觉输入的模型",
            directWorkInputRestriction(
                contents = listOf(UIMessagePart.Image("content://image")),
                modelInputModalities = listOf("text"),
                compatibilityLevel = AppServerCompatibilityLevel.FULL,
            ),
        )
        assertNull(
            directWorkInputRestriction(
                contents = listOf(UIMessagePart.Image("content://image")),
                modelInputModalities = listOf("text", "image"),
                compatibilityLevel = AppServerCompatibilityLevel.FULL,
            )
        )
    }

    @Test
    fun `text only compatibility blocks attachments but keeps text writable`() {
        assertNull(
            directWorkInputRestriction(
                contents = listOf(UIMessagePart.Text("hello")),
                modelInputModalities = listOf("text"),
                compatibilityLevel = AppServerCompatibilityLevel.TEXT_ONLY,
            )
        )
        assertEquals(
            "当前连接未启用受控附件上传，请配置 Supervisor 或移除附件",
            directWorkInputRestriction(
                contents = listOf(UIMessagePart.Document("content://doc", "spec.md", "text/markdown")),
                modelInputModalities = listOf("text"),
                compatibilityLevel = AppServerCompatibilityLevel.TEXT_ONLY,
            ),
        )
    }
}
