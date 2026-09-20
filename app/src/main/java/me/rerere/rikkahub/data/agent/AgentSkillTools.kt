package me.rerere.rikkahub.data.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.files.SkillManager
import java.security.MessageDigest

private val skillMutationGate = Mutex()
internal fun skillRevision(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun createAgentSkillTool(skills: SkillManager): Tool = Tool(
    name = "agent_skill",
    description = "Read or write a local shared SKILL.md instruction document. Read first; write requires the exact if_revision returned by read (empty string only for a missing skill). Writes only Markdown, not scripts. Shared edits affect every agent using the skill on its next read. Use agent_config.skills to enable it for an agent. Do not change shared instructions without a user-requested purpose.",
    parameters = { agentSchema(mapOf("action" to "string", "name" to "string", "content" to "string", "if_revision" to "string"), listOf("action", "name")) },
    execute = { input -> agentResult {
        val p = input.jsonObject
        require(p.keys.all { it in setOf("action", "name", "content", "if_revision") }) { "INVALID_INPUT" }
        val name = p.text("name")
        require(name.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) { "INVALID_SKILL_NAME" }
        skillMutationGate.withLock {
            val old = skills.readSkillContent(name)
            val action = p.text("action")
            require(action in setOf("read", "write")) { "INVALID_INPUT" }
            val content = if (action == "write") {
                require(p["if_revision"]?.jsonPrimitive?.takeIf { it.isString }?.content == (old?.let(::skillRevision) ?: "")) { "REVISION_CONFLICT" }
                val proposed = p.text("content")
                require(proposed.length <= 32000) { "INPUT_TOO_LARGE" }
                require(proposed.startsWith("---\n") || proposed.startsWith("---\r\n")) { "SKILL_FRONTMATTER_REQUIRED" }
                val parsed = me.rerere.rikkahub.data.files.SkillFrontmatterParser.parse(proposed)
                require(parsed["name"] == name && !parsed["description"].isNullOrBlank()) { "INVALID_SKILL_METADATA" }
                require(skills.saveSkill(name, proposed) != null) { "SKILL_WRITE_FAILED" }
                require(skills.readSkillContent(name) == proposed) { "SKILL_WRITE_FAILED" }
                proposed
            } else old
            buildJsonObject {
                put("success", true); put("name", name); put("exists", content != null)
                put("content", content); put("revision", content?.let(::skillRevision) ?: "")
            }
        }
    } },
)
