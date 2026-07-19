package me.rerere.rikkahub.data.workflow.codex

private const val DEFAULT_HOME_PROJECT_LIMIT = 8

data class CodexHomeCatalog(
    val projects: List<CodexProject>,
    val pinnedThreads: List<CodexThread>,
    val remainingProjectCount: Int,
)

/**
 * Keeps the complete catalog available for search and management while limiting the work home to
 * user-pinned projects and a small set of recently active projects.
 */
fun curateCodexHome(
    projects: List<CodexProject>,
    projectLimit: Int = DEFAULT_HOME_PROJECT_LIMIT,
): CodexHomeCatalog {
    require(projectLimit >= 0)
    val available = projects.filterNot(CodexProject::isHidden)
    val pinnedProjects = available.filter(CodexProject::isPinned).sortedByDescending(CodexProject::activeAt)
    val recentProjects = available
        .filter { !it.isPinned && it.existsOnDisk && it.currentThreads.isNotEmpty() }
        .sortedByDescending(CodexProject::activeAt)
    val visibleProjects = pinnedProjects + recentProjects.take((projectLimit - pinnedProjects.size).coerceAtLeast(0))
    val eligibleProjectCount = pinnedProjects.size + recentProjects.size
    val pinnedThreads = projects
        .asSequence()
        .filterNot(CodexProject::isHidden)
        .flatMap { it.primaryThreads.asSequence() }
        .filter(CodexThread::isPinned)
        .distinctBy { it.machineId to it.threadId }
        .sortedByDescending(CodexThread::recencyAt)
        .toList()
    return CodexHomeCatalog(
        projects = visibleProjects,
        pinnedThreads = pinnedThreads,
        remainingProjectCount = (eligibleProjectCount - visibleProjects.size).coerceAtLeast(0),
    )
}
