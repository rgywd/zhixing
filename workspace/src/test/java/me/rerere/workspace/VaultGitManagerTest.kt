package me.rerere.workspace

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultGitManagerTest {
    @Test
    fun bindInitializesRepositoryAndOriginWithoutSynchronizingContent() {
        val fixture = fixture()

        val result = fixture.git.bind(
            root = fixture.root,
            remoteUrl = "https://github.com/example/vault.git",
        )

        assertTrue(result.repositoryCreated)
        assertTrue(result.remoteChanged)
        assertTrue(result.status.isBound)
        assertEquals("https://github.com/example/vault.git", result.status.remoteUrl)
        assertEquals("main", result.status.branch)
        assertTrue(fixture.runner.repository)
        assertEquals("https://github.com/example/vault.git", fixture.runner.remote)
        assertTrue(
            fixture.workspace.readText(fixture.root, "vault/.gitignore")
                .contains(".env.*")
        )
        assertFalse(
            fixture.runner.commands.any { command ->
                command.firstOrNull() in setOf("clone", "fetch", "pull", "merge", "commit", "push")
            }
        )
    }

    @Test
    fun differentOriginRequiresExplicitReplacement() {
        val fixture = fixture()
        fixture.runner.repository = true
        fixture.runner.remote = "https://github.com/example/old.git"

        assertThrows(VaultGitRemoteConflictException::class.java) {
            fixture.git.bind(
                root = fixture.root,
                remoteUrl = "https://github.com/example/new.git",
            )
        }
        assertEquals("https://github.com/example/old.git", fixture.runner.remote)

        val replaced = fixture.git.bind(
            root = fixture.root,
            remoteUrl = "https://github.com/example/new.git",
            replaceExisting = true,
        )

        assertFalse(replaced.repositoryCreated)
        assertTrue(replaced.remoteChanged)
        assertEquals("https://github.com/example/new.git", replaced.status.remoteUrl)
    }

    @Test
    fun embeddedHttpsCredentialsAreRejectedBeforeGitRuns() {
        val fixture = fixture()

        assertThrows(IllegalArgumentException::class.java) {
            fixture.git.bind(
                root = fixture.root,
                remoteUrl = "https://token@example.com/owner/vault.git",
            )
        }

        assertTrue(fixture.runner.commands.isEmpty())
    }

    @Test
    fun statusExplainsWhenRootfsIsMissing() {
        val fixture = fixture(rootfsReady = false)

        val status = fixture.git.status(fixture.root)

        assertEquals(VaultGitAvailability.ROOTFS_REQUIRED, status.availability)
        assertFalse(status.isRepository)
        assertTrue(fixture.runner.commands.isEmpty())
    }

    @Test
    fun installGitUsesPackageManagerWithoutShellInterpolation() {
        val fixture = fixture(gitInstalled = false)

        val status = fixture.git.installGit(fixture.root)

        assertEquals(VaultGitAvailability.READY, status.availability)
        assertEquals(
            listOf(
                "apt-get" to listOf("update"),
                "apt-get" to listOf("install", "-y", "git"),
            ),
            fixture.runner.programs.filter { it.first == "apt-get" },
        )
    }

    private fun fixture(
        rootfsReady: Boolean = true,
        gitInstalled: Boolean = true,
    ): Fixture {
        val baseDir = Files.createTempDirectory("vault-git-test").toFile()
        val runner = FakeGitRunner(gitInstalled = gitInstalled)
        val workspace = WorkspaceManager(baseDir, shellRunner = runner)
        val root = "test-workspace"
        workspace.ensureWorkspace(root)
        if (rootfsReady) {
            File(workspace.linuxDir(root), "bin/sh").apply {
                parentFile?.mkdirs()
                writeText("")
            }
        }
        KnowledgeSpaceManager(workspace).initialize(root, "Test Vault")
        return Fixture(
            root = root,
            workspace = workspace,
            runner = runner,
            git = VaultGitManager(workspace),
        )
    }

    private data class Fixture(
        val root: String,
        val workspace: WorkspaceManager,
        val runner: FakeGitRunner,
        val git: VaultGitManager,
    )

    private class FakeGitRunner(
        var gitInstalled: Boolean,
    ) : WorkspaceShellRunner {
        var repository: Boolean = false
        var remote: String? = null
        var branch: String = "main"
        var hasChanges: Boolean = false
        val commands = mutableListOf<List<String>>()
        val programs = mutableListOf<Pair<String, List<String>>>()

        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult =
            error("Shell command execution was not expected")

        override fun executeProgram(context: WorkspaceProgramContext): WorkspaceCommandResult {
            programs += context.program to context.arguments
            val args = context.arguments
            if (context.program == "apt-get") {
                return when (args) {
                    listOf("update") -> success()
                    listOf("install", "-y", "git") -> {
                        gitInstalled = true
                        success()
                    }
                    else -> failure("Unexpected apt-get command: ${args.joinToString(" ")}")
                }
            }
            assertEquals("git", context.program)
            commands += args
            return when {
                args == listOf("--version") ->
                    if (gitInstalled) success("git version 2.50.0\n") else failure("git not found")
                args == listOf("rev-parse", "--is-inside-work-tree") ->
                    if (repository) success("true\n") else failure("not a git repository")

                args == listOf("remote", "get-url", "origin") ->
                    remote?.let { success("$it\n") } ?: failure("No such remote")

                args == listOf("symbolic-ref", "--quiet", "--short", "HEAD") ->
                    success("$branch\n")

                args == listOf("status", "--porcelain", "--untracked-files=normal") ->
                    success(if (hasChanges) " M note.md\n" else "")

                args == listOf("init", "-b", "main") -> {
                    repository = true
                    branch = "main"
                    success("Initialized empty Git repository")
                }

                args.size == 4 && args.take(3) == listOf("remote", "add", "origin") -> {
                    remote = args.last()
                    success()
                }

                args.size == 4 && args.take(3) == listOf("remote", "set-url", "origin") -> {
                    remote = args.last()
                    success()
                }

                else -> failure("Unexpected Git command: ${args.joinToString(" ")}")
            }
        }

        private fun success(stdout: String = "") =
            WorkspaceCommandResult(exitCode = 0, stdout = stdout, stderr = "")

        private fun failure(stderr: String) =
            WorkspaceCommandResult(exitCode = 1, stdout = "", stderr = stderr)
    }
}
