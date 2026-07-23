package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val TAG = "ChatGenerationFg"

class ChatGenerationForegroundService : Service() {
    private val controller: ChatGenerationForegroundController by inject()
    private val chatService: ChatService by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialState = controller.state.value
        if (!startForegroundCompat(initialState)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_CONVERSATION_ID)
                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?.let { conversationId ->
                    scope.launch { chatService.stopGeneration(conversationId) }
                }
        }

        if (stateJob == null) {
            stateJob = scope.launch {
                controller.state.collectLatest { state ->
                    if (state.isActive) {
                        if (!startForegroundCompat(state)) stopSelf()
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stateJob = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startForegroundCompat(state: ChatGenerationForegroundState): Boolean = try {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (error: Exception) {
        Log.e(TAG, "Unable to keep Chat generation in the foreground", error)
        false
    }

    private fun buildNotification(state: ChatGenerationForegroundState): Notification {
        val targetConversationId = state.targetConversationId
        val text = if (state.activeConversationCount > 1) {
            "${state.activeConversationCount} · ${getString(R.string.notification_live_update_writing)}"
        } else {
            getString(R.string.notification_live_update_writing)
        }
        val builder = NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_live_update_title))
            .setContentText(text)
            .setContentIntent(openChatPendingIntent(targetConversationId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        targetConversationId?.let { conversationId ->
            builder.addAction(
                0,
                getString(R.string.chat_page_cancel),
                cancelGenerationPendingIntent(conversationId),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            builder.setRequestPromotedOngoing(true)
        }
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setShortCriticalText(getString(R.string.notification_live_update_chip_writing))
        }
        return builder.build()
    }

    private fun openChatPendingIntent(conversationId: Uuid?): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it.toString()) }
        }
        return PendingIntent.getActivity(
            this,
            conversationId?.hashCode() ?: NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun cancelGenerationPendingIntent(conversationId: Uuid): PendingIntent {
        val intent = Intent(this, ChatGenerationForegroundService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
        }
        return PendingIntent.getService(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 2201
        const val ACTION_CANCEL = "me.rerere.rikkahub.action.CANCEL_CHAT_GENERATION"
        const val EXTRA_CONVERSATION_ID = "conversationId"
    }
}
