package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import me.rerere.rikkahub.AppIdentity
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.utils.openUrl
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
    val url: String,
)

internal fun buildGitHubIssueTool(context: Context): Tool = Tool(
    name = "submit_github_issue",
    description = """
        Prepare a feature request or bug report for the Zhixing GitHub repository and open the prefilled
        GitHub issue page for the user to review and submit. Prefer type 'feature' for product ideas and
        requirements. Never include API keys, tokens, private conversations, personal data, or other secrets.
        This tool always requires user approval and does not claim the issue is submitted until the user
        confirms it on GitHub.
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
        withContext(Dispatchers.Main) {
            context.openUrl(draft.url)
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("status", "AWAITING_USER_SUBMISSION")
            put("type", draft.type)
            put("title", draft.title)
            put("label", draft.label)
            put("url", draft.url)
            put("message", "The prefilled GitHub issue page is open. Review it and submit on GitHub.")
        }.toString()))
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
    val url = buildGitHubIssueUrl(title, body, label)
    return GitHubIssueDraft(type, title, body, label, url)
}

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

private fun buildGitHubIssueUrl(title: String, body: String, label: String): String =
    "${AppIdentity.issueTrackerUrl}/new" +
        "?title=${title.urlEncode()}&body=${body.urlEncode()}&labels=${label.urlEncode()}"

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

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
