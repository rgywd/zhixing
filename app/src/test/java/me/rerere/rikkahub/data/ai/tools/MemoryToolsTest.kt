package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.ProfileDimensions
import me.rerere.rikkahub.data.model.ProfileEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class MemoryToolsTest {
    @Test
    fun structuredMemoryRemainsOneModelFacingTool() {
        val tools = listOf(memoryTool())

        assertEquals(1, tools.size)
        assertEquals("memory_tool", tools.single().name)
        assertTrue(tools.single().description.contains("single model-facing memory capability"))

        val schema = tools.single().parameters() as InputSchema.Obj
        val actions = schema.properties.getValue("action")
            .jsonObject.getValue("enum").jsonArray.map { it.toString().trim('"') }
        assertEquals(listOf("create", "edit", "archive", "restore", "delete"), actions)
        assertTrue(schema.properties.containsKey("kind"))
        val dimensions = schema.properties.getValue("dimensionId")
            .jsonObject.getValue("enum").jsonArray.map { it.toString().trim('"') }
        assertEquals(ProfileDimensions.builtIn, dimensions)
    }

    @Test
    fun profileCreationRequiresAndForwardsBuiltInDimension() = runBlocking {
        var creation: Triple<MemoryKind, String, String>? = null
        val tool = memoryTool { kind, content, dimensionId ->
            creation = Triple(kind, content, dimensionId)
            AssistantMemory(1, content, kind, dimensionId = dimensionId)
        }

        tool.execute(
            buildJsonObject {
                put("action", "create")
                put("kind", MemoryKind.PROFILE.name)
                put("content", "User prefers concise Chinese replies.")
                put("dimensionId", ProfileDimensions.PREFERENCES_VALUES)
            }
        )

        assertEquals(
            Triple(
                MemoryKind.PROFILE,
                "User prefers concise Chinese replies.",
                ProfileDimensions.PREFERENCES_VALUES,
            ),
            creation,
        )

        val missingDimension = runCatching {
            tool.execute(
                buildJsonObject {
                    put("action", "create")
                    put("kind", MemoryKind.PROFILE.name)
                    put("content", "User prefers concise Chinese replies.")
                }
            )
        }.exceptionOrNull()
        assertNotNull(missingDimension)
        assertEquals("dimensionId is required when kind is PROFILE", missingDimension?.message)

        val invalidDimension = runCatching {
            tool.execute(
                buildJsonObject {
                    put("action", "create")
                    put("kind", MemoryKind.PROFILE.name)
                    put("content", "User prefers concise Chinese replies.")
                    put("dimensionId", "future_dimension")
                }
            )
        }.exceptionOrNull()
        assertNotNull(invalidDimension)
        assertEquals(
            "unknown dimensionId: future_dimension, must be a built-in profile dimension",
            invalidDimension?.message,
        )
    }

    @Test
    fun contextCreationRejectsDimension() = runBlocking {
        var creation: Triple<MemoryKind, String, String>? = null
        val tool = memoryTool { kind, content, dimensionId ->
            creation = Triple(kind, content, dimensionId)
            AssistantMemory(1, content, kind, dimensionId = dimensionId)
        }

        tool.execute(
            buildJsonObject {
                put("action", "create")
                put("kind", MemoryKind.CONTEXT.name)
                put("content", "User wants this context remembered.")
            }
        )
        assertEquals(
            Triple(MemoryKind.CONTEXT, "User wants this context remembered.", ""),
            creation,
        )

        val failure = runCatching {
            tool.execute(
                buildJsonObject {
                    put("action", "create")
                    put("kind", MemoryKind.CONTEXT.name)
                    put("content", "User wants this context remembered.")
                    put("dimensionId", ProfileDimensions.IDENTITY_CONTEXT)
                }
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("dimensionId must be omitted when kind is CONTEXT", failure?.message)
    }

    @Test
    fun toolResponseExcludesInternalProfileEvidenceAndObservationIds() = runBlocking {
        val tool = memoryTool { kind, content, dimensionId ->
            AssistantMemory(
                id = 17,
                content = content,
                kind = kind,
                dimensionId = dimensionId,
                source = MemorySource.AUTO,
                profileEvidence = listOf(
                    ProfileEvidence("conversation-1", "message-1", "private quote", 1L)
                ),
                supportingObservationIds = listOf(31, 32),
                canonicalKey = "internal-key",
            )
        }

        val result = tool.execute(
            buildJsonObject {
                put("action", "create")
                put("kind", MemoryKind.PROFILE.name)
                put("content", "User prefers concise replies.")
                put("dimensionId", ProfileDimensions.PREFERENCES_VALUES)
            }
        ).single().let { part ->
            Json.parseToJsonElement((part as me.rerere.ai.ui.UIMessagePart.Text).text).jsonObject
        }

        assertEquals("17", result.getValue("id").jsonPrimitive.content)
        assertEquals("User prefers concise replies.", result.getValue("content").jsonPrimitive.content)
        assertFalse("profileEvidence" in result)
        assertFalse("evidenceConversationIds" in result)
        assertFalse("supportingObservationIds" in result)
        assertFalse("canonicalKey" in result)
        assertFalse("confidence" in result)
    }

    @Test
    fun createAndEditRejectBlankContent() = runBlocking {
        val tool = memoryTool()

        for (input in listOf(
            buildJsonObject {
                put("action", "create")
                put("kind", MemoryKind.CONTEXT.name)
                put("content", "   ")
            },
            buildJsonObject {
                put("action", "edit")
                put("id", 1)
                put("content", "\n")
            },
        )) {
            val failure = runCatching { tool.execute(input) }.exceptionOrNull()
            assertEquals("content must not be blank", failure?.message)
        }
    }

    @Test
    fun `lifecycle and delete legacy approval metadata remains compatible`() {
        val tool = memoryTool()

        assertFalse(tool.needsApproval(actionInput("create")))
        assertFalse(tool.needsApproval(actionInput("edit")))
        assertTrue(tool.needsApproval(actionInput("archive")))
        assertTrue(tool.needsApproval(actionInput("restore")))
        assertTrue(tool.needsApproval(actionInput("delete")))
    }

    private fun memoryTool(
        onCreation: suspend (MemoryKind, String, String) -> AssistantMemory = { kind, content, dimensionId ->
            AssistantMemory(1, content, kind, dimensionId = dimensionId)
        },
    ): Tool = buildMemoryTools(
        json = Json,
        onCreation = onCreation,
        onUpdate = { id, content -> AssistantMemory(id, content) },
        onStateChange = { id, state -> AssistantMemory(id, state = state) },
        onDelete = {},
    ).single()

    private fun actionInput(action: String) = buildJsonObject {
        put("action", action)
    }
}
