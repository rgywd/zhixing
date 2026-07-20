package me.rerere.rikkahub.data.work

/** Restores only options explicitly rejected by App Server validation. */
object AppServerOptionRollback {
    fun rollback(
        current: WorkRepositoryPreferences,
        accepted: WorkRepositoryPreferences,
        error: Throwable,
    ): WorkRepositoryPreferences {
        val message = buildString {
            append(error.message.orEmpty())
            if (error is AppServerRpcException) append(' ').append(error.data?.toString().orEmpty())
        }.lowercase()
        val rejectModel = "model" in message
        val rejectEffort = "effort" in message || "reasoning" in message
        val rejectFast = "servicetier" in message || "service_tier" in message || "service tier" in message || "priority" in message
        val rejectPermission = "approval" in message || "sandbox" in message || "permission" in message

        if (!rejectModel && !rejectEffort && !rejectFast && !rejectPermission) return current
        var restored = current
        if (rejectModel) restored = restored.copy(model = accepted.model)
        if (rejectEffort) restored = restored.copy(
            effort = accepted.effort,
            effortByModel = accepted.effortByModel,
        )
        if (rejectFast) restored = restored.copy(fastMode = accepted.fastMode)
        if (rejectPermission) restored = restored.copy(permission = accepted.permission)
        return restored
    }
}
