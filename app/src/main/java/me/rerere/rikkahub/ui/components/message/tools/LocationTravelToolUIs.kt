package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.json.JsonArray
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Location01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.modifier.shimmer

object CurrentLocationToolUI : ToolUIRenderer {
    override val toolName: String = "get_current_location"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Location01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_current_location)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("address") != null ||
            context.content.getStringContent("message") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        LocationTravelSummary(
            text = context.content.getStringContent("address")
                ?: context.content.getStringContent("message").orEmpty(),
            loading = context.loading,
        )
    }
}

object NearbyPlacesToolUI : ToolUIRenderer {
    override val toolName: String = "search_nearby_places"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(
        R.string.chat_message_tool_nearby_places,
        context.arguments.getStringContent("query").orEmpty(),
    )

    private fun places(context: ToolUIContext): List<kotlinx.serialization.json.JsonElement> =
        (context.content?.jsonObjectOrNull?.get("places") as? JsonArray).orEmpty()

    override fun hasSummary(context: ToolUIContext): Boolean =
        places(context).isNotEmpty() || context.content.getStringContent("message") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val placeNames = places(context)
            .take(3)
            .mapNotNull { it.getStringContent("name") }
        val text = if (placeNames.isNotEmpty()) {
            placeNames.joinToString(" · ")
        } else {
            context.content.getStringContent("message").orEmpty()
        }
        LocationTravelSummary(text = text, loading = context.loading)
    }
}

object OpenNavigationToolUI : ToolUIRenderer {
    override val toolName: String = "open_navigation"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Location01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(
        R.string.chat_message_tool_open_navigation,
        context.arguments.getStringContent("destination_name").orEmpty(),
    )

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("target") != null ||
            context.content.getStringContent("message") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val target = when (context.content.getStringContent("target")) {
            "amap_app" -> stringResource(R.string.tool_ui_navigation_amap_app)
            "amap_web" -> stringResource(R.string.tool_ui_navigation_amap_web)
            else -> context.content.getStringContent("message").orEmpty()
        }
        LocationTravelSummary(text = target, loading = context.loading)
    }
}

@Composable
private fun LocationTravelSummary(text: String, loading: Boolean) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = androidx.compose.ui.Modifier.shimmer(isLoading = loading),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
