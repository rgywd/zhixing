package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.work.PhoneWorkSession

internal const val WORK_MILESTONE_DISPLAY_MS = 60_000L

internal enum class WorkTrackingMilestoneStatus {
    IDLE,
    COMPLETED,
    FAILED,
}

internal data class WorkTrackingMilestone(
    val sessionId: String,
    val repoName: String,
    val status: WorkTrackingMilestoneStatus,
    val observedAtMillis: Long,
)

internal data class WorkTrackingNotificationContent(
    val text: String,
    val expandedText: String? = null,
    val targetSessionId: String? = null,
)

internal fun buildWorkTrackingNotificationContent(
    active: List<PhoneWorkSession>,
    milestone: WorkTrackingMilestone?,
    nowMillis: Long,
): WorkTrackingNotificationContent {
    val primary = active.firstOrNull()
    if (primary == null) return WorkTrackingNotificationContent(text = "正在连接 Work Core")

    val milestoneIsVisible = milestone != null &&
        active.none { it.id == milestone.sessionId } &&
        nowMillis - milestone.observedAtMillis in 0 until WORK_MILESTONE_DISPLAY_MS
    if (milestoneIsVisible) {
        checkNotNull(milestone)
        val summary = "${milestone.repoName} · ${milestone.status.displayText()}"
        val remaining = "仍有 ${active.size} 个任务正在跟踪"
        return WorkTrackingNotificationContent(
            text = "$summary，$remaining",
            expandedText = "$summary\n$remaining",
            targetSessionId = milestone.sessionId,
        )
    }

    return WorkTrackingNotificationContent(
        text = if (active.size == 1) {
            "${primary.repoName} · ${primary.status.statusText()}"
        } else {
            "${primary.repoName} 等 ${active.size} 个任务正在跟踪"
        },
        targetSessionId = primary.id,
    )
}

private fun WorkTrackingMilestoneStatus.displayText() = when (this) {
    WorkTrackingMilestoneStatus.IDLE -> "本轮任务已完成"
    WorkTrackingMilestoneStatus.COMPLETED -> "会话已结束"
    WorkTrackingMilestoneStatus.FAILED -> "任务遇到问题"
}

private fun String.statusText() = when (this) {
    "QUEUED" -> "等待开发机"
    "RUNNING" -> "进行中"
    "WAITING_FOR_USER" -> "等你回答"
    else -> this
}
