package me.rerere.rikkahub.ui.pages.workflow

import me.rerere.rikkahub.data.workflow.WorkMachine
import me.rerere.rikkahub.data.workflow.WorkSession

data class WorkflowProject(
    val key: String,
    val machineId: String,
    val path: String,
    val name: String,
    val machine: WorkMachine?,
    val sessions: List<WorkSession>,
) {
    val latestSession: WorkSession get() = sessions.first()
    val isOnline: Boolean get() = machine?.active == true
    val activeSessions: Int get() = sessions.count(WorkSession::active)
    val pendingApprovals: Int get() = sessions.sumOf { it.approvals.size }
}

fun buildWorkflowProjects(
    sessions: List<WorkSession>,
    machines: List<WorkMachine>,
): List<WorkflowProject> {
    val machinesById = machines.associateBy(WorkMachine::id)
    return sessions
        .asSequence()
        .mapNotNull { session ->
            val machineId = session.machineId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val path = session.path?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ProjectIdentity(machineId, normalizeProjectPath(path)) to session
        }
        .groupBy({ it.first }, { it.second })
        .map { (identity, projectSessions) ->
            val sorted = projectSessions.sortedByDescending(WorkSession::updatedAt)
            WorkflowProject(
                key = "${identity.machineId}:${identity.path}",
                machineId = identity.machineId,
                path = sorted.first().path ?: identity.path,
                name = projectName(sorted.first().path ?: identity.path),
                machine = machinesById[identity.machineId],
                sessions = sorted,
            )
        }
        .sortedByDescending { it.latestSession.updatedAt }
}

private data class ProjectIdentity(val machineId: String, val path: String)

private fun normalizeProjectPath(path: String): String =
    path.trim().trimEnd('/', '\\').replace('\\', '/').lowercase()

internal fun projectName(path: String): String =
    path.trim().trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
