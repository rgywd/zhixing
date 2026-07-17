package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.WORK_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.workflow.WorkRepository
import me.rerere.rikkahub.data.workflow.WorkSession
import me.rerere.rikkahub.utils.sendNotification

/**
 * 远程工作会话的关键通知（Issue #18 验收 10）：
 * 只在任务结束或出现待审批/待决策时打扰用户，普通命令执行不通知；
 * App 在前台时静默（用户正在看）。
 *
 * 通知时机由两条链路驱动：进程存活期间的 Socket 增量信号（近实时），
 * 以及 WorkManager 周期同步兜底（进程被杀后仍能补发）。
 */
class WorkNotificationManager(
    private val context: Application,
    appScope: AppScope,
    private val repository: WorkRepository,
) {
    private val isForeground = MutableStateFlow(false)
    private val syncMutex = Mutex()

    init {
        // ProcessLifecycleOwner 要求在主线程注册观察者
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> isForeground.value = true
                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> {}
                    }
                }
            )
        }
        appScope.launch {
            repository.connected.collect { connected ->
                if (connected) repository.ensureRealtime()
            }
        }
        appScope.launch(Dispatchers.Default) {
            repository.updates.conflate().collect {
                runCatching { syncAndNotify() }
                delay(SYNC_THROTTLE_MS)
            }
        }
        schedulePeriodicSync()
    }

    /** 刷新快照并对比前后状态，向后台用户补发关键事件通知 */
    suspend fun syncAndNotify() = syncMutex.withLock {
        if (!repository.connected.value) return
        val previous = repository.observeSessions().first()
        runCatching { repository.refreshSnapshot() }.getOrElse { return }
        val current = repository.observeSessions().first()
        notifyDiff(previous, current)
    }

    private fun notifyDiff(previous: List<WorkSession>, current: List<WorkSession>) {
        if (isForeground.value) return
        val previousById = previous.associateBy(WorkSession::id)
        current.forEach { session ->
            val before = previousById[session.id] ?: return@forEach
            if (session.approvals.size > before.approvals.size) {
                sendApprovalNotification(session)
            }
            if (before.active && !session.active) {
                sendEndedNotification(session)
            }
        }
    }

    private fun sendApprovalNotification(session: WorkSession) {
        val approval = session.approvals.lastOrNull() ?: return
        context.sendNotification(
            channelId = WORK_NOTIFICATION_CHANNEL_ID,
            notificationId = session.id.hashCode() + APPROVAL_NOTIFICATION_OFFSET,
        ) {
            title = "${session.displayTitle()} 等待你的确认"
            content = "${approval.tool}：${approval.arguments.take(120)}"
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, session.id)
        }
    }

    private fun sendEndedNotification(session: WorkSession) {
        context.sendNotification(
            channelId = WORK_NOTIFICATION_CHANNEL_ID,
            notificationId = session.id.hashCode() + ENDED_NOTIFICATION_OFFSET,
        ) {
            title = "${session.displayTitle()} 已结束"
            content = "远程任务已停止运行，点按查看结果与总结"
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, session.id)
        }
    }

    private fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<WorkSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "work_remote_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun getPendingIntent(context: Context, sessionId: String): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("workSessionId", sessionId)
        }
        return PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun WorkSession.displayTitle(): String =
        name?.takeIf(String::isNotBlank)
            ?: path?.substringAfterLast('/')?.substringAfterLast('\\')
            ?: "远程会话 ${id.take(6)}"

    private companion object {
        const val SYNC_THROTTLE_MS = 3_000L
        const val APPROVAL_NOTIFICATION_OFFSET = 20000
        const val ENDED_NOTIFICATION_OFFSET = 30000
    }
}
