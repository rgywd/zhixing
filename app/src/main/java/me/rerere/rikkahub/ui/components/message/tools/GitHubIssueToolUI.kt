package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.openUrl

object GitHubIssueToolUI : ToolUIRenderer {
    override val toolName: String = "submit_github_issue"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Bug01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(
            R.string.chat_message_tool_github_issue,
            context.arguments.getStringContent("title").orEmpty(),
        )

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("status") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val appContext = LocalContext.current
        val url = context.content.getStringContent("url")
        val status = context.content.getStringContent("status")
        val number = context.content.getStringContent("number")?.toIntOrNull()
        val message = context.content.getStringContent("message").orEmpty()
        Text(
            text = if (status == "CREATED" && number != null) {
                stringResource(R.string.tool_ui_github_issue_created, number)
            } else {
                message
            },
            modifier = Modifier.clickable(enabled = status == "CREATED" && url != null) {
                url?.let(appContext::openUrl)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (status == "CREATED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
