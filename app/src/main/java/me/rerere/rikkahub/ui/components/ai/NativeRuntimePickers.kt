package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ToggleSurface

data class NativeRuntimeChoice(
    val id: String,
    val label: String,
    val detail: String? = null,
)

/** Runtime-neutral model picker with the same icon-and-sheet interaction as Provider Chat. */
@Composable
fun NativeModelChoiceSelector(
    current: NativeRuntimeChoice?,
    choices: List<NativeRuntimeChoice>,
    onSelect: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    IconButton(onClick = { visible = true }) {
        if (current == null) {
            Icon(HugeIcons.Brain02, contentDescription = "选择模型", modifier = Modifier.size(20.dp))
        } else {
            AutoAIIcon(name = current.id, modifier = Modifier.size(36.dp))
        }
    }
    NativeRuntimeChoiceSheet(
        visible = visible,
        title = "选择模型",
        currentId = current?.id,
        choices = choices,
        onDismiss = { visible = false },
        onSelect = onSelect,
    )
}

/** Runtime-neutral reasoning picker occupying the same light-bulb control position as Provider Chat. */
@Composable
fun NativeReasoningChoiceSelector(
    currentId: String?,
    choices: List<NativeRuntimeChoice>,
    onSelect: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    ToggleSurface(
        checked = currentId != null && currentId !in setOf("none", "minimal"),
        onClick = { visible = true },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.Idea01, contentDescription = "选择思考强度", modifier = Modifier.size(24.dp))
        }
    }
    NativeRuntimeChoiceSheet(
        visible = visible,
        title = "思考强度",
        currentId = currentId,
        choices = choices,
        onDismiss = { visible = false },
        onSelect = onSelect,
    )
}

@Composable
private fun NativeRuntimeChoiceSheet(
    visible: Boolean,
    title: String,
    currentId: String?,
    choices: List<NativeRuntimeChoice>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    if (!visible) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            choices.forEach { choice ->
                ListItem(
                    headlineContent = { Text(choice.label) },
                    supportingContent = choice.detail?.let { detail -> ({ Text(detail) }) },
                    trailingContent = {
                        if (choice.id == currentId) Text("已选择", color = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (choice.id == currentId) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            onSelect(choice.id)
                            onDismiss()
                        },
                )
            }
        }
    }
}
