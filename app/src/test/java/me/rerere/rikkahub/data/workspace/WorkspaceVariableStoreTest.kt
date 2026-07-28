package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceVariableStoreTest {
    @Test
    fun `parses leading temporary and user declarations without retaining values`() {
        val result = parseWorkspaceVariableDeclarations(
            """
            ${'$'}TEMP_TOKEN=temporary-secret
            ${'$'}${'$'}USER_TOKEN=persistent-secret

            请使用这两个变量调用接口
            """.trimIndent()
        )

        assertEquals(
            listOf(
                WorkspaceVariableDeclaration(
                    name = "TEMP_TOKEN",
                    value = "temporary-secret",
                    scope = WorkspaceVariableScope.TEMPORARY,
                ),
                WorkspaceVariableDeclaration(
                    name = "USER_TOKEN",
                    value = "persistent-secret",
                    scope = WorkspaceVariableScope.USER,
                ),
            ),
            result.declarations,
        )
        assertTrue(result.safeText.contains("`TEMP_TOKEN`"))
        assertTrue(result.safeText.contains("`USER_TOKEN`"))
        assertTrue(result.safeText.contains("请使用这两个变量调用接口"))
        assertTrue(!result.safeText.contains("temporary-secret"))
        assertTrue(!result.safeText.contains("persistent-secret"))
    }

    @Test
    fun `only recognizes a declaration block at the beginning`() {
        val text = """
            请解释下面的 shell：
            ${'$'}TOKEN=example
        """.trimIndent()

        val result = parseWorkspaceVariableDeclarations(text)

        assertTrue(result.declarations.isEmpty())
        assertEquals(text, result.safeText)
    }

    @Test
    fun `empty declaration removes a variable without exposing later text`() {
        val result = parseWorkspaceVariableDeclarations(
            "${'$'}${'$'}OLD_TOKEN=\n删除这个用户变量"
        )

        assertEquals("", result.declarations.single().value)
        assertTrue(result.safeText.contains("已删除"))
        assertTrue(result.safeText.endsWith("删除这个用户变量"))
    }

    @Test
    fun `redacts longer and shorter secret values from tool output`() {
        val result = redactWorkspaceVariableValues(
            text = "token=abc123 and short=abc",
            values = listOf("abc", "abc123"),
        )

        assertEquals(
            "token=[REDACTED_SECRET] and short=[REDACTED_SECRET]",
            result,
        )
    }

    @Test
    fun `temporary variables override user variables with the same name`() {
        val environment = mergeWorkspaceVariableEnvironment(
            userVariables = mapOf("TOKEN" to "user-value", "GLOBAL" to "global-value"),
            temporaryVariables = mapOf("TOKEN" to "temporary-value"),
        )

        assertEquals("temporary-value", environment["TOKEN"])
        assertEquals("global-value", environment["GLOBAL"])
    }
}
