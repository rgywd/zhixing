package me.rerere.rikkahub.data.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AgentFileSystemTest {
    private val model = me.rerere.ai.provider.Model(modelId = "test")
    private val actor = Assistant(chatModelId = model.id, name = "Main", capabilities = setOf("agents"), systemPrompt = "Before")
    private var state = Settings(assistants = listOf(actor), providers = listOf(me.rerere.ai.provider.ProviderSetting.OpenAI(models = listOf(model))))
    private var applied = 0
    private fun files() = AgentFileSystem(actor, { state }, agentConfigTools(actor, { state }, { fn ->
        val next = fn(state)
        state = next.copy(assistants = next.assistants.map { candidate ->
            val old = state.assistants.find { it.id == candidate.id }
            if (old != null && candidate != old) candidate.copy(configRevision = old.configRevision + 1) else candidate
        })
    }, { emptyList() }), Tool(name = "unused", description = "", execute = { error("Unexpected skill call") }), { applied++ })

    @Test fun `files share settings and reject stale writes`() = runBlocking {
        val a = files(); val b = files()
        a.read("/agents/self/AGENT.md"); b.read("/agents/self/AGENT.md")
        a.write("/agents/self/AGENT.md", "After", true)
        assertEquals("After", state.assistants.single().systemPrompt)
        assertEquals(1, applied)
        assertEquals("REVISION_CONFLICT", runCatching { b.write("/agents/self/AGENT.md", "Stale", true) }.exceptionOrNull()?.message)
        assertEquals("After", files().read("/agents/self/AGENT.md")["text"]!!.jsonPrimitive.content)
    }

    @Test fun `new child prompt requires a read and receipts have one effective scope`() = runBlocking {
        val fs = files()
        val id = Uuid.random()
        fs.write("/agents/$id/config.json", """{"name":"Child"}""", false)
        val denied = fs.mount(emptyList()).first { it.name == "workspace_write_file" }.execute(buildJsonObject {
            put("path", "/agents/$id/AGENT.md"); put("text", "Child prompt")
        }).single() as UIMessagePart.Text
        val failure = Json.parseToJsonElement(denied.text).jsonObject
        assertEquals("READ_BEFORE_WRITE", failure["error"]!!.jsonPrimitive.content)
        assertTrue(failure.containsKey("hint"))
        assertEquals("", state.assistants.last().systemPrompt)
        fs.read("/agents/$id/AGENT.md")
        val childResult = fs.write("/agents/$id/AGENT.md", "Child prompt", true)
        assertEquals("new_conversations", childResult["applies_to"]!!.jsonPrimitive.content)
        assertFalse(childResult["result"]!!.jsonObject.containsKey("applies_to"))
        fs.read("/agents/self/AGENT.md")
        val selfResult = fs.write("/agents/self/AGENT.md", "Self prompt", true)
        assertEquals("current_conversation_next_turn", selfResult["applies_to"]!!.jsonPrimitive.content)
        assertFalse(selfResult["result"]!!.jsonObject.containsKey("applies_to"))
        assertEquals(1, applied)
    }

    @Test fun `read config edit round trip preserves defaults and validates before committing`() = runBlocking {
        val fs = files()
        val config = fs.read("/agents/self/config.json")["text"]!!.jsonPrimitive.content
        fs.write("/agents/self/config.json", config.replace("Main", "Renamed"), true)
        assertEquals("Renamed", state.assistants.single().name)
        assertEquals("Before", state.assistants.single().systemPrompt)
        fs.read("/agents/self/config.json")
        assertTrue(runCatching { fs.write("/agents/self/config.json", "{\"api_key\":\"x\"}", true) }.isFailure)
        assertEquals("Renamed", state.assistants.single().name)
    }

    @Test fun `create child through ordinary file tools without workspace or management tools`() = runBlocking {
        val fs = files(); val tools = fs.mount(emptyList())
        assertEquals(setOf("workspace_read_file", "workspace_write_file", "workspace_edit_file"), tools.map { it.name }.toSet())
        val id = Uuid.random()
        val result = tools.first { it.name == "workspace_write_file" }.execute(buildJsonObject {
            put("path", "/agents/$id/config.json"); put("text", """{"name":"Research","capabilities":["javascript"]}"""); put("overwrite", false)
        }).single() as UIMessagePart.Text
        assertTrue(result.text, Json.parseToJsonElement(result.text).jsonObject["success"]!!.jsonPrimitive.boolean)
        assertEquals(actor.id, state.assistants.last().managedBy)
        assertEquals(id, state.assistants.last().id)
        fs.read("/agents/$id/AGENT.md"); fs.write("/agents/$id/AGENT.md", "Research carefully", true)
        assertEquals("Research carefully", state.assistants.last().systemPrompt)
        assertEquals(0, applied)
    }

    @Test fun `path ownership and revocation are checked on every operation`() = runBlocking {
        val fs = files(); val foreign = Assistant(name = "Foreign")
        state = state.copy(assistants = state.assistants + foreign)
        assertTrue(runCatching { fs.read("/agents/${foreign.id}/AGENT.md") }.isFailure)
        assertTrue(runCatching { fs.read("/agents/../settings.json") }.isFailure)
        fs.read("/agents/self/AGENT.md")
        state = state.copy(assistants = listOf(actor.copy(capabilities = emptySet())))
        assertTrue(runCatching { fs.write("/agents/self/AGENT.md", "Denied", true) }.isFailure)
    }

    @Test fun `management permission never grants ordinary workspace access`() = runBlocking {
        var calls = 0
        val fallback = Tool(name = "workspace_read_file", description = "", execute = {
            calls++; listOf(UIMessagePart.Text("workspace contents"))
        })
        val tool = files().mount(listOf(fallback)).first { it.name == fallback.name }
        tool.execute(buildJsonObject { put("path", "/workspace/private.txt") })
        assertEquals(0, calls)
        state = state.copy(assistants = listOf(actor.copy(capabilities = setOf("workspace"))))
        tool.execute(buildJsonObject { put("path", "/workspace/private.txt") })
        assertEquals(1, calls)
        val denied = tool.execute(buildJsonObject { put("path", "/agents/self/AGENT.md") }).single() as UIMessagePart.Text
        assertTrue(denied.text.contains("CAPABILITY_DENIED"))
    }

    @Test fun `ordinary edit preserves unrelated changes and rejects ambiguous replacements`() = runBlocking {
        val fs = files()
        val edit = fs.mount(emptyList()).first { it.name == "workspace_edit_file" }
        edit.execute(buildJsonObject {
            put("path", "/agents/self/AGENT.md"); put("old_text", "Before"); put("new_text", "After After")
        })
        val denied = edit.execute(buildJsonObject {
            put("path", "/agents/self/AGENT.md"); put("old_text", "After"); put("new_text", "Wrong")
        }).single() as UIMessagePart.Text
        assertTrue(denied.text.contains("AMBIGUOUS_MATCH"))
        assertEquals("After After", state.assistants.single().systemPrompt)
    }
}
