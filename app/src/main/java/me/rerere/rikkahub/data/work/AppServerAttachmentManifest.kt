package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class AppServerAttachedFile(
    val name: String,
    val path: String,
    val mime: String,
)

data class ParsedAttachmentManifest(
    val visibleText: String,
    val files: List<AppServerAttachedFile>,
)

/**
 * App Server's `mention` input is reserved for apps/plugins; it does not make an arbitrary local
 * file model-visible. Zhixing therefore sends a compact, machine-readable local-file manifest as
 * text and strips it again at the UI projection boundary.
 */
object AppServerAttachmentManifest {
    private const val OPEN = "<zhixing_file_attachments>"
    private const val CLOSE = "</zhixing_file_attachments>"
    private val block = Regex("${Regex.escape(OPEN)}(.*?)${Regex.escape(CLOSE)}", RegexOption.DOT_MATCHES_ALL)

    fun append(text: String, files: List<AppServerAttachedFile>): String {
        if (files.isEmpty()) return text
        val payload = buildJsonObject {
            put(
                "instruction",
                "These local paths are files explicitly attached by the user. Inspect them when relevant.",
            )
            put("files", buildJsonArray {
                files.forEach { file ->
                    add(buildJsonObject {
                        put("name", file.name)
                        put("path", file.path)
                        put("mime", file.mime)
                    })
                }
            })
        }
        return buildString {
            append(text)
            if (text.isNotBlank()) append("\n\n")
            append(OPEN)
            append(payload)
            append(CLOSE)
        }
    }

    fun parse(text: String): ParsedAttachmentManifest {
        val files = block.findAll(text).flatMap { match ->
            val payload = runCatching { Json.parseToJsonElement(match.groupValues[1]) as? JsonObject }.getOrNull()
            (payload?.get("files") as? JsonArray).orEmpty().mapNotNull { element ->
                val raw = element as? JsonObject ?: return@mapNotNull null
                val name = (raw["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val path = (raw["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val mime = (raw["mime"] as? JsonPrimitive)?.contentOrNull ?: "application/octet-stream"
                AppServerAttachedFile(name, path, mime)
            }
        }.toList()
        return ParsedAttachmentManifest(
            visibleText = block.replace(text, "").trimEnd(),
            files = files,
        )
    }
}
