package me.rerere.rikkahub.data.model

data class AgendaPlan(
    val id: String,
    val title: String,
    val note: String,
    val location: String,
    val status: AgendaPlanStatus,
    val eventAt: Long?,
    val source: AgendaPlanSource,
    val sourceReference: String?,
    val conversationId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

data class AgendaPlanStage(
    val id: String,
    val planId: String,
    val title: String,
    val note: String,
    val status: AgendaPlanStageStatus,
    val position: Int,
    val scheduledAt: Long?,
    val dueAt: Long?,
    val triggerAt: Long?,
    val reminderAt: Long?,
    val scheduledOffsetMinutes: Long?,
    val dueOffsetMinutes: Long?,
    val triggerOffsetMinutes: Long?,
    val reminderOffsetMinutes: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

data class AgendaPlanWithStages(
    val plan: AgendaPlan,
    val stages: List<AgendaPlanStage>,
)

enum class AgendaPlanStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
}

enum class AgendaPlanStageStatus {
    PENDING,
    COMPLETED,
    CANCELLED,
}

enum class AgendaPlanSource {
    MANUAL,
    CHAT,
}

data class AgendaPlanStageDraft(
    val title: String,
    val note: String = "",
    val scheduledAt: Long? = null,
    val dueAt: Long? = null,
    val triggerAt: Long? = null,
    val reminderAt: Long? = null,
    val scheduledOffsetMinutes: Long? = null,
    val dueOffsetMinutes: Long? = null,
    val triggerOffsetMinutes: Long? = null,
    val reminderOffsetMinutes: Long? = null,
)
