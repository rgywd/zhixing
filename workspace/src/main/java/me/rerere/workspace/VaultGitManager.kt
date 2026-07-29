package me.rerere.workspace

import java.net.URI

enum class VaultGitAvailability {
    READY,
    ROOTFS_REQUIRED,
    GIT_REQUIRED,
}

data class VaultGitStatus(
    val availability: VaultGitAvailability,
    val isRepository: Boolean = false,
    val remoteUrl: String? = null,
    val branch: String? = null,
    val hasChanges: Boolean = false,
) {
    val isBound: Boolean
        get() = availability == VaultGitAvailability.READY &&
            isRepository &&
            !remoteUrl.isNullOrBlank()
}

data class VaultGitBindResult(
    val status: VaultGitStatus,
    val repositoryCreated: Boolean,
    val remoteChanged: Boolean,
)

class VaultGitRemoteConflictException : IllegalStateException(
    "A different origin remote is already configured",
)

/**
 * Maintains the Git identity of an existing OrbitOS vault.
 *
 * Binding is deliberately local-only: it initializes `.git` when needed and configures `origin`.
 * It never clones, fetches, pulls, commits, merges, or pushes user content.
 */
class VaultGitManager(
    private val workspaceManager: WorkspaceManager,
) {
    fun status(root: String): VaultGitStatus {
        requireVault(root)
        if (!workspaceManager.hasRootfs(root)) {
            return VaultGitStatus(VaultGitAvailability.ROOTFS_REQUIRED)
        }

        val version = git(root, "--version")
        if (!version.isSuccess) {
            return VaultGitStatus(VaultGitAvailability.GIT_REQUIRED)
        }

        val repository = git(root, "rev-parse", "--is-inside-work-tree")
        if (!repository.isSuccess || repository.stdout.trim() != "true") {
            return VaultGitStatus(VaultGitAvailability.READY)
        }

        val remote = git(root, "remote", "get-url", ORIGIN_REMOTE)
            .takeIf { it.isSuccess }
            ?.stdout
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val branch = git(root, "symbolic-ref", "--quiet", "--short", "HEAD")
            .takeIf { it.isSuccess }
            ?.stdout
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val changes = git(root, "status", "--porcelain", "--untracked-files=normal")

        return VaultGitStatus(
            availability = VaultGitAvailability.READY,
            isRepository = true,
            remoteUrl = remote?.toDisplayRemoteUrl(),
            branch = branch,
            hasChanges = changes.isSuccess && changes.stdout.isNotBlank(),
        )
    }

    fun bind(
        root: String,
        remoteUrl: String,
        replaceExisting: Boolean = false,
    ): VaultGitBindResult {
        requireVault(root)
        val normalizedRemote = validateRemoteUrl(remoteUrl)
        val initial = status(root)
        check(initial.availability == VaultGitAvailability.READY) {
            when (initial.availability) {
                VaultGitAvailability.ROOTFS_REQUIRED -> "Rootfs is required to bind Git"
                VaultGitAvailability.GIT_REQUIRED -> "Git is not installed in Rootfs"
                VaultGitAvailability.READY -> "Git is unavailable"
            }
        }

        var repositoryCreated = false
        if (!initial.isRepository) {
            git(root, "init", "-b", DEFAULT_BRANCH).requireSuccess("Initialize Git repository")
            repositoryCreated = true
        }

        val currentRemote = git(root, "remote", "get-url", ORIGIN_REMOTE)
            .takeIf { it.isSuccess }
            ?.stdout
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val remoteChanged = when {
            currentRemote == null -> {
                git(root, "remote", "add", ORIGIN_REMOTE, normalizedRemote)
                    .requireSuccess("Add Git origin")
                true
            }

            currentRemote == normalizedRemote -> false
            !replaceExisting -> throw VaultGitRemoteConflictException()
            else -> {
                git(root, "remote", "set-url", ORIGIN_REMOTE, normalizedRemote)
                    .requireSuccess("Update Git origin")
                true
            }
        }

        ensureEnvironmentGitIgnore(root)
        return VaultGitBindResult(
            status = status(root),
            repositoryCreated = repositoryCreated,
            remoteChanged = remoteChanged,
        )
    }

    fun installGit(root: String): VaultGitStatus {
        requireVault(root)
        check(workspaceManager.hasRootfs(root)) {
            "Rootfs is required to install Git"
        }
        val initial = status(root)
        if (initial.availability == VaultGitAvailability.READY) return initial

        aptGet(root, "update").requireSuccess("Update package index")
        aptGet(root, "install", "-y", "git").requireSuccess("Install Git")
        return status(root).also { installed ->
            check(installed.availability == VaultGitAvailability.READY) {
                "Git installation completed but Git is still unavailable"
            }
        }
    }

    fun ensureEnvironmentGitIgnore(root: String) {
        requireVault(root)
        val path = "${KnowledgeSpaceManager.VAULT_DIR}/.gitignore"
        val existing = if (workspaceManager.exists(root, path)) {
            workspaceManager.readText(root, path)
        } else {
            ""
        }
        val existingLines = existing.lineSequence().map(String::trim).toSet()
        val missingPatterns = ENVIRONMENT_IGNORE_PATTERNS.filterNot(existingLines::contains)
        if (missingPatterns.isEmpty()) return

        val separator = when {
            existing.isBlank() -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val addition = buildString {
            append(separator)
            appendLine(ENVIRONMENT_IGNORE_COMMENT)
            missingPatterns.forEach(::appendLine)
        }
        workspaceManager.writeText(
            root = root,
            path = path,
            text = existing + addition,
            overwrite = true,
        )
    }

    private fun requireVault(root: String) {
        require(
            workspaceManager.exists(root, KnowledgeSpaceManager.VAULT_DIR) &&
                workspaceManager.exists(root, KnowledgeSpaceManager.AGENTS_FILE)
        ) {
            "Knowledge vault is not initialized"
        }
    }

    private fun git(root: String, vararg arguments: String): WorkspaceCommandResult =
        workspaceManager.executeProgram(
            root = root,
            program = "git",
            arguments = arguments.toList(),
            cwd = KnowledgeSpaceManager.VAULT_DIR,
        )

    private fun aptGet(root: String, vararg arguments: String): WorkspaceCommandResult =
        workspaceManager.executeProgram(
            root = root,
            program = "apt-get",
            arguments = arguments.toList(),
            environment = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
            cwd = KnowledgeSpaceManager.VAULT_DIR,
            timeoutMillis = GIT_INSTALL_TIMEOUT_MS,
        )

    private fun validateRemoteUrl(remoteUrl: String): String {
        val candidate = remoteUrl.trim()
        require(candidate.isNotBlank()) { "Git remote URL is required" }
        require(
            candidate.none { it == '\u0000' || it == '\r' || it == '\n' || it.isWhitespace() }
        ) {
            "Git remote URL contains invalid characters"
        }
        if (SCP_STYLE_REMOTE.matches(candidate)) return candidate

        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: throw IllegalArgumentException("Git remote URL is invalid")
        require(uri.scheme == "https" || uri.scheme == "ssh") {
            "Git remote URL must use HTTPS or SSH"
        }
        require(!uri.host.isNullOrBlank()) { "Git remote URL must include a host" }
        require(!uri.path.isNullOrBlank() && uri.path != "/") {
            "Git remote URL must include a repository path"
        }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "Git remote URL must not contain query parameters or fragments"
        }
        if (uri.scheme == "https") {
            require(uri.userInfo.isNullOrBlank()) {
                "Git remote URL must not contain embedded credentials"
            }
        } else {
            require(uri.userInfo?.contains(':') != true) {
                "Git remote URL must not contain embedded credentials"
            }
        }
        return candidate
    }

    private fun String.toDisplayRemoteUrl(): String {
        if (SCP_STYLE_REMOTE.matches(this)) return this
        val uri = runCatching { URI(this) }.getOrNull() ?: return this
        if (uri.userInfo.isNullOrBlank() || uri.scheme != "https") return this
        return URI(
            uri.scheme,
            null,
            uri.host,
            uri.port,
            uri.path,
            uri.query,
            uri.fragment,
        ).toString()
    }

    private fun WorkspaceCommandResult.requireSuccess(action: String) {
        check(isSuccess) {
            val reason = when {
                timedOut -> "timed out"
                else -> stderr.trim().ifBlank { "exit code $exitCode" }
            }
            "$action failed: $reason"
        }
    }

    private val WorkspaceCommandResult.isSuccess: Boolean
        get() = exitCode == 0 && !timedOut

    private companion object {
        private const val ORIGIN_REMOTE = "origin"
        private const val DEFAULT_BRANCH = "main"
        private const val GIT_INSTALL_TIMEOUT_MS = 15 * 60 * 1000L
        private const val ENVIRONMENT_IGNORE_COMMENT = "# Local environment variables"
        private val ENVIRONMENT_IGNORE_PATTERNS = listOf(
            ".env",
            ".env.*",
            "!.env.example",
        )
        private val SCP_STYLE_REMOTE = Regex(
            """[A-Za-z][A-Za-z0-9._-]*@[A-Za-z0-9.-]+:[^\s]+""",
        )
    }
}
