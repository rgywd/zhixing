package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.MemoryDocument
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDocumentPromptsTest {
    @Test
    fun promptLoadsOnlyPinnedDocumentsAndRoutesEverythingElseProgressively() {
        val prompt = buildMemoryDocumentPrompt(
            listOf(
                document("/profile.md", "Profile", "- [stated] 用户使用中文。"),
                document("/preferences.md", "Preferences", "- [stated] 用户偏好先给结论。"),
                document(
                    "/areas/zhixing.md",
                    "Zhixing project",
                    "- [stated] 这是不应常驻的项目正文。",
                    aliases = listOf("知行", "Zhixing"),
                ),
            )
        )

        assertFalse(prompt.contains("/areas/zhixing.md"))
        assertTrue(prompt.contains("Memory writes are optional"))
        assertTrue(prompt.contains("do not call a memory tool"))
        assertTrue(prompt.contains("materially improve the answer"))
        assertTrue(prompt.contains("memory_find for a focused lookup"))
        assertTrue(prompt.contains("memory_list for explicit browsing or ambiguity"))
        assertTrue(prompt.contains("routing descriptors only"))
        assertTrue(prompt.contains("are not evidence"))
        assertTrue(prompt.contains("never return content or sources"))
        assertTrue(prompt.contains("one exact document per call"))
        assertTrue(prompt.contains("multiple sequential memory_read calls"))
        assertTrue(prompt.contains("directly relevant path"))
        assertTrue(prompt.contains("stop once you have enough"))
        assertTrue(prompt.contains("do not call memory_write"))
        assertTrue(prompt.contains("multiple memory_write calls"))
        assertTrue(prompt.contains("version returned by the preceding result"))
        assertTrue(prompt.contains("explicitly asks to remember, reorganize, correct, or delete"))
        assertTrue(prompt.contains("foreground chat run"))
        assertFalse(prompt.contains("no_change"))
        assertFalse(prompt.contains("terminal memory_write"))
        assertFalse(prompt.contains("run-finalization"))
        assertTrue(prompt.contains("conversation_read"))
        assertTrue(prompt.contains("source_ref"))
        assertTrue(prompt.contains("answered ask_user"))
        assertTrue(prompt.contains("canonical date/unit forms are guidance"))
        assertTrue(prompt.contains("Existing archive documents may be edited or deleted"))
        assertTrue(prompt.contains("no background or follow-up memory pass"))
        assertTrue(prompt.contains("Use canonical memory fact formats"))
        assertTrue(prompt.contains("YYYY-MM-DD"))
        assertTrue(prompt.contains("HH:mm"))
        assertTrue(prompt.contains("Asia/Shanghai"))
        assertFalse(prompt.contains("知行"))
        assertFalse(prompt.contains("Zhixing project"))
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

    @Test
    fun virtualMarkdownUsesTypedEmptySources() {
        val markdown = renderMemoryDocumentMarkdown(
            document("/profile.md", "Profile", "- [stated] 用户偏好中文。")
        )

        assertTrue(markdown.contains("aliases: []"))
        assertTrue(markdown.contains("sources: []"))
    }

    private fun document(
        path: String,
        description: String,
        content: String,
        aliases: List<String> = emptyList(),
    ) = MemoryDocument(
        scopeId = "__global__",
        path = path,
        name = path,
        description = description,
        aliases = aliases,
        content = content,
    )
}
