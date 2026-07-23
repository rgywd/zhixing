package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult

    fun executeProgram(context: WorkspaceProgramContext): WorkspaceCommandResult
}

data class WorkspaceShellContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
    val stdin: ByteArray? = null,
)

data class WorkspaceProgramContext(
    val root: String,
    val program: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
    val stdin: ByteArray? = null,
)

class HostShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    override fun executeProgram(context: WorkspaceProgramContext): WorkspaceCommandResult {
        val process = ProcessBuilder(listOf(context.program) + context.arguments)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .apply {
                environment().clear()
                environment().putAll(context.environment)
            }
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    private fun defaultShell(): String {
        listOf("/system/bin/sh", "/bin/sh")
            .firstOrNull { File(it).isFile }
            ?.let { return it }

        if (!System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) {
            return "/bin/sh"
        }

        val environment = System.getenv()
        val path = environment.entries
            .firstOrNull { (key, _) -> key.equals("PATH", ignoreCase = true) }
            ?.value
            .orEmpty()
        val programFiles = environment.entries
            .firstOrNull { (key, _) -> key.equals("ProgramFiles", ignoreCase = true) }
            ?.value
        val localAppData = environment.entries
            .firstOrNull { (key, _) -> key.equals("LOCALAPPDATA", ignoreCase = true) }
            ?.value

        val candidates = buildList {
            path.split(File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .mapTo(this) { directory -> File(directory, "sh.exe") }
            programFiles?.let { directory ->
                add(File(directory, "Git/bin/sh.exe"))
                add(File(directory, "Git/usr/bin/sh.exe"))
            }
            localAppData?.let { directory ->
                add(File(directory, "Programs/Git/bin/sh.exe"))
            }
        }
        return candidates.firstOrNull(File::isFile)?.absolutePath
            ?: throw IOException(
                "No POSIX shell found. Install Git for Windows or configure sh.exe on PATH."
            )
    }
}

// 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
const val MAX_OUTPUT_CHARS = 128 * 1024

fun Process.readResult(timeoutMillis: Long, stdin: ByteArray? = null): WorkspaceCommandResult {
    val stdout = StreamCollector(inputStream)
    val stderr = StreamCollector(errorStream)
    val stdinWriter = stdin?.let { bytes -> StreamWriter(outputStream, bytes) }
    try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            destroyForcibly()
        }
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        return WorkspaceCommandResult(
            exitCode = if (finished) exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
        )
    } catch (e: InterruptedException) {
        // 调用方线程被中断（如协程取消时的 runInterruptible），杀掉进程避免命令继续执行
        destroyForcibly()
        // 进程被杀后 stdout/stderr 会关闭, 这里 join 回收两个采集线程, 避免每次取消泄漏一对线程
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}

private class StreamWriter(
    private val stream: java.io.OutputStream,
    private val bytes: ByteArray,
) {
    private val thread = Thread {
        try {
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (_: IOException) {
            // 子进程提前退出或被强杀时 stdin 可能关闭, 忽略即可, 退出状态会由进程本身返回
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}

private class StreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
) {
    private val builder = StringBuilder()

    @Volatile
    var truncated = false
        private set

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    // 超出上限后继续读到 EOF 并丢弃，否则管道写满会阻塞子进程导致其无法退出
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭，阻塞中的 read 会抛 InterruptedIOException 等，
            // 保留已读取的内容即可；不能让异常逃逸，否则会触发线程默认异常处理导致应用崩溃
        }
    }.apply {
        // 设为 daemon: 即使 proot grandchild 残留 fd 导致 read() 永久阻塞, 也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }
}
