package me.rerere.rikkahub.data.work

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PhoneWorkRunner(
    val id: String,
    val name: String,
    val version: String,
    val online: Boolean,
    val leaseUntil: String? = null,
)

@Serializable
data class PhoneWorkRepo(
    val id: String,
    val runnerId: String,
    val name: String,
    val models: List<String>,
    val reasoningEfforts: List<String>,
    val available: Boolean,
    val group: String? = null,
)

@Serializable
data class PhoneWorkSession(
    val id: String,
    val runnerId: String,
    val repoId: String,
    val repoName: String,
    val model: String,
    val reasoningEffort: String,
    val sandboxMode: String = "danger-full-access",
    val approvalPolicy: String = "never",
    val status: String,
    val codexSessionId: String? = null,
    val lastSeq: Long = 0,
    val archivedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PhoneWorkEvent(
    val sessionId: String,
    val seq: Long,
    val id: String,
    val type: String,
    val payload: JsonElement,
    val createdAt: String,
)

@Serializable
data class PhoneWorkAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class PhoneWorkUserMessagePayload(
    val text: String = "",
    val attachments: List<PhoneWorkAttachment> = emptyList(),
)

@Serializable
data class PhoneWorkQuestionOption(
    val id: String,
    val label: String,
    val description: String? = null,
)

@Serializable
data class PhoneWorkQuestion(
    val id: String,
    val header: String,
    val question: String,
    val multiSelect: Boolean = false,
    val options: List<PhoneWorkQuestionOption>,
)

@Serializable
data class PhoneWorkAnswer(
    val questionId: String,
    val selectedOptionIds: List<String>,
    val otherText: String? = null,
)

@Serializable
data class PhoneWorkAskPayload(
    val askId: String,
    val questions: List<PhoneWorkQuestion>,
)

@Serializable
data class PhoneWorkReportPayload(val text: String)

@Serializable
data class PhoneWorkAssistantMessagePayload(val text: String)

@Serializable
data class PhoneWorkHtmlReportPayload(
    val reportId: String,
    val title: String,
    val size: Long = 0,
)

@Serializable
data class PhoneWorkRunStatePayload(
    val status: String,
    val detail: String? = null,
)

@Serializable
data class PhoneWorkCatalog(
    val runners: List<PhoneWorkRunner> = emptyList(),
    val repos: List<PhoneWorkRepo> = emptyList(),
    val refreshedAtMillis: Long = 0,
)

data class PhoneWorkConnection(
    val baseUrl: String,
    val configured: Boolean,
)
