package me.rerere.rikkahub.data.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class AgentConfigurationInstrumentedTest {
    @Test fun staleUiEditPreservesAgentChangeAndPreviousVersion() = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = store.settingsFlowRaw.first()
        val agent = Assistant(name = "Before")
        try {
            store.update { it.copy(assistants = it.assistants + agent) }
            val uiSnapshot = store.settingsFlowRaw.first()
            store.update { current -> current.copy(assistants = current.assistants.map {
                if (it.id == agent.id) it.copy(systemPrompt = "Updated by agent") else it
            }) }
            store.update(uiSnapshot.copy(assistants = uiSnapshot.assistants.map {
                if (it.id == agent.id) it.copy(name = "Updated by UI") else it
            }))
            val actual = store.settingsFlowRaw.first().assistants.single { it.id == agent.id }
            assertEquals("Updated by agent", actual.systemPrompt)
            assertEquals("Updated by UI", actual.name)
            assertEquals(2L, actual.configRevision)
            val previous = JsonInstant.decodeFromString<Assistant>(actual.previousConfiguration!!)
            assertEquals("Before", previous.name)
            assertEquals("Updated by agent", previous.systemPrompt)
        } finally { store.restore(original) }
    }

    @Test fun skillWritesRequireMatchingRevisionAndRemainReadable() = runBlocking {
        val skills = GlobalContext.get().get<SkillManager>()
        val name = "agent-test-${System.currentTimeMillis()}"
        val tool = createAgentSkillTool(skills)
        suspend fun call(action: String, revision: String = "", content: String = ""): JsonObject {
            val output = tool.execute(buildJsonObject {
                put("action", action); put("name", name); put("if_revision", revision); put("content", content)
            })
            return Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        }
        try {
            val content = "---\nname: $name\ndescription: Acceptance test\n---\nUse the provided input."
            val created = call("write", content = content)
            assertTrue(created["success"]!!.jsonPrimitive.boolean)
            assertEquals(content, call("read")["content"]!!.jsonPrimitive.content)
            assertEquals("REVISION_CONFLICT", call("write", "stale", content)["error"]!!.jsonPrimitive.content)
            assertEquals(content, skills.readSkillContent(name))
        } finally { skills.deleteSkill(name) }
    }
}
