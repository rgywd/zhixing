package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolsTest {
    @Test
    fun structuredMemoryRemainsOneModelFacingTool() {
        val tools = buildMemoryTools(
            json = Json,
            onCreation = { kind, content -> AssistantMemory(1, content, kind) },
            onUpdate = { id, content -> AssistantMemory(id, content) },
            onStateChange = { id, state -> AssistantMemory(id, state = state) },
            onDelete = {},
        )

        assertEquals(1, tools.size)
        assertEquals("memory_tool", tools.single().name)
        assertTrue(tools.single().description.contains("single model-facing memory capability"))

        val schema = tools.single().parameters() as InputSchema.Obj
        val actions = schema.properties.getValue("action")
            .jsonObject.getValue("enum").jsonArray.map { it.toString().trim('"') }
        assertEquals(listOf("create", "edit", "archive", "restore", "delete"), actions)
        assertTrue(schema.properties.containsKey("kind"))
    }
}
