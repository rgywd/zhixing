package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.github.GitHubCliExecution
import me.rerere.rikkahub.data.github.GitHubCliNotConfiguredException
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCliToolTest {
    @Test
    fun `all gh commands require approval`() {
        val tool = buildGitHubCliTool { successfulExecution() }

        assertTrue(tool.needsApproval(input("issue", "list")))
        assertTrue(tool.needsApproval(input("issue", "view", "42")))
        assertTrue(tool.needsApproval(input("issue", "create", "--title", "New issue")))
        assertTrue(tool.needsApproval(input("issue", "close", "42")))
    }

    @Test
    fun `executes standard gh arguments and returns command result`() = runBlocking {
        var captured: GitHubCliInvocation? = null
        val tool = buildGitHubCliTool { invocation ->
            captured = invocation
            successfulExecution(provisioned = true)
        }
        val output = tool.execute(
            input(
                "issue",
                "create",
                "--title",
                "bug: test",
                "--body-file",
                "-",
                stdin = "body",
            )
        ).single() as UIMessagePart.Text

        assertEquals(listOf("issue", "create", "--title", "bug: test", "--body-file", "-"), captured?.arguments)
        assertEquals("body", captured?.stdin)
        val result = Json.parseToJsonElement(output.text).jsonObject
        assertEquals("COMPLETED", result.getValue("status").jsonPrimitive.content)
        assertEquals("true", result.getValue("provisioned").jsonPrimitive.content)
    }

    @Test
    fun `rejects commands outside issue boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseGitHubCliInvocation(input("api", "repos/rgywd/zhixing"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseGitHubCliInvocation(input("issue", "develop", "42"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseGitHubCliInvocation(input("issue", "list", "--repo", "someone/else"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseGitHubCliInvocation(input("issue", "create", "--body-file", "/root/secret"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseGitHubCliInvocation(input("issue", "comment", "42", "-F", "/root/secret"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseGitHubCliInvocation(input("issue", "comment", "42", "-F=/root/secret"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseGitHubCliInvocation(input("issue", "view", "https://github.com/someone/else/issues/1"))
        }
    }

    @Test
    fun `reports missing configured token without exposing credentials`() = runBlocking {
        val tool = buildGitHubCliTool { throw GitHubCliNotConfiguredException() }
        val output = tool.execute(input("issue", "list")).single() as UIMessagePart.Text
        val result = Json.parseToJsonElement(output.text).jsonObject

        assertEquals("NOT_CONFIGURED", result.getValue("status").jsonPrimitive.content)
        assertTrue(result.getValue("message").jsonPrimitive.content.contains("Settings > About"))
    }

    private fun input(vararg arguments: String, stdin: String? = null) = buildJsonObject {
        put("args", buildJsonArray { arguments.forEach { add(it) } })
        stdin?.let { put("stdin", it) }
    }

    private fun successfulExecution(provisioned: Boolean = false) = GitHubCliExecution(
        result = WorkspaceCommandResult(
            exitCode = 0,
            stdout = "https://github.com/rgywd/zhixing/issues/42\n",
            stderr = "",
        ),
        provisioned = provisioned,
    )
}
