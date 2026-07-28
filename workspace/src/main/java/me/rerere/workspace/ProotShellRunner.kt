package me.rerere.workspace

import java.io.File
import java.util.UUID

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val relay = relayProgramEnvironment(context.environment)
        return executeInRootfs(
            linuxDir = context.linuxDir,
            filesDir = context.filesDir,
            tempDir = context.tempDir,
            timeoutMillis = context.timeoutMillis,
            stdin = context.stdin,
            workingDirectory = context.prootCwd(),
            command = listOf(
                "/bin/bash",
                "-c",
                SHELL_EXEC_SCRIPT,
                "zhixing-shell",
                context.prootCwd(),
                context.command,
                relay.names.size.toString(),
            ) + relay.names.flatMap { listOf(it.target, it.relay) },
            processEnvironment = relay.processEnvironment,
            clearProcessEnvironment = true,
        )
    }

    override fun executeProgram(context: WorkspaceProgramContext): WorkspaceCommandResult {
        val relay = relayProgramEnvironment(context.environment)
        return executeInRootfs(
            linuxDir = context.linuxDir,
            filesDir = context.filesDir,
            tempDir = context.tempDir,
            timeoutMillis = context.timeoutMillis,
            stdin = context.stdin,
            workingDirectory = context.prootCwd(),
            command = listOf(
                "/bin/bash",
                "-c",
                PROGRAM_EXEC_SCRIPT,
                "zhixing-program",
                context.program,
                relay.names.size.toString(),
            ) + relay.names.flatMap { listOf(it.target, it.relay) } + context.arguments,
            processEnvironment = relay.processEnvironment,
            clearProcessEnvironment = true,
        )
    }

    private fun executeInRootfs(
        linuxDir: File,
        filesDir: File,
        tempDir: File,
        timeoutMillis: Long,
        stdin: ByteArray?,
        workingDirectory: String,
        command: List<String>,
        processEnvironment: Map<String, String> = emptyMap(),
        clearProcessEnvironment: Boolean = false,
    ): WorkspaceCommandResult {
        if (!linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(127, "", "Rootfs is not installed")
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(127, "", "proot executable not found: ${proot.absolutePath}")
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(127, "", "proot loader not found: ${loader.absolutePath}")
        }

        tempDir.mkdirs()
        patcher.patch(linuxDir)
        val process = ProcessBuilder(
            buildProotCommand(linuxDir, filesDir, workingDirectory, proot) + command
        )
            .directory(filesDir)
            .redirectErrorStream(false)
            .apply {
                if (clearProcessEnvironment) environment().clear()
                environment().putAll(processEnvironment)
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
                environment()["TMPDIR"] = tempDir.absolutePath
            }
            .start()
        return process.readResult(timeoutMillis, stdin)
    }

    private fun buildProotCommand(
        linuxDir: File,
        filesDir: File,
        workingDirectory: String,
        proot: File,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir.absolutePath,
            "-w",
            workingDirectory,
            "-b",
            "${filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        extraBindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        listOf("/dev", "/proc", "/sys").forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun WorkspaceProgramContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) WORKSPACE_DIR else "$WORKSPACE_DIR/$normalized"
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val WORKSPACE_DIR = "/workspace"
        private val SHELL_EXEC_SCRIPT = """
            cwd="${'$'}1"
            command="${'$'}2"
            relay_count="${'$'}3"
            shift 3
            forwarded=()
            for ((i = 0; i < relay_count; i++)); do
              target="${'$'}1"
              relay="${'$'}2"
              shift 2
              forwarded+=("${'$'}target=${'$'}{!relay}")
            done
            exec /usr/bin/env -i \
              HOME=/root \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              TERM=xterm-256color \
              LANG=C.UTF-8 \
              LC_ALL=C.UTF-8 \
              "${'$'}{forwarded[@]}" \
              /bin/bash -l -c 'cd -- "$1" && eval "$2"' zhixing "${'$'}cwd" "${'$'}command"
        """.trimIndent()
        private val PROGRAM_EXEC_SCRIPT = """
            program="${'$'}1"
            shift
            relay_count="${'$'}1"
            shift
            forwarded=()
            for ((i = 0; i < relay_count; i++)); do
              target="${'$'}1"
              relay="${'$'}2"
              shift 2
              forwarded+=("${'$'}target=${'$'}{!relay}")
            done
            exec /usr/bin/env -i \
              HOME=/root \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              TERM=xterm-256color \
              LANG=C.UTF-8 \
              LC_ALL=C.UTF-8 \
              "${'$'}{forwarded[@]}" \
              "${'$'}program" "${'$'}@"
        """.trimIndent()
    }
}

internal data class RelayedEnvironmentName(
    val target: String,
    val relay: String,
)

internal data class RelayedProgramEnvironment(
    val names: List<RelayedEnvironmentName>,
    val processEnvironment: Map<String, String>,
)

internal fun relayProgramEnvironment(
    environment: Map<String, String>,
    relayName: () -> String = { "ZHIXING_PRIVATE_${UUID.randomUUID().toString().replace("-", "")}" },
): RelayedProgramEnvironment {
    val names = environment.keys.map { target -> RelayedEnvironmentName(target, relayName()) }
    return RelayedProgramEnvironment(
        names = names,
        processEnvironment = names.associate { name -> name.relay to environment.getValue(name.target) },
    )
}
