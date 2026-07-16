package me.rerere.rikkahub.data.ai.tools.local

import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.github.GitHubIssueApiException
import me.rerere.rikkahub.data.github.GitHubIssueClient
import me.rerere.rikkahub.data.github.GitHubIssueTokenProvider

private const val FEATURE_TYPE = "feature"
private const val BUG_TYPE = "bug"
private const val MAX_TITLE_LENGTH = 180
private const val MAX_FIELD_LENGTH = 6_000

internal data class GitHubIssueEnvironment(
    val appVersion: String,
    val versionCode: String,
    val androidVersion: String,
    val device: String,
)

internal data class GitHubIssueDraft(
    val type: String,
    val title: String,
    val body: String,
    val label: String,
)

internal fun buildGitHubIssueTool(
    tokenProvider: GitHubIssueTokenProvider,
    issueClient: GitHubIssueClient,
): Tool = Tool(
    name = "submit_github_issue",
    description = """
        Create a feature request or bug report in the Zhixing GitHub repository after the user approves
        this tool call. Prefer type 'feature' for product ideas and
        requirements. Never include API keys, tokens, private conversations, personal data, or other secrets.
        This tool always requires user approval and creates the issue directly after approval.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add(FEATURE_TYPE); add(BUG_TYPE) })
                    put("description", "Issue type. Use feature for requests and bug for defects.")
                })
                put("title", stringProperty("Short, specific issue title without a feat:/bug: prefix."))
                put("description", stringProperty("Problem, motivation, or requested capability."))
                put("use_case", stringProperty("Feature usage scenario and who benefits."))
                put("acceptance_criteria", stringProperty("Observable conditions that define completion."))
                put("steps_to_reproduce", stringProperty("Numbered reproduction steps for a bug."))
                put("expected_behavior", stringProperty("Expected behavior for a bug."))
                put("actual_behavior", stringProperty("Observed behavior for a bug."))
                put("additional_context", stringProperty("Optional constraints, alternatives, screenshots, or notes."))
            },
            required = listOf("type", "title", "description"),
        )
    },
    needsApproval = { true },
    execute = { input ->
        val draft = createGitHubIssueDraft(input.jsonObject, currentIssueEnvironment())
        val token = tokenProvider.getToken()
        if (token == null) {
            return@Tool listOf(UIMessagePart.Text(toolResult(
                status = "NOT_CONFIGURED",
                draft = draft,
                message = "Configure a fine-grained GitHub token in Settings > About before submitting.",
            )))
        }
        try {
            val created = issueClient.createIssue(token, draft.title, draft.body, draft.label)
            listOf(UIMessagePart.Text(toolResult(
                status = "CREATED",
                draft = draft,
                message = "GitHub issue #${created.number} was created.",
                number = created.number,
                url = created.url,
            )))
        } catch (error: GitHubIssueApiException) {
            listOf(UIMessagePart.Text(toolResult(
                status = if (error.statusCode == 401 || error.statusCode == 403) "AUTH_FAILED" else "GITHUB_ERROR",
                draft = draft,
                message = error.message.orEmpty(),
            )))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            listOf(UIMessagePart.Text(toolResult(
                status = "GITHUB_ERROR",
                draft = draft,
                message = "Unable to reach GitHub. Check the network and try again.",
            )))
        }
    },
)

internal fun createGitHubIssueDraft(
    input: JsonObject,
    environment: GitHubIssueEnvironment,
): GitHubIssueDraft {
    val type = input.string("type").lowercase()
    require(type == FEATURE_TYPE || type == BUG_TYPE) { "type must be feature or bug" }
    val rawTitle = input.string("title")
    val description = input.string("description")
    require(rawTitle.isNotBlank()) { "title is required" }
    require(description.isNotBlank()) { "description is required" }

    val prefix = if (type == FEATURE_TYPE) "feat: " else "bug: "
    val titleWithoutPrefix = rawTitle
        .replace(Regex("^(feat|feature|bug|fix):\\s*", RegexOption.IGNORE_CASE), "")
        .trim()
    require(titleWithoutPrefix.isNotBlank()) { "title is required" }
    val title = (prefix + titleWithoutPrefix).take(MAX_TITLE_LENGTH)
    val label = if (type == FEATURE_TYPE) "enhancement" else "bug"
    val body = if (type == FEATURE_TYPE) {
        featureBody(input, description, environment)
    } else {
        bugBody(input, description, environment)
    }
    return GitHubIssueDraft(type, title, body, label)
}

private fun toolResult(
    status: String,
    draft: GitHubIssueDraft,
    message: String,
    number: Int? = null,
    url: String? = null,
): String = buildJsonObject {
    put("status", status)
    put("type", draft.type)
    put("title", draft.title)
    put("label", draft.label)
    put("message", message)
    number?.let { put("number", it) }
    url?.let { put("url", it) }
}.toString()

private fun featureBody(
    input: JsonObject,
    description: String,
    environment: GitHubIssueEnvironment,
): String = buildString {
    appendSection("需求描述", description)
    appendSection("使用场景", input.optionalString("use_case"))
    appendSection("验收标准", input.optionalString("acceptance_criteria"))
    appendSection("补充信息", input.optionalString("additional_context"))
    appendEnvironment(environment)
}

private fun bugBody(
    input: JsonObject,
    description: String,
    environment: GitHubIssueEnvironment,
): String = buildString {
    appendSection("问题描述", description)
    appendSection("复现步骤", input.optionalString("steps_to_reproduce"))
    appendSection("预期行为", input.optionalString("expected_behavior"))
    appendSection("实际行为", input.optionalString("actual_behavior"))
    appendSection("补充信息", input.optionalString("additional_context"))
    appendEnvironment(environment)
}

private fun StringBuilder.appendSection(title: String, content: String?) {
    appendLine("## $title")
    appendLine(content?.take(MAX_FIELD_LENGTH)?.trim().takeUnless { it.isNullOrBlank() } ?: "未提供")
    appendLine()
}

private fun StringBuilder.appendEnvironment(environment: GitHubIssueEnvironment) {
    appendLine("## 环境信息")
    appendLine("- Zhixing: ${environment.appVersion} (${environment.versionCode})")
    appendLine("- Android: ${environment.androidVersion}")
    appendLine("- Device: ${environment.device}")
    appendLine()
    appendLine("<!-- 由 Zhixing 内置工具整理；提交前已由用户确认。 -->")
}

private fun currentIssueEnvironment() = GitHubIssueEnvironment(
    appVersion = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE,
    androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
    device = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Unknown" },
)

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

private fun JsonObject.optionalString(key: String): String? =
    string(key).takeIf { it.isNotBlank() }

private fun stringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}
