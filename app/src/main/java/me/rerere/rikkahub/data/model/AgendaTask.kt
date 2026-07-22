package me.rerere.rikkahub.data.model

data class AgendaTask(
    val id: String,
    val title: String,
    val note: String,
    val status: AgendaTaskStatus,
    val dueAt: Long?,
    val reminderAt: Long?,
    val source: AgendaTaskSource,
    val conversationId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

enum class AgendaTaskStatus {
    PENDING,
    COMPLETED,
    CANCELLED,
}

enum class AgendaTaskSource {
    MANUAL,
    CHAT,
}
