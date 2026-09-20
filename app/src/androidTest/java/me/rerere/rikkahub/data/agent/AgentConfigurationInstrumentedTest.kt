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
            val skills = GlobalContext.get().get<SkillManager>()
            val fs = AgentFileSystem(agent, { store.settingsFlowRaw.first() },
                createAgentConfigTools(agent, store, skills, GlobalContext.get().get()), createAgentSkillTool(skills), {})
            fs.read("/agents/self/AGENT.md")
            fs.write("/agents/self/AGENT.md", "Updated by agent", true)
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
        val store = GlobalContext.get().get<SettingsStore>()
        val original = store.settingsFlowRaw.first()
        val agent = Assistant(name = "Skill acceptance")
        store.update { it.copy(assistants = it.assistants + agent) }
        val fs = AgentFileSystem(agent, { store.settingsFlowRaw.first() },
            createAgentConfigTools(agent, store, skills, GlobalContext.get().get()), createAgentSkillTool(skills), {})
        val path = "/skills/$name/SKILL.md"
        try {
            val content = "---\nname: $name\ndescription: Acceptance test\n---\nUse the provided input."
            fs.read(path)
            val created = fs.write(path, content, true)
            assertTrue(created["success"]!!.jsonPrimitive.boolean)
            assertEquals(content, fs.read(path)["text"]!!.jsonPrimitive.content)
            assertEquals("REVISION_CONFLICT", runCatching { fs.write(path, content, true, "stale") }.exceptionOrNull()?.message)
            assertEquals(content, skills.readSkillContent(name))
        } finally { skills.deleteSkill(name); store.restore(original) }
    }
}
