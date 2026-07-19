package me.rerere.rikkahub.ui.pages.workflow.codex

import kotlinx.serialization.json.jsonPrimitive
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
}
