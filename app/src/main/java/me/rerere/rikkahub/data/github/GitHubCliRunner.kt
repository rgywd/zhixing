package me.rerere.rikkahub.data.github

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.AppIdentity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceCommandResult

data class GitHubCliExecution(
    val result: WorkspaceCommandResult,
    val provisioned: Boolean,
)

class GitHubCliNotConfiguredException : Exception(
    "Configure the GitHub token in Settings > About before using gh."
)

class GitHubCliProvisioningException(message: String) : Exception(message)

class GitHubCliRunner(
    private val workspaceRepository: WorkspaceRepository,
    private val tokenProvider: GitHubIssueTokenProvider,
) {
    private val provisionMutex = Mutex()

    suspend fun execute(
        workspaceId: String,
        arguments: List<String>,
        stdin: String?,
        cwd: String?,
    ): GitHubCliExecution {
        val token = tokenProvider.getToken() ?: throw GitHubCliNotConfiguredException()
        val provisioned = ensureInstalled(workspaceId)
        val result = workspaceRepository.executeProgram(
            id = workspaceId,
            program = GH_PROGRAM,
            arguments = arguments,
            environment = githubEnvironment(token),
            cwd = cwd.orEmpty(),
            timeoutMillis = GH_COMMAND_TIMEOUT_MS,
            stdin = stdin?.toByteArray(Charsets.UTF_8) ?: ByteArray(0),
        )
        return GitHubCliExecution(result.scrub(token), provisioned)
    }

    private suspend fun ensureInstalled(workspaceId: String): Boolean {
        if (isInstalled(workspaceId)) return false
        return provisionMutex.withLock {
            if (isInstalled(workspaceId)) return@withLock false
            val install = workspaceRepository.executeCommand(
                id = workspaceId,
                command = INSTALL_COMMAND,
                timeoutMillis = INSTALL_TIMEOUT_MS,
            )
            if (install.exitCode != 0 || install.timedOut || !isInstalled(workspaceId)) {
                val detail = install.stderr.ifBlank { install.stdout }.trim().take(MAX_ERROR_LENGTH)
                throw GitHubCliProvisioningException(
                    if (detail.isBlank()) "Unable to install GitHub CLI in Rootfs."
                    else "Unable to install GitHub CLI in Rootfs: $detail"
                )
            }
            true
        }
    }

    private suspend fun isInstalled(workspaceId: String): Boolean {
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = "test -x $GH_PROGRAM",
        )
        return result.exitCode == 0
    }

    private fun githubEnvironment(token: String): Map<String, String> = mapOf(
        "GH_TOKEN" to token,
        "GH_HOST" to "github.com",
        "GH_REPO" to "${AppIdentity.repositoryOwner}/${AppIdentity.repositoryName}",
        "GH_CONFIG_DIR" to GH_CONFIG_DIR,
        "GH_PROMPT_DISABLED" to "1",
        "GH_NO_UPDATE_NOTIFIER" to "1",
        "GH_NO_EXTENSION_UPDATE_NOTIFIER" to "1",
        "GH_PAGER" to "cat",
        "PAGER" to "cat",
        "NO_COLOR" to "1",
        "GH_TELEMETRY" to "0",
    )

    private fun WorkspaceCommandResult.scrub(secret: String): WorkspaceCommandResult = copy(
        stdout = stdout.replace(secret, REDACTED),
        stderr = stderr.replace(secret, REDACTED),
    )

    private companion object {
        const val GH_PROGRAM = "/usr/bin/gh"
        const val GH_CONFIG_DIR = "/tmp/zhixing-gh"
        const val REDACTED = "[REDACTED]"
        const val MAX_ERROR_LENGTH = 2_000
        const val GH_COMMAND_TIMEOUT_MS = 120_000L
        const val INSTALL_TIMEOUT_MS = 600_000L
        val INSTALL_COMMAND = """
            export DEBIAN_FRONTEND=noninteractive
            apt-get update && apt-get install -y --no-install-recommends ca-certificates gh
        """.trimIndent()
    }
}
