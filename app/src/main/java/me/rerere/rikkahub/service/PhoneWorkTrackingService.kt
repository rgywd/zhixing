package me.rerere.rikkahub.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.WORK_ALERT_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.WORK_ASK_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.WORK_TRACKING_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.work.PhoneWorkEvent
import me.rerere.rikkahub.data.work.PhoneWorkRepository
import me.rerere.rikkahub.data.work.PhoneWorkSession
import org.koin.android.ext.android.inject

private const val TAG = "PhoneWorkTracking"

class PhoneWorkTrackingService : Service() {
    private val repository: PhoneWorkRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackingJob: Job? = null
    private val askReminderJobs = mutableMapOf<String, Job>()
    private val trackingState by lazy { getSharedPreferences(TRACKING_PREFERENCES, Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (trackingJob == null) trackingJob = scope.launch { track() }
        return START_STICKY
    }

    override fun onDestroy() {
        trackingJob = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun track() {
        while (scope.isActive) {
            var latestMilestone: WorkTrackingMilestone? = null
            val before = runCatching { repository.activeSessionsSnapshot() }.getOrDefault(emptyList())
            val sessionsRefresh = runCatching { repository.refreshSessions() }
                .onFailure { Log.w(TAG, "Session refresh failed", it) }
            val after = runCatching { repository.activeSessionsSnapshot() }.getOrDefault(before)
            if (sessionsRefresh.isSuccess) after.forEach { rememberTracked(it.id) }
            val snapshots = (before + after).associateBy { it.id }
            trackedSessionIds().forEach { sessionId ->
                val session = snapshots[sessionId] ?: repository.sessionSnapshot(sessionId)
                if (session == null) {
                    forgetTracked(sessionId)
                    return@forEach
                }
                ensureNotificationCursor(sessionId)
                runCatching {
                    repository.refreshEvents(sessionId)
                    deliverPendingNotifications(session)?.let { latestMilestone = it }
                    repository.sessionSnapshot(sessionId)
                }.onSuccess { latest ->
                    if (latest?.status !in ACTIVE_STATES) forgetTracked(sessionId)
                }.onFailure { Log.w(TAG, "Event refresh failed for $sessionId", it) }
            }
            latestMilestone?.let(::rememberMilestone)
            val active = runCatching { repository.activeSessionsSnapshot() }.getOrDefault(after)
            if (active.isEmpty() && trackedSessionIds().isEmpty()) {
                clearMilestone()
                clearWorkShortcuts(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            updateOngoing(active)
            syncWorkShortcuts(this, active)
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun ensureNotificationCursor(sessionId: String) {
        val key = cursorKey(sessionId)
        if (!trackingState.contains(key)) {
            trackingState.edit(commit = true) {
                putLong(key, repository.maxEventSeq(sessionId))
            }
        }
    }

    private suspend fun deliverPendingNotifications(session: PhoneWorkSession): WorkTrackingMilestone? {
        val key = cursorKey(session.id)
        var cursor = trackingState.getLong(key, 0L)
        var latestMilestone: WorkTrackingMilestone? = null
        repository.cachedEventsAfter(session.id, cursor).forEach { event ->
            notifyEvent(session, event)?.let { latestMilestone = it }
            cursor = event.seq
            trackingState.edit(commit = true) {
                putLong(key, cursor)
            }
        }
        return latestMilestone
    }

    private fun trackedSessionIds(): Set<String> =
        trackingState.getStringSet(KEY_TRACKED_SESSIONS, emptySet()).orEmpty().toSet()

    private fun rememberTracked(sessionId: String) {
        trackingState.edit(commit = true) {
            putStringSet(KEY_TRACKED_SESSIONS, trackedSessionIds() + sessionId)
        }
    }

    private fun forgetTracked(sessionId: String) {
        trackingState.edit(commit = true) {
            putStringSet(KEY_TRACKED_SESSIONS, trackedSessionIds() - sessionId)
        }
    }

    private fun cursorKey(sessionId: String) = "notified_seq_$sessionId"

    private fun notifyEvent(session: PhoneWorkSession, event: PhoneWorkEvent): WorkTrackingMilestone? {
        val payload = event.payload.jsonObject
        val runtimeName = if (session.runtime == "claude-code") "Claude Code" else "Codex"
        var milestoneStatus: WorkTrackingMilestoneStatus? = null
        val notification = when (event.type) {
            "ASK" -> {
                val askId = payload["askId"]?.jsonPrimitive?.content ?: return null
                val deadlineAt = payload["deadlineAt"]?.jsonPrimitive?.content
                scheduleAskReminder(session, event, askId, deadlineAt)
                val minutes = deadlineAt?.let { deadlineMinutesAway(it) }
                askAlertBuilder(
                    session,
                    askId,
                    payload,
                    "${session.repoName} 需要你的回答",
                    if (minutes != null) {
                        "$runtimeName 遇到需要你决定的问题，${minutes} 分钟未回答将采用推荐方案"
                    } else {
                        "$runtimeName 遇到需要你决定的问题"
                    },
                ).build()
                    .also { notifyWithId(askNotificationId(askId), it) }
                return null
            }
            "ASK_ANSWERED" -> {
                payload["askId"]?.jsonPrimitive?.content?.let { askId ->
                    askReminderJobs.remove(askId)?.cancel()
                    NotificationManagerCompat.from(this).cancel(askNotificationId(askId))
                }
                return null
            }
            "REPORT" -> alertBuilder(
                WORK_ALERT_NOTIFICATION_CHANNEL_ID,
                session.repoName,
                payload["text"]?.jsonPrimitive?.content?.take(180) ?: "$runtimeName 发来一条进度汇报",
                session.id,
            ).build()
            "HTML_REPORT" -> alertBuilder(
                WORK_ALERT_NOTIFICATION_CHANNEL_ID,
                payload["title"]?.jsonPrimitive?.content ?: "${session.repoName} 报告",
                "$runtimeName 已生成一份可查看的报告",
                session.id,
            ).build()
            "RUN_STATE" -> {
                val state = payload["status"]?.jsonPrimitive?.content ?: return null
                milestoneStatus = runCatching { WorkTrackingMilestoneStatus.valueOf(state) }.getOrNull() ?: return null
                alertBuilder(
                    WORK_ALERT_NOTIFICATION_CHANNEL_ID,
                    session.repoName,
                    when (state) {
                        "IDLE" -> "本轮任务已完成，可以继续对话"
                        "COMPLETED" -> "会话已结束"
                        else -> "任务遇到问题，请打开查看"
                    },
                    session.id,
                ).build()
            }
            else -> return null
        }
        notifyWithId(event.id.hashCode(), notification)
        return milestoneStatus?.let {
            WorkTrackingMilestone(
                sessionId = session.id,
                repoName = session.repoName,
                status = it,
                observedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun scheduleAskReminder(session: PhoneWorkSession, event: PhoneWorkEvent, askId: String, deadlineAt: String?) {
        val deadlineMillis = deadlineAt
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return
        val delayMs = deadlineMillis - ASK_REMINDER_LEAD_MS - System.currentTimeMillis()
        if (delayMs <= 0) return
        askReminderJobs.remove(askId)?.cancel()
        askReminderJobs[askId] = scope.launch {
            delay(delayMs)
            askReminderJobs.remove(askId)
            val answered = repository.cachedEventsAfter(session.id, event.seq).any {
                it.type == "ASK_ANSWERED" && it.payload.jsonObject["askId"]?.jsonPrimitive?.content == askId
            }
            if (!answered) {
                notifyWithId(
                    askNotificationId(askId),
                    askAlertBuilder(
                        session,
                        askId,
                        event.payload.jsonObject,
                        "${session.repoName} 仍在等你的回答",
                        "1 分钟后将采用推荐方案，点按立即决定",
                    ).build(),
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyWithId(id: Int, notification: Notification) {
        if (hasNotificationPermission()) NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun deadlineMinutesAway(deadlineAt: String): Int? {
        val millis = runCatching { Instant.parse(deadlineAt).toEpochMilli() }.getOrNull() ?: return null
        val remaining = millis - System.currentTimeMillis()
        if (remaining <= 0) return null
        return ((remaining + 59_999) / 60_000).toInt()
    }

    private fun rememberMilestone(milestone: WorkTrackingMilestone) {
        trackingState.edit(commit = true) {
            putString(KEY_MILESTONE_SESSION_ID, milestone.sessionId)
            putString(KEY_MILESTONE_REPO_NAME, milestone.repoName)
            putString(KEY_MILESTONE_STATUS, milestone.status.name)
            putLong(KEY_MILESTONE_OBSERVED_AT, milestone.observedAtMillis)
        }
    }

    private fun trackedMilestone(nowMillis: Long): WorkTrackingMilestone? {
        val sessionId = trackingState.getString(KEY_MILESTONE_SESSION_ID, null) ?: return null
        val repoName = trackingState.getString(KEY_MILESTONE_REPO_NAME, null) ?: return null
        val status = trackingState.getString(KEY_MILESTONE_STATUS, null)
            ?.let { runCatching { WorkTrackingMilestoneStatus.valueOf(it) }.getOrNull() }
            ?: return null
        val milestone = WorkTrackingMilestone(
            sessionId = sessionId,
            repoName = repoName,
            status = status,
            observedAtMillis = trackingState.getLong(KEY_MILESTONE_OBSERVED_AT, 0L),
        )
        if (nowMillis - milestone.observedAtMillis !in 0 until WORK_MILESTONE_DISPLAY_MS) {
            clearMilestone()
            return null
        }
        return milestone
    }

    private fun clearMilestone() {
        trackingState.edit {
            remove(KEY_MILESTONE_SESSION_ID)
            remove(KEY_MILESTONE_REPO_NAME)
            remove(KEY_MILESTONE_STATUS)
            remove(KEY_MILESTONE_OBSERVED_AT)
        }
    }

    private fun alertBuilder(channel: String, title: String, text: String, sessionId: String) =
        NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openWorkPendingIntent(sessionId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

    private fun askAlertBuilder(
        session: PhoneWorkSession,
        askId: String,
        payload: JsonObject,
        title: String,
        text: String,
    ): NotificationCompat.Builder {
        val builder = alertBuilder(WORK_ASK_NOTIFICATION_CHANNEL_ID, title, text, session.id)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        recommendedAnswersFromAskPayload(payload)?.let { answers ->
            builder.addAction(
                R.drawable.small_icon,
                "采用推荐方案",
                WorkAskActionReceiver.acceptRecommendedPendingIntent(this, session.id, askId, answers),
            )
        }
        return builder
    }

    private fun startForegroundCompat(): Boolean = try {
        val notification = ongoingNotification(emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (error: Exception) {
        Log.e(TAG, "Unable to start Work tracking", error)
        false
    }

    private fun updateOngoing(active: List<PhoneWorkSession>) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, ongoingNotification(active))
    }

    private fun ongoingNotification(active: List<PhoneWorkSession>): Notification {
        val nowMillis = System.currentTimeMillis()
        val content = buildWorkTrackingNotificationContent(active, trackedMilestone(nowMillis), nowMillis)
        val builder = NotificationCompat.Builder(this, WORK_TRACKING_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("知行 Work")
            .setContentText(content.text)
            .setContentIntent(openWorkPendingIntent(content.targetSessionId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        content.expandedText?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            builder.setRequestPromotedOngoing(true)
        }
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setShortCriticalText("Work")
        }
        return builder.build()
    }

    private fun openWorkPendingIntent(sessionId: String?): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_WORK_SESSION_ID, sessionId.orEmpty())
        }
        return PendingIntent.getActivity(
            this,
            sessionId?.hashCode() ?: NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_WORK_SESSION_ID = "workSessionId"
        private const val NOTIFICATION_ID = 2401
        private const val POLL_INTERVAL_MS = 10_000L
        private const val ASK_REMINDER_LEAD_MS = 60_000L
        private const val TRACKING_PREFERENCES = "phone_work_tracking"
        private const val KEY_TRACKED_SESSIONS = "tracked_session_ids"
        private const val KEY_MILESTONE_SESSION_ID = "milestone_session_id"
        private const val KEY_MILESTONE_REPO_NAME = "milestone_repo_name"
        private const val KEY_MILESTONE_STATUS = "milestone_status"
        private const val KEY_MILESTONE_OBSERVED_AT = "milestone_observed_at"
        private val ACTIVE_STATES = setOf("QUEUED", "RUNNING", "WAITING_FOR_USER")

        internal fun askNotificationId(askId: String) = "zhixing-work-ask:$askId".hashCode()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PhoneWorkTrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhoneWorkTrackingService::class.java))
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            clearWorkShortcuts(context)
            context.getSharedPreferences(TRACKING_PREFERENCES, Context.MODE_PRIVATE).edit {
                clear()
            }
        }
    }
}
