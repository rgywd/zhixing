package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R

object KnowledgeStatusToolUI : ToolUIRenderer {
    override val toolName = "knowledge_status"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.BookOpen01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.workspace_detail_tool_knowledge_status)

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val initialized = context.content.getStringContent("initialized")?.toBooleanStrictOrNull() ?: false
        val sources = context.content.getStringContent("sourceCount")?.toIntOrNull() ?: 0
        val indexed = context.content.getStringContent("indexedDocumentCount")?.toIntOrNull() ?: 0
        Text(
            if (initialized) stringResource(R.string.workspace_detail_knowledge_counts, sources, indexed)
            else stringResource(R.string.workspace_detail_knowledge_not_initialized),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

object KnowledgeSearchToolUI : ToolUIRenderer {
    override val toolName = "knowledge_search"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String {
        val query = context.arguments.getStringContent("query")
        return query?.let { stringResource(R.string.tool_ui_knowledge_search, it) }
            ?: stringResource(R.string.workspace_detail_tool_knowledge_search)
    }

    private fun matches(context: ToolUIContext): List<JsonElement> = runCatching {
        context.content?.jsonObjectOrNull?.get("matches")?.jsonArray?.toList().orEmpty()
    }.getOrDefault(emptyList())

    override fun hasSummary(context: ToolUIContext): Boolean = matches(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            matches(context).take(5).forEach { item ->
                val source = item.getStringContent("sourcePath") ?: item.getStringContent("path") ?: "-"
                val line = item.getStringContent("line") ?: "?"
                val excerpt = item.getStringContent("excerpt").orEmpty()
                Text("$source:L$line", style = MaterialTheme.typography.labelMedium)
                Text(excerpt, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

object KnowledgeReadToolUI : ToolUIRenderer {
    override val toolName = "knowledge_read"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileView

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return path?.let { stringResource(R.string.tool_ui_knowledge_read, it) }
            ?: stringResource(R.string.workspace_detail_tool_knowledge_read)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("text") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        Text(
            text = context.content.getStringContent("text").orEmpty(),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

object KnowledgeIngestToolUI : ToolUIRenderer {
    override val toolName = "knowledge_ingest"
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileImport

    @Composable
    override fun title(context: ToolUIContext): String {
        val name = context.arguments.getStringContent("upload_name")
        return name?.let { stringResource(R.string.tool_ui_knowledge_ingest, it) }
            ?: stringResource(R.string.workspace_detail_tool_knowledge_ingest)
    }
}
