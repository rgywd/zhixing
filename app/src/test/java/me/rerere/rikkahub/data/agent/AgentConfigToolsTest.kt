package me.rerere.rikkahub.data.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class AgentConfigToolsTest {
    private val model = Model(modelId = "test", displayName = "Test model")
    private val actor = Assistant(name = "Main", chatModelId = model.id)
    private var state = Settings(assistants = listOf(actor), chatModelId = model.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(model), apiKey = "secret-must-not-escape")))
    private val tools = agentConfigTools(actor, { state }, { mutate ->
        val next = mutate(state)
        state = next.copy(assistants = next.assistants.map { candidate ->
            val old = state.assistants.find { it.id == candidate.id }
            if (old != null && old != candidate) candidate.copy(configRevision = old.configRevision + 1) else candidate
        })
    }, { listOf("research") })

    private suspend fun call(name: String, input: String): JsonObject = Json.parseToJsonElement(
        (tools.first { it.name == name }.execute(Json.parseToJsonElement(input)).single() as UIMessagePart.Text).text,
    ).jsonObject

    @Test fun `create a configured child and patch it without modifying its parent`() = runBlocking {
        val created = call("agent_config", """{"action":"create","name":"Research","capabilities":["search","skills"],"skills":["research"]}""")
        assertTrue(created["success"]!!.jsonPrimitive.boolean)
        val child = state.assistants.last()
        assertEquals(actor.id, child.managedBy)
        assertTrue(child.enableWebSearch)
        assertFalse(AgentCapabilities.permits(child, "monthly_spending_summary"))
        assertFalse(AgentCapabilities.permits(child, "agent_config"))
        assertTrue(AgentCapabilities.permits(child, "ask_user"))
        assertEquals(actor, state.assistants.first())
        val changed = call("agent_config", """{"action":"patch","agent_id":"${child.id}","if_revision":0,"description":"Focused research"}""")
        assertTrue(changed["success"]!!.jsonPrimitive.boolean)
        assertEquals(1L, state.assistants.last().configRevision)
        assertEquals(setOf("research"), state.assistants.last().enabledSkills)
        val stale = call("agent_config", """{"action":"patch","agent_id":"${child.id}","if_revision":0,"name":"Stale overwrite"}""")
        assertEquals("REVISION_CONFLICT", stale["error"]!!.jsonPrimitive.content)
        assertEquals("Research", state.assistants.last().name)
    }

    @Test fun `catalog excludes credentials and mutation rejects unknown ids and fields`() = runBlocking {
        assertFalse(call("agent_catalog", "{}").toString().contains("secret-must-not-escape"))
        for (input in listOf(
            """{"action":"create","name":"bad","capabilities":["root_shell"]}""",
            """{"action":"create","name":"bad","api_key":"secret"}""",
            """{"action":"create","name":"bad","skills":["missing"]}""",
            """{"action":"create","name":"bad","capabilities":["agents"]}""",
        )) assertFalse(call("agent_config", input)["success"]!!.jsonPrimitive.boolean)
        assertEquals(1, state.assistants.size)
    }

    @Test fun `one root cannot modify another roots child`() = runBlocking {
        val other = Assistant(name = "Other")
        val foreign = Assistant(name = "Foreign", managedBy = other.id)
        state = state.copy(assistants = state.assistants + other + foreign)
        val result = call("agent_config", """{"action":"patch","agent_id":"${foreign.id}","if_revision":0,"name":"Stolen"}""")
        assertEquals("CAPABILITY_DENIED", result["error"]!!.jsonPrimitive.content)
    }

    @Test fun `legacy tools remain available but explicit capabilities fail closed`() {
        assertTrue(AgentCapabilities.permits(actor, "monthly_spending_summary"))
        val isolated = actor.copy(capabilities = setOf("search"))
        assertTrue(AgentCapabilities.permits(isolated, "search_web"))
        assertFalse(AgentCapabilities.permits(isolated, "workspace_shell"))
        assertFalse(AgentCapabilities.permits(isolated, "unknown_new_tool"))
        assertFalse(AgentCapabilities.permits(isolated.copy(isEnabled = false), "ask_user"))
    }
}
