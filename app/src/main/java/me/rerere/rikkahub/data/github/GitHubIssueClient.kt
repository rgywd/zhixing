package me.rerere.rikkahub.data.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.AppIdentity
import me.rerere.rikkahub.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class CreatedGitHubIssue(
    val number: Int,
    val url: String,
)

class GitHubIssueApiException(
    val statusCode: Int,
    message: String,
) : Exception(message)

class GitHubIssueClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val endpoint: String = "https://api.github.com/repos/${AppIdentity.repositoryOwner}/${AppIdentity.repositoryName}/issues",
) {
    suspend fun createIssue(
        token: String,
        title: String,
        body: String,
        label: String,
    ): CreatedGitHubIssue = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("title", title)
            put("body", body)
            put("labels", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(label)) })
        }.toString()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "${AppIdentity.userAgentProduct}/${BuildConfig.VERSION_NAME}")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw GitHubIssueApiException(
                    statusCode = response.code,
                    message = githubErrorMessage(response.code),
                )
            }
            val json = Json.parseToJsonElement(responseBody).jsonObject
            CreatedGitHubIssue(
                number = json.getValue("number").jsonPrimitive.int,
                url = json.getValue("html_url").jsonPrimitive.content,
            )
        }
    }

    private fun githubErrorMessage(statusCode: Int): String = when (statusCode) {
        401, 403 -> "GitHub authorization failed. Update the Issue token in Settings > About."
        404 -> "The GitHub repository was not found or the token cannot access it."
        422 -> "GitHub rejected the issue content. Please revise the request and try again."
        else -> "GitHub issue creation failed (HTTP $statusCode)."
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
