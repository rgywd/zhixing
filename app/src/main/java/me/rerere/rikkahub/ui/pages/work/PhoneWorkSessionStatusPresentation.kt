package me.rerere.rikkahub.ui.pages.work

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.work.PhoneWorkCatalog
import me.rerere.rikkahub.data.work.PhoneWorkEvent
import me.rerere.rikkahub.data.work.PhoneWorkSession

internal enum class WorkSessionStatusKind {
    ACTIVE,
    WAITING,
    IDLE,
    TERMINAL,
    FAILURE,
    ATTENTION,
}

internal data class WorkSessionStatusPresentation(
    val headline: String,
    val detail: String,
    val kind: WorkSessionStatusKind,
)

private enum class WorkRunnerPresence {
    ONLINE,
    OFFLINE,
    UNKNOWN,
}

internal fun buildWorkSessionStatusPresentation(
    session: PhoneWorkSession?,
    catalog: PhoneWorkCatalog,
    events: List<PhoneWorkEvent>,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): WorkSessionStatusPresentation? {
    session ?: return null
    val runnerPresence = runnerPresence(session, catalog, now)
    val detail = buildStatusDetail(session, events, now, zoneId)
    return when (session.status) {
        "QUEUED" -> when (runnerPresence) {
            WorkRunnerPresence.ONLINE -> WorkSessionStatusPresentation("等待开发机接单", detail, WorkSessionStatusKind.WAITING)
            WorkRunnerPresence.OFFLINE -> WorkSessionStatusPresentation(
                "开发机离线 · 等待恢复连接",
                detail,
                WorkSessionStatusKind.ATTENTION,
            )
            WorkRunnerPresence.UNKNOWN -> WorkSessionStatusPresentation(
                "正在确认开发机状态",
                detail,
                WorkSessionStatusKind.ATTENTION,
            )
        }
        "RUNNING" -> when (runnerPresence) {
            WorkRunnerPresence.ONLINE -> WorkSessionStatusPresentation(
                "运行中 · 开发机在线",
                detail,
                WorkSessionStatusKind.ACTIVE,
            )
            WorkRunnerPresence.OFFLINE -> WorkSessionStatusPresentation(
                "连接中断 · 任务状态待确认",
                detail,
                WorkSessionStatusKind.ATTENTION,
            )
            WorkRunnerPresence.UNKNOWN -> WorkSessionStatusPresentation(
                "正在确认开发机状态",
                detail,
                WorkSessionStatusKind.ATTENTION,
            )
        }
        "WAITING_FOR_USER" -> WorkSessionStatusPresentation(
            if (runnerPresence == WorkRunnerPresence.OFFLINE) "等你回答 · 开发机暂时离线" else "等你回答",
            detail,
            WorkSessionStatusKind.WAITING,
        )
        "IDLE" -> WorkSessionStatusPresentation("本轮完成 · 可继续", detail, WorkSessionStatusKind.IDLE)
        "COMPLETED" -> WorkSessionStatusPresentation("会话已结束", detail, WorkSessionStatusKind.TERMINAL)
        "FAILED" -> WorkSessionStatusPresentation("执行失败 · 可以重试", detail, WorkSessionStatusKind.FAILURE)
        else -> WorkSessionStatusPresentation(session.status, detail, WorkSessionStatusKind.ATTENTION)
    }
}

internal fun workRunStateTimelineLabel(
    status: String,
    detail: String?,
    createdAt: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val time = formatWorkTimestamp(createdAt, zoneId)
    val statusLabel = when (status) {
        "QUEUED" -> "等待开发机"
        "RUNNING" -> "开始执行"
        "WAITING_FOR_USER" -> "等待你的回答"
        "IDLE" -> "本轮完成 · 可继续"
        "COMPLETED" -> "会话已结束"
        "FAILED" -> "执行失败"
        else -> status.displayStatus()
    }
    val meaningfulDetail = detail
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.takeIf { status == "FAILED" || status !in KNOWN_WORK_STATUSES }
    return listOfNotNull(time, statusLabel, meaningfulDetail)
        .joinToString(separator = if (time == null) " · " else "  ")
}

private fun runnerPresence(
    session: PhoneWorkSession,
    catalog: PhoneWorkCatalog,
    now: Instant,
): WorkRunnerPresence {
    val runner = catalog.runners.firstOrNull { it.id == session.runnerId }
        ?: return if (catalog.refreshedAtMillis > 0) WorkRunnerPresence.OFFLINE else WorkRunnerPresence.UNKNOWN
    val leaseExpired = runner.leaseUntil
        ?.let(::parseWorkInstant)
        ?.let { !it.isAfter(now) }
        ?: false
    return if (runner.online && !leaseExpired) WorkRunnerPresence.ONLINE else WorkRunnerPresence.OFFLINE
}

private fun buildStatusDetail(
    session: PhoneWorkSession,
    events: List<PhoneWorkEvent>,
    now: Instant,
    zoneId: ZoneId,
): String {
    val lastUpdatedAt = events.lastOrNull()?.createdAt ?: session.updatedAt
    val updateLabel = relativeWorkUpdateLabel(lastUpdatedAt, now, zoneId)
    if (session.status != "RUNNING") return updateLabel
    val runStartedAt = events
        .asReversed()
        .firstOrNull { event ->
            event.type == "RUN_STATE" &&
                event.payload.jsonObject["status"]?.jsonPrimitive?.content == "RUNNING"
        }
        ?.createdAt
        ?.let { formatWorkTimestamp(it, zoneId) }
    return listOfNotNull(runStartedAt?.let { "本轮开始 $it" }, updateLabel).joinToString(" · ")
}

private fun relativeWorkUpdateLabel(value: String, now: Instant, zoneId: ZoneId): String {
    val instant = parseWorkInstant(value) ?: return formatWorkTimestamp(value, zoneId)?.let { "最后更新 $it" } ?: "更新时间未知"
    val age = Duration.between(instant, now).coerceAtLeast(Duration.ZERO)
    return when {
        age.seconds < 60 -> "刚刚更新"
        age.toMinutes() < 60 -> "${age.toMinutes()} 分钟前更新"
        age.toHours() < 24 -> "${age.toHours()} 小时前更新"
        else -> "最后更新 ${WORK_MONTH_DAY_TIME_FORMATTER.withZone(zoneId).format(instant)}"
    }
}

private fun formatWorkTimestamp(value: String, zoneId: ZoneId): String? =
    parseWorkInstant(value)?.let { WORK_TIME_FORMATTER.withZone(zoneId).format(it) }

private fun parseWorkInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

private val KNOWN_WORK_STATUSES = setOf("QUEUED", "RUNNING", "WAITING_FOR_USER", "IDLE", "COMPLETED", "FAILED")
private val WORK_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val WORK_MONTH_DAY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
