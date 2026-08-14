package me.rerere.rikkahub.data.work

fun interface PhoneWorkTitleGenerator {
    suspend fun generate(message: String): String?
}

interface PhoneWorkSessionGateway {
    suspend fun createSession(
        request: CreateSessionRequest,
        attachments: List<PhoneWorkPendingAttachment> = emptyList(),
    ): PhoneWorkSession
}

class PhoneWorkSessionCreator(
    private val gateway: PhoneWorkSessionGateway,
    private val titleGenerator: PhoneWorkTitleGenerator,
) {
    suspend fun create(
        repo: PhoneWorkRepo,
        model: String,
        reasoningEffort: String,
        message: String,
        fastMode: Boolean = false,
        runtime: String = "codex",
        attachments: List<PhoneWorkPendingAttachment> = emptyList(),
    ): PhoneWorkSession {
        val title = titleGenerator.generate(message)
            ?: fallbackWorkSessionTitle(message, repo.name)
        return gateway.createSession(
            request = CreateSessionRequest(
                runnerId = repo.runnerId,
                repoId = repo.id,
                title = title,
                runtime = runtime,
                model = model,
                reasoningEffort = reasoningEffort,
                fastMode = fastMode,
                message = message,
            ),
            attachments = attachments,
        )
    }
}

internal fun fallbackWorkSessionTitle(message: String, repoName: String): String = message
    .lineSequence()
    .map(String::trim)
    .firstOrNull(String::isNotBlank)
    ?.trimStart('#', '-', '*', ' ')
    ?.replace(Regex("\\s+"), " ")
    ?.take(40)
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: repoName
