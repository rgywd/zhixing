package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppIdentity
import me.rerere.rikkahub.data.github.GitHubCliExecution
import me.rerere.rikkahub.data.github.GitHubCliNotConfiguredException
import me.rerere.rikkahub.data.github.GitHubCliProvisioningException
import me.rerere.rikkahub.data.github.GitHubCliRunner

private const val MAX_ARGUMENT_COUNT = 64
private const val MAX_ARGUMENT_LENGTH = 8_000
private const val MAX_STDIN_LENGTH = 64_000
private const val ISSUE_COMMAND = "issue"

private val READ_ONLY_ISSUE_SUBCOMMANDS = setOf("list", "status", "view")
private val WRITE_ISSUE_SUBCOMMANDS = setOf(
    "close",
    "comment",
    "create",
    "delete",
    "edit",
    "lock",
    "pin",
    "reopen",
    "unlock",
    "unpin",
)

internal data class GitHubCliInvocation(
    val arguments: List<String>,
    val stdin: String?,
)

internal fun buildGitHubCliTool(
    workspaceId: String,
    cwd: String?,
    runner: GitHubCliRunner,
): Tool = buildGitHubCliTool { invocation ->
    runner.execute(workspaceId, invocation.arguments, invocation.stdin, cwd)
}

internal fun buildGitHubCliTool(
    execute: suspend (GitHubCliInvocation) -> GitHubCliExecution,
): Tool = Tool(
    name = "gh",
    description = """
        Run supported GitHub CLI issue commands against ${AppIdentity.repositoryOwner}/${AppIdentity.repositoryName}.
        Pass arguments after the gh executable, for example ["issue", "list"] or
        ["issue", "create", "--title", "...", "--body-file", "-"]. Use stdin with --body-file -.
        Rootfs provisioning and authentication are handled by Zhixing. Do not request or expose tokens.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("args", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Arguments after gh. Only supported gh issue subcommands are allowed.")
                })
                put("stdin", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional standard input, such as an issue body used with --body-file -.")
                })
            },
            required = listOf("args"),
        )
    },
    // 首次调用可能会在 Rootfs 中安装 gh；沿用旧 Issue 工具的逐次审批边界。
    needsApproval = { true },
    execute = { input ->
        val invocation = parseGitHubCliInvocation(input.jsonObject)
        try {
            val execution = execute(invocation)
            listOf(UIMessagePart.Text(githubCliResult(execution)))
        } catch (error: GitHubCliNotConfiguredException) {
            listOf(UIMessagePart.Text(githubCliError("NOT_CONFIGURED", error.message.orEmpty())))
        } catch (error: GitHubCliProvisioningException) {
            listOf(UIMessagePart.Text(githubCliError("PROVISIONING_FAILED", error.message.orEmpty())))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            listOf(UIMessagePart.Text(githubCliError("EXECUTION_FAILED", "Unable to run gh.")))
        }
    },
)

internal fun parseGitHubCliInvocation(input: JsonObject): GitHubCliInvocation {
    val arguments = input["args"]?.jsonArray?.map { element ->
        element.jsonPrimitive.contentOrNull ?: error("args must contain only strings")
    } ?: error("args is required")
    require(arguments.size in 2..MAX_ARGUMENT_COUNT) { "args must contain between 2 and $MAX_ARGUMENT_COUNT items" }
    require(arguments.none { it.isBlank() || it.length > MAX_ARGUMENT_LENGTH || it.contains('\u0000') }) {
        "args contains an invalid value"
    }
    require(arguments.first() == ISSUE_COMMAND) { "Only gh issue commands are supported" }

    val subcommand = arguments[1]
    when (subcommand) {
        in READ_ONLY_ISSUE_SUBCOMMANDS -> Unit
        in WRITE_ISSUE_SUBCOMMANDS -> Unit
        else -> throw IllegalArgumentException("Unsupported gh issue subcommand: $subcommand")
    }
    validateRepository(arguments)
    validateArguments(arguments)

    val stdin = input["stdin"]?.jsonPrimitive?.contentOrNull
    require(stdin == null || stdin.length <= MAX_STDIN_LENGTH) { "stdin is too large" }
    return GitHubCliInvocation(arguments, stdin)
}

private fun validateRepository(arguments: List<String>) {
    arguments.forEachIndexed { index, argument ->
        val repository = when {
            argument == "--repo" || argument == "-R" -> arguments.getOrNull(index + 1)
                ?: throw IllegalArgumentException("$argument requires a repository")
            argument.startsWith("--repo=") -> argument.substringAfter('=')
            argument.startsWith("-R") && argument.length > 2 -> argument.substring(2)
            else -> null
        }
        if (repository != null) {
            require(repository == "${AppIdentity.repositoryOwner}/${AppIdentity.repositoryName}") {
                "gh is restricted to ${AppIdentity.repositoryOwner}/${AppIdentity.repositoryName}"
            }
        }
    }
}

private fun validateArguments(arguments: List<String>) {
    require(arguments.none { it == "--hostname" || it.startsWith("--hostname=") }) {
        "Changing the GitHub host is not allowed"
    }
    require(arguments.none { it == "--web" }) { "Browser mode is not supported" }

    val bodyFileIndexes = arguments.indices.filter { arguments[it] in setOf("--body-file", "-F") }
    require(bodyFileIndexes.all { arguments.getOrNull(it + 1) == "-" }) {
        "--body-file/-F must read from stdin"
    }
    require(arguments.none { it.startsWith("-F") && it != "-F" }) {
        "Attached -F values are not allowed; use -F - with stdin"
    }
    require(arguments.filter { it.startsWith("--body-file=") }.all { it.substringAfter('=') == "-" }) {
        "--body-file must read from stdin"
    }

    arguments.filter { it.startsWith("https://github.com/") }.forEach { target ->
        require(target.startsWith("${AppIdentity.developmentRepositoryUrl}/issues/")) {
            "Issue URLs must target ${AppIdentity.repositoryOwner}/${AppIdentity.repositoryName}"
        }
    }
}

private fun githubCliResult(execution: GitHubCliExecution): String = buildJsonObject {
    put("status", if (execution.result.exitCode == 0) "COMPLETED" else "FAILED")
    put("exitCode", execution.result.exitCode)
    put("stdout", execution.result.stdout)
    put("stderr", execution.result.stderr)
    put("timedOut", execution.result.timedOut)
    put("truncated", execution.result.truncated)
    put("provisioned", execution.provisioned)
}.toString()

private fun githubCliError(status: String, message: String): String = buildJsonObject {
    put("status", status)
    put("message", message)
}.toString()
