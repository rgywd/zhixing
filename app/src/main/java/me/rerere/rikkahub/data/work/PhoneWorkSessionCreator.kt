package me.rerere.rikkahub.data.work

fun interface PhoneWorkTitleGenerator {
    suspend fun generate(message: String): String?
}

interface PhoneWorkSessionGateway {
    suspend fun createSession(
        request: CreateSessionRequest,
        imageUrls: List<String> = emptyList(),
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
        imageUrls: List<String> = emptyList(),
    ): PhoneWorkSession {
        val title = titleGenerator.generate(message)
            ?: fallbackWorkSessionTitle(message, repo.name)
        return gateway.createSession(
            request = CreateSessionRequest(
                runnerId = repo.runnerId,
                repoId = repo.id,
                title = title,
                model = model,
                reasoningEffort = reasoningEffort,
                message = message,
            ),
            imageUrls = imageUrls,
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
