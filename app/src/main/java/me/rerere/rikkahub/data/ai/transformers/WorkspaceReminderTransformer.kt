package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定 workspace 时注入基本上下文；Rootfs 就绪时再追加 Shell 能力。
 * 知识空间是按需工具，不在这里把检索提升为普通对话的前置步骤。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        val prompt = buildWorkspacePrompt(
            workspace = workspace,
            cwd = ctx.workspaceCwd,
        )

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

internal fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    cwd: String? = null,
): String = buildString {
    appendLine("<workspace>")
    appendLine("You are bound to a persistent local workspace named \"${workspace.name}\".")
    if (workspace.shellStatus == WorkspaceShellStatus.READY.name) {
        appendLine("- A sandboxed Linux Rootfs is ready. The persistent files area is mounted at `/workspace`.")
        appendLine("- Workspace tool paths must be absolute inside the Rootfs, for example `/workspace/notes.md`.")
        appendLine("- Use `workspace_read_file`, `workspace_write_file`, `workspace_edit_file`, and `workspace_shell` for project execution.")
        appendLine("- The skills directory is mounted at `/skills`; read a skill's `SKILL.md` before using it.")
        appendLine("- `/upload` is read-only. Copy a file to `/workspace` before changing it.")
        if (!cwd.isNullOrBlank()) {
            appendLine("- Current working directory: `$cwd`.")
        }
    }
    append("</workspace>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
