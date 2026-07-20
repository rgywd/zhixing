package me.rerere.rikkahub.data.work

data class AppServerBuiltInCommand(
    val name: String,
    val description: String,
)

/** Client commands that map to real App Server operations instead of model text. */
object AppServerBuiltInCommands {
    val available: List<AppServerBuiltInCommand> = listOf(
        AppServerBuiltInCommand("new", "在当前仓库新建一个 Codex Thread"),
        AppServerBuiltInCommand("compact", "压缩当前 Thread 的上下文"),
    )

    fun exact(text: String): AppServerBuiltInCommand? {
        val name = text.trim().removePrefix("/")
        if (name.isBlank() || name.any(Char::isWhitespace)) return null
        return available.firstOrNull { it.name == name }
    }
}
