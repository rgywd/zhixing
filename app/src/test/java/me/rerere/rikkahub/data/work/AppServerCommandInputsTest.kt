package me.rerere.rikkahub.data.work

import me.rerere.rikkahub.data.workflow.codex.CodexAppOption
import me.rerere.rikkahub.data.workflow.codex.CodexPluginOption
import me.rerere.rikkahub.data.workflow.codex.CodexSkillOption
import me.rerere.rikkahub.data.workflow.codex.RuntimeCatalogPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class AppServerCommandInputsTest {
    private val catalog = RuntimeCatalogPayload(
        machineId = "direct",
        cwd = "C:/src/zhixing",
        skills = listOf(CodexSkillOption("imagegen", "C:/skills/imagegen/SKILL.md")),
        plugins = listOf(CodexPluginOption("openai/figma", "figma", installed = true, enabled = true)),
        apps = listOf(CodexAppOption("drive-connector", "drive", isAccessible = true)),
        generatedAt = 1,
    )

    @Test
    fun `slash commands become official structured inputs`() {
        val resolved = AppServerCommandInputs.resolve(
            "/imagegen 生成图，/figma 看设计，/drive 找文件",
            catalog,
        )

        assertEquals("skill", resolved[0]["type"]?.toString()?.trim('"'))
        assertEquals("C:/skills/imagegen/SKILL.md", resolved[0]["path"]?.toString()?.trim('"'))
        assertEquals("plugin://openai/figma", resolved[1]["path"]?.toString()?.trim('"'))
        assertEquals("app://drive-connector", resolved[2]["path"]?.toString()?.trim('"'))
    }

    @Test
    fun `disabled or inaccessible commands are not sent`() {
        val unavailable = catalog.copy(
            skills = catalog.skills.map { it.copy(enabled = false) },
            plugins = catalog.plugins.map { it.copy(enabled = false) },
            apps = catalog.apps.map { it.copy(isAccessible = false) },
        )

        assertEquals(emptyList<Any>(), AppServerCommandInputs.resolve("/imagegen /figma /drive", unavailable))
    }

    @Test
    fun `a duplicate name is claimed by the higher priority skill`() {
        val duplicate = catalog.copy(
            plugins = catalog.plugins + CodexPluginOption("plugin-image", "imagegen", installed = true, enabled = true),
            apps = catalog.apps + CodexAppOption("app-image", "imagegen", isAccessible = true),
        )

        val resolved = AppServerCommandInputs.resolve("/imagegen", duplicate)

        assertEquals(1, resolved.size)
        assertEquals("skill", resolved.single()["type"]?.toString()?.trim('"'))
    }
}
