package me.rerere.rikkahub.data.agenda

import java.time.ZonedDateTime

data class AgendaTaskSaveInput(
    val title: String,
    val note: String,
    val dueAt: Long?,
    val reminderEnabled: Boolean,
    val originalDueAt: Long?,
    val originalReminderAt: Long?,
    val quickInputEnabled: Boolean,
)

data class AgendaTaskSaveResult(
    val title: String,
    val note: String,
    val dueAt: Long?,
    val reminderAt: Long?,
)

fun resolveAgendaTaskSave(
    input: AgendaTaskSaveInput,
    now: ZonedDateTime = ZonedDateTime.now(),
): AgendaTaskSaveResult {
    val parsed = if (input.quickInputEnabled && input.dueAt == null) {
        parseAgendaQuickInput(input.title, now)
    } else {
        ParsedAgendaInput(input.title, input.dueAt)
    }
    val dueAt = input.dueAt ?: parsed.dueAt
    val nowMillis = now.toInstant().toEpochMilli()
    val originalReminderCoupled =
        input.originalReminderAt != null && input.originalReminderAt == input.originalDueAt
    val reminderAt = when {
        !input.reminderEnabled -> null
        originalReminderCoupled && dueAt != input.originalDueAt ->
            dueAt?.takeIf { it > nowMillis }
        input.originalReminderAt == null -> dueAt?.takeIf { it > nowMillis }
        input.originalReminderAt <= nowMillis -> null
        input.originalReminderAt != input.originalDueAt -> input.originalReminderAt
        dueAt == input.originalDueAt -> input.originalReminderAt
        else -> dueAt?.takeIf { it > nowMillis }
    }

    return AgendaTaskSaveResult(
        title = parsed.title.ifBlank { input.title },
        note = input.note,
        dueAt = dueAt,
        reminderAt = reminderAt,
    )
}
