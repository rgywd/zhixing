package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.MemoryDocument
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDocumentPromptsTest {
    @Test
    fun promptLoadsOnlyPinnedDocumentsAndRoutesEverythingElseByListing() {
        val prompt = buildMemoryDocumentPrompt(
            listOf(
                document("/profile.md", "Profile", "- [stated] 用户使用中文。"),
                document("/preferences.md", "Preferences", "- [stated] 用户偏好先给结论。"),
                document("/areas/zhixing.md", "Zhixing project", "- [stated] 这是不应常驻的项目正文。"),
            )
        )

        assertTrue(prompt.contains("/areas/zhixing.md"))
        assertTrue(prompt.contains("Memory writes are optional"))
        assertTrue(prompt.contains("do not call a memory tool"))
        assertTrue(prompt.contains("materially improve the answer"))
        assertTrue(prompt.contains("one document per call"))
        assertTrue(prompt.contains("multiple sequential memory_read calls"))
        assertTrue(prompt.contains("directly relevant relationship"))
        assertTrue(prompt.contains("stop once you have enough"))
        assertTrue(prompt.contains("do not call memory_write"))
        assertTrue(prompt.contains("multiple memory_write calls"))
        assertTrue(prompt.contains("version returned by the preceding result"))
        assertTrue(prompt.contains("explicitly asks to remember, correct, or delete"))
        assertTrue(prompt.contains("foreground chat run"))
        assertFalse(prompt.contains("no_change"))
        assertFalse(prompt.contains("terminal memory_write"))
        assertFalse(prompt.contains("run-finalization"))
        assertTrue(prompt.contains("app binds its current conversation and message IDs"))
        assertTrue(prompt.contains("no background or follow-up memory pass"))
        assertTrue(prompt.contains("Zhixing project"))
        assertFalse(prompt.contains("这是不应常驻的项目正文"))
        assertTrue(prompt.contains("用户使用中文"))
        assertTrue(prompt.contains("用户偏好先给结论"))
        assertTrue(prompt.contains("past conversation search is a separate raw-history capability"))
    }

    @Test
    fun promptKeepsCompletePinnedFileWithinValidatedStorageBudget() {
        val content = "- [stated] " + "x".repeat(16_000)
        val prompt = buildMemoryDocumentPrompt(
            listOf(document("/profile.md", "Profile", content))
        )

        assertTrue(prompt.length <= MEMORY_DOCUMENT_PROMPT_CHAR_LIMIT)
        assertTrue(prompt.contains(content))
    }

    private fun document(path: String, description: String, content: String) = MemoryDocument(
        scopeId = "__global__",
        path = path,
        name = path,
        description = description,
        content = content,
    )
}
