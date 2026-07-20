package me.rerere.rikkahub.ui.pages.workflow.codex

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.work.AppServerAttachmentManifest
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.work.AppServerCompatibilityLevel
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class AppServerUserInputTest {
    @Test
    fun `document uses model visible text because mention is reserved for connectors`() {
        val input = appServerDocumentInput(
            name = "requirements.md",
            path = "C:\\workspace\\uploads\\requirements.md",
            mime = "text/markdown",
        )

        assertEquals("text", input.getValue("type").jsonPrimitive.content)
        val parsed = AppServerAttachmentManifest.parse(input.getValue("text").jsonPrimitive.content)
        assertEquals("", parsed.visibleText)
        assertEquals("requirements.md", parsed.files.single().name)
        assertEquals(
            "C:\\workspace\\uploads\\requirements.md",
            parsed.files.single().path,
        )
        assertEquals("text/markdown", parsed.files.single().mime)
    }

    @Test
    fun `document metadata is json escaped without changing the trusted local path`() {
        val input = appServerDocumentInput(
            name = "notes`draft\n.md",
            path = "C:\\workspace\\uploads\\notes`draft.md",
            mime = "text/markdown",
        )

        val parsed = AppServerAttachmentManifest.parse(input.getValue("text").jsonPrimitive.content)
        assertEquals("notes`draft\n.md", parsed.files.single().name)
        assertEquals("C:\\workspace\\uploads\\notes`draft.md", parsed.files.single().path)
        assertEquals("text", input.getValue("type").jsonPrimitive.content)
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
