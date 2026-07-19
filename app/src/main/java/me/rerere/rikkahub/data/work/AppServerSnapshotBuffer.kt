package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.workflow.codex.CodexThreadDetail

/** Replays notifications that arrive while an older thread/read snapshot is in flight. */
class AppServerSnapshotBuffer {
    private var loadingThreadId: String? = null
    private val notifications = mutableListOf<AppServerNotification>()

    fun begin(threadId: String) {
        check(loadingThreadId == null) { "A thread snapshot is already in flight" }
        loadingThreadId = threadId
        notifications.clear()
    }

    fun offer(notification: AppServerNotification): Boolean {
        val loading = loadingThreadId ?: return false
        val notificationThreadId = (notification.params as? JsonObject)?.string("threadId") ?: return false
        if (notificationThreadId != loading) return false
        notifications += notification
        return true
    }

    fun complete(snapshot: CodexThreadDetail): CodexThreadDetail {
        val loading = loadingThreadId ?: return snapshot
        check(snapshot.thread?.threadId == loading) { "Snapshot thread does not match the in-flight read" }
        val result = notifications.fold(snapshot, AppServerThreadReducer::apply)
        clear()
        return result
    }

    fun abort(current: CodexThreadDetail): CodexThreadDetail {
        val result = notifications.fold(current, AppServerThreadReducer::apply)
        clear()
        return result
    }

    private fun clear() {
        loadingThreadId = null
        notifications.clear()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
