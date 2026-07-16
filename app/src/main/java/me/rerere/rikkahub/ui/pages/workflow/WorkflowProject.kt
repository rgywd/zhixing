package me.rerere.rikkahub.ui.pages.workflow

import me.rerere.rikkahub.ui.pages.workflow.happy.HappyMachine
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySession

data class WorkflowProject(
    val key: String,
    val machineId: String,
    val path: String,
    val name: String,
    val machine: HappyMachine?,
    val sessions: List<HappySession>,
) {
    val latestSession: HappySession get() = sessions.first()
    val isOnline: Boolean get() = machine?.active == true
    val activeSessions: Int get() = sessions.count(HappySession::active)
    val pendingApprovals: Int get() = sessions.sumOf { it.approvals.size }
}

fun buildWorkflowProjects(
    sessions: List<HappySession>,
    machines: List<HappyMachine>,
): List<WorkflowProject> {
    val machinesById = machines.associateBy(HappyMachine::id)
    return sessions
        .asSequence()
        .filter { it.isCodexSession() }
        .mapNotNull { session ->
            val machineId = session.machineId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val path = session.path?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ProjectIdentity(machineId, normalizeProjectPath(path)) to session
        }
        .groupBy({ it.first }, { it.second })
        .map { (identity, projectSessions) ->
            val sorted = projectSessions.sortedByDescending(HappySession::updatedAt)
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

fun HappySession.isCodexSession(): Boolean =
    flavor.equals("codex", ignoreCase = true) || !codexThreadId.isNullOrBlank()

private data class ProjectIdentity(val machineId: String, val path: String)

private fun normalizeProjectPath(path: String): String =
    path.trim().trimEnd('/', '\\').replace('\\', '/').lowercase()

private fun projectName(path: String): String =
    path.trim().trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
