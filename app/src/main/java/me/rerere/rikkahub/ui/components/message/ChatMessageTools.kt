package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant

private const val ASK_USER_TOOL_NAME = "ask_user"

@Composable
fun ChainOfThoughtScope.ChatMessageResearchPurposeStep(
    purpose: String,
    tools: List<UIMessagePart.Tool>,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToolCancel: ((toolCallId: String) -> Unit)? = null,
) {
    var expanded by remember(purpose) { mutableStateOf(false) }
    val groupLoading = loading && tools.any { !it.isExecuted }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (groupLoading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            Text(
                text = purpose,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column {
                tools.forEach { tool ->
                    key(tool.toolCallId.ifBlank { tool.hashCode().toString() }) {
                        ChatMessageToolStep(
                            tool = tool,
                            loading = loading && !tool.isExecuted,
                            onToolApproval = onToolApproval,
                            onToolAnswer = onToolAnswer,
                            onToolCancel = onToolCancel,
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToolCancel: ((toolCallId: String) -> Unit)? = null,
) {
    // ask_user 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(
            tool = tool,
            loading = loading,
            onToolAnswer = onToolAnswer,
            onToolCancel = onToolCancel,
        )
        return
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool, loading) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                null
            },
            loading = loading,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()

    // 摘要由注册的渲染器决定; 图片输出与拒绝原因为所有工具通用
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = renderer.title(context),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onToolCancel != null) {
                        TextButton(onClick = { onToolCancel(tool.toolCallId) }) {
                            Text("取消", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    FilledTonalIconButton(
                        onClick = { showDenyDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onToolApproval(tool.toolCallId, true, "") },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        } else {
            null
        },
        onClick = if (context.content != null || isPending || images.isNotEmpty()) {
            { showResult = true }
        } else {
            null
        },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    renderer.Summary(context)
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason)
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                renderer.Preview(
                    context = context,
                    onDismissRequest = { showResult = false },
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
    onToolCancel: ((toolCallId: String) -> Unit)?,
) {
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    // Parse questions from arguments
    val questions = remember(arguments) {
        runCatching {
            arguments.jsonObject["questions"]?.jsonArray?.map { q ->
                val obj = q.jsonObject
                AskUserQuestion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                    header = obj["header"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // Track answers for text/single questions
    val answers = remember { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }
    // "Type something" 自由文本
    val customTexts = remember { mutableStateMapOf<String, String>() }
    // "Chat about this" 讨论标记
    val discuss = remember { mutableStateMapOf<String, Boolean>() }
    var selectedTab by remember { mutableIntStateOf(0) }

    fun effectiveAnswer(q: AskUserQuestion): String? {
        val custom = customTexts[q.id]?.takeIf { it.isNotBlank() }
        return if (q.selectionType == "multi") {
            val combined = multiAnswers[q.id].orEmpty() + listOfNotNull(custom)
            combined.takeIf { it.isNotEmpty() }?.joinToString(", ")
        } else {
            custom ?: answers[q.id]?.takeIf { it.isNotBlank() }
        }
    }

    fun stateOf(q: AskUserQuestion): AskQState = when {
        discuss[q.id] == true -> AskQState.DISCUSS
        effectiveAnswer(q) != null -> AskQState.ANSWERED
        else -> AskQState.UNANSWERED
    }

    val currentIndex = selectedTab.coerceIn(0, (questions.size - 1).coerceAtLeast(0))

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isPending && onToolAnswer != null) {
                    // 多题时顶部 tab 条, tab 上的圆点标记作答状态
                    if (questions.size > 1) {
                        SecondaryScrollableTabRow(
                            selectedTabIndex = currentIndex,
                            containerColor = Color.Transparent,
                            modifier = Modifier.fillMaxWidth(),
                            edgePadding = 0.dp,
                        ) {
                            questions.forEachIndexed { index, q ->
                                Tab(
                                    selected = currentIndex == index,
                                    onClick = { selectedTab = index },
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(
                                                        when (stateOf(q)) {
                                                            AskQState.ANSWERED -> MaterialTheme.colorScheme.primary
                                                            AskQState.DISCUSS -> MaterialTheme.colorScheme.tertiary
                                                            AskQState.UNANSWERED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                                        },
                                                        CircleShape,
                                                    )
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = q.header.ifBlank { "Q${index + 1}" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }

                    questions.getOrNull(currentIndex)?.let { q ->
                        AskUserQuestionBody(
                            question = q,
                            selectedOption = answers[q.id],
                            multiSelected = multiAnswers[q.id] ?: emptySet(),
                            customText = customTexts[q.id] ?: "",
                            isDiscuss = discuss[q.id] == true,
                            onOptionSelect = { option ->
                                answers[q.id] = option
                                customTexts.remove(q.id)
                            },
                            onMultiToggle = { option ->
                                val current = multiAnswers[q.id].orEmpty().toMutableSet()
                                if (!current.add(option)) current.remove(option)
                                multiAnswers[q.id] = current
                            },
                            onCustomTextChange = { text ->
                                customTexts[q.id] = text
                                if (text.isNotBlank() && q.selectionType != "multi") {
                                    answers.remove(q.id)
                                }
                            },
                            onDiscussToggle = {
                                discuss[q.id] = !(discuss[q.id] ?: false)
                            },
                        )
                    }
                } else if (isAnswered) {
                    // 冻结展示: 答案或 discuss 徽标
                    val answeredState = tool.approvalState as ToolApprovalState.Answered
                    val answerJson = runCatching {
                        JsonInstant.parseToJsonElement(answeredState.answer)
                    }.getOrNull()
                    val answersObj = answerJson?.jsonObject?.get("answers")?.jsonObject
                    val discussIds = answerJson?.jsonObject?.get("discuss")?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()

                    questions.forEach { q ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = q.question,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (q.id in discussIds) {
                                Text(
                                    text = stringResource(R.string.chat_message_tool_ask_discussed_badge),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            } else {
                                val answerText = answersObj?.get(q.id)?.jsonPrimitive?.contentOrNull
                                    ?: answeredState.answer
                                Text(
                                    text = answerText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                // Submit button
                if (isPending && (onToolAnswer != null || onToolCancel != null)) {
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onToolCancel != null) {
                            TextButton(onClick = { onToolCancel(tool.toolCallId) }) {
                                Text(stringResource(R.string.chat_message_tool_cancel))
                            }
                        }
                        if (onToolAnswer != null) {
                            FilledTonalButton(
                                onClick = {
                                    // discuss 的题从 answers 省略, 单独放进 discuss 数组
                                    val answerPayload = buildJsonObject {
                                        put("answers", buildJsonObject {
                                            questions.filter { discuss[it.id] != true }.forEach { q ->
                                                put(q.id, JsonPrimitive(effectiveAnswer(q) ?: ""))
                                            }
                                        })
                                        val discussIds = questions.mapNotNull { q ->
                                            q.id.takeIf { discuss[q.id] == true }
                                        }
                                        if (discussIds.isNotEmpty()) {
                                            put("discuss", buildJsonArray {
                                                discussIds.forEach { add(it) }
                                            })
                                        }
                                    }
                                    onToolAnswer(tool.toolCallId, answerPayload.toString())
                                },
                                enabled = questions.isNotEmpty() && questions.all { stateOf(it) != AskQState.UNANSWERED },
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Tick01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.chat_message_tool_submit),
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val header: String = "",
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

private enum class AskQState { UNANSWERED, ANSWERED, DISCUSS }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskUserQuestionBody(
    question: AskUserQuestion,
    selectedOption: String?,
    multiSelected: Set<String>,
    customText: String,
    isDiscuss: Boolean,
    onOptionSelect: (String) -> Unit,
    onMultiToggle: (String) -> Unit,
    onCustomTextChange: (String) -> Unit,
    onDiscussToggle: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = question.question,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            question.options.forEach { option ->
                when (question.selectionType) {
                    "multi" -> FilterChip(
                        selected = multiSelected.contains(option),
                        enabled = !isDiscuss,
                        onClick = { onMultiToggle(option) },
                        label = {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )

                    else -> FilterChip(
                        selected = selectedOption == option,
                        enabled = !isDiscuss,
                        onClick = { onOptionSelect(option) },
                        label = {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }

            // 固定项: 就这一点聊聊 (标记该题为待讨论)
            FilterChip(
                selected = isDiscuss,
                onClick = onDiscussToggle,
                label = {
                    Text(
                        text = stringResource(R.string.chat_message_tool_ask_chat_about),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = HugeIcons.BubbleChatQuestion,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            )
        }

        // 固定项: 自由文本
        Text(
            text = stringResource(R.string.chat_message_tool_ask_type_something),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = customText,
            onValueChange = onCustomTextChange,
            enabled = !isDiscuss,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = false,
            minLines = 1,
            maxLines = 3,
        )
    }
}

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
