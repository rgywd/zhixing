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
    val recurrence: AgendaRecurrence? = null,
)

data class AgendaRecurrence(
    val frequency: AgendaRecurrenceFrequency,
    val interval: Int = 1,
) {
    init {
        require(interval in 1..MAX_AGENDA_RECURRENCE_INTERVAL) {
            "重复间隔必须在 1 到 $MAX_AGENDA_RECURRENCE_INTERVAL 之间"
        }
    }
}

enum class AgendaRecurrenceFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
}

const val MAX_AGENDA_RECURRENCE_INTERVAL = 999

enum class AgendaTaskStatus {
    PENDING,
    COMPLETED,
    CANCELLED,
}

enum class AgendaTaskSource {
    MANUAL,
    CHAT,
}
