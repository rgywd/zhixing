package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.workflow.codex.CodexThreadDetail

data class AppServerSnapshotReplay(
    val detail: CodexThreadDetail,
    val serverRequests: List<AppServerRequest>,
)

/** Replays events that arrive while an older thread/read snapshot is in flight. */
class AppServerSnapshotBuffer {
    private var loadingThreadId: String? = null
    private var loadingConnectionGeneration: Long? = null
    private val notifications = mutableListOf<AppServerNotification>()
    private val serverRequests = mutableListOf<AppServerRequest>()

    fun begin(threadId: String, connectionGeneration: Long = 0) {
        check(loadingThreadId == null) { "A thread snapshot is already in flight" }
        loadingThreadId = threadId
        loadingConnectionGeneration = connectionGeneration
        notifications.clear()
        serverRequests.clear()
    }

    fun offer(notification: AppServerNotification): Boolean {
        val loading = loadingThreadId ?: return false
        if (notification.connectionGeneration != loadingConnectionGeneration) return false
        val notificationThreadId = (notification.params as? JsonObject)?.string("threadId") ?: return false
        if (notificationThreadId != loading) return false
        notifications += notification
        return true
    }

    fun offer(request: AppServerRequest): Boolean {
        val loading = loadingThreadId ?: return false
        if (request.connectionGeneration != loadingConnectionGeneration) return false
        val requestThreadId = (request.params as? JsonObject)?.string("threadId") ?: return false
        if (requestThreadId != loading) return false
        serverRequests += request
        return true
    }

    fun complete(snapshot: CodexThreadDetail): AppServerSnapshotReplay {
        val loading = loadingThreadId ?: return AppServerSnapshotReplay(snapshot, emptyList())
        check(snapshot.thread?.threadId == loading) { "Snapshot thread does not match the in-flight read" }
        val result = notifications.fold(snapshot, AppServerThreadReducer::apply)
        val requests = serverRequests.toList()
        clear()
        return AppServerSnapshotReplay(result, requests)
    }

    fun abort(current: CodexThreadDetail): AppServerSnapshotReplay {
        val result = notifications.fold(current, AppServerThreadReducer::apply)
        val requests = serverRequests.toList()
        clear()
        return AppServerSnapshotReplay(result, requests)
    }

    private fun clear() {
        loadingThreadId = null
        loadingConnectionGeneration = null
        notifications.clear()
        serverRequests.clear()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
