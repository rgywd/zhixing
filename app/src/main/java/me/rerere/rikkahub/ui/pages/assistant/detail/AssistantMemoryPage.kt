package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.Refresh01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemoryState
import me.rerere.rikkahub.data.model.ProfileDimensions
import me.rerere.rikkahub.data.datastore.ProfileMaintenanceConfig
import me.rerere.rikkahub.data.datastore.ProfileMaintenanceStatus
import me.rerere.rikkahub.data.datastore.ProfileMaintenanceStrategy
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.DateFormat
import java.util.Date

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val profileMemories by vm.profileMemories.collectAsStateWithLifecycle()
    val contextMemories by vm.contextMemories.collectAsStateWithLifecycle()
    val archivedMemories by vm.archivedMemories.collectAsStateWithLifecycle()
    val pendingProfileMemories by vm.pendingProfileMemories.collectAsStateWithLifecycle()
    val profileMaintenanceConfig by vm.profileMaintenanceConfig.collectAsStateWithLifecycle()
    val profileMaintenanceStatus by vm.profileMaintenanceStatus.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantMemoryContent(
            innerPadding = innerPadding,
            assistant = assistant,
            profileMemories = profileMemories,
            contextMemories = contextMemories,
            archivedMemories = archivedMemories,
            pendingProfileMemories = pendingProfileMemories,
            profileMaintenanceConfig = profileMaintenanceConfig,
            profileMaintenanceStatus = profileMaintenanceStatus,
            onUpdateAssistant = { vm.update(it) },
            onDeleteMemory = { vm.deleteMemory(it) },
            onAddMemory = { vm.addMemory(it) },
            onUpdateMemory = { vm.updateMemory(it) },
            onArchiveMemory = { vm.archiveMemory(it) },
            onRestoreMemory = { vm.restoreMemory(it) },
            onConfirmPendingMemory = { vm.confirmPendingMemory(it) },
            onUpdateProfileMaintenanceConfig = vm::updateProfileMaintenanceConfig,
            onRunProfileMaintenanceNow = vm::runProfileMaintenanceNow,
        )
    }
}

@Composable
private fun AssistantMemoryContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    profileMemories: List<AssistantMemory>,
    contextMemories: List<AssistantMemory>,
    archivedMemories: List<AssistantMemory>,
    pendingProfileMemories: List<AssistantMemory>,
    profileMaintenanceConfig: ProfileMaintenanceConfig,
    profileMaintenanceStatus: ProfileMaintenanceStatus,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    onArchiveMemory: (AssistantMemory) -> Unit,
    onRestoreMemory: (AssistantMemory) -> Unit,
    onConfirmPendingMemory: (AssistantMemory) -> Unit,
    onUpdateProfileMaintenanceConfig: (ProfileMaintenanceConfig) -> Unit,
    onRunProfileMaintenanceNow: () -> Unit,
) {
    val memoryDialogState = useEditState<AssistantMemory> {
        if (it.id == 0) {
            onAddMemory(it)
        } else {
            onUpdateMemory(it)
        }
    }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }
    var showMaintenanceSettings by remember { mutableStateOf(false) }

    // 记忆对话框
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = {
                memoryDialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.assistant_page_manage_memory_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (memory.kind == MemoryKind.PROFILE) {
                        Select(
                            options = ProfileDimensions.builtIn,
                            selectedOption = memory.dimensionId.takeIf { it in ProfileDimensions.builtIn }
                                ?: ProfileDimensions.IDENTITY_CONTEXT,
                            onOptionSelected = { update(memory.copy(dimensionId = it)) },
                            optionToString = { profileDimensionLabel(it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextField(
                        value = memory.content,
                        onValueChange = {
                            update(memory.copy(content = it))
                        },
                        label = {
                            Text(stringResource(R.string.assistant_page_manage_memory_title))
                        },
                        minLines = 2,
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableMemory = it
                                )
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_global_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.useGlobalMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    useGlobalMemory = it
                                )
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_recent_chats_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableRecentChatsReference = it
                                )
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_time_reminder_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableTimeReminder,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableTimeReminder = it
                                )
                            )
                        }
                    )
                }
            )
        }

        ProfileMaintenanceCard(
            config = profileMaintenanceConfig,
            status = profileMaintenanceStatus,
            prerequisitesMet = assistant.enableMemory &&
                assistant.useGlobalMemory &&
                assistant.enableRecentChatsReference,
            onToggle = { enabled ->
                onUpdateProfileMaintenanceConfig(profileMaintenanceConfig.copy(enabled = enabled))
            },
            onOpenSettings = { showMaintenanceSettings = true },
            onRunNow = onRunProfileMaintenanceNow,
        )

        ProfileDimensions.builtIn.forEach { dimensionId ->
            MemorySection(
                title = profileDimensionLabel(dimensionId),
                description = profileDimensionDescription(dimensionId),
                memories = profileMemories.filter { it.dimensionId == dimensionId },
                onAdd = {
                    memoryDialogState.open(
                        AssistantMemory(0, kind = MemoryKind.PROFILE, dimensionId = dimensionId)
                    )
                },
                onEdit = memoryDialogState::open,
                onArchive = onArchiveMemory,
            )
        }

        val uncategorizedProfiles = profileMemories.filter { it.dimensionId !in ProfileDimensions.builtIn }
        if (uncategorizedProfiles.isNotEmpty()) {
            MemorySection(
                title = "其他画像",
                description = "旧版本或未来扩展维度中的画像。",
                memories = uncategorizedProfiles,
                onEdit = memoryDialogState::open,
                onArchive = onArchiveMemory,
            )
        }

        if (pendingProfileMemories.isNotEmpty()) {
            MemorySection(
                title = "待确认画像",
                description = "自动整理出的候选项，确认后才会在后续对话中使用。",
                memories = pendingProfileMemories,
                onEdit = memoryDialogState::open,
                onArchive = onArchiveMemory,
                onConfirm = onConfirmPendingMemory,
            )
        }

        MemorySection(
            title = stringResource(R.string.assistant_page_context_memory),
            description = stringResource(R.string.assistant_page_context_memory_desc),
            memories = contextMemories,
            onAdd = { memoryDialogState.open(AssistantMemory(0, kind = MemoryKind.CONTEXT)) },
            onEdit = memoryDialogState::open,
            onArchive = onArchiveMemory,
        )

        if (archivedMemories.isNotEmpty()) {
            MemorySection(
                title = stringResource(R.string.assistant_page_archived_memory),
                description = stringResource(R.string.assistant_page_archived_memory_desc),
                memories = archivedMemories,
                onRestore = onRestoreMemory,
                onDelete = { pendingDeleteMemory = it },
            )
        }
    }

    if (showMaintenanceSettings) {
        ProfileMaintenanceSettingsDialog(
            config = profileMaintenanceConfig,
            onDismiss = { showMaintenanceSettings = false },
            onSave = {
                onUpdateProfileMaintenanceConfig(it)
                showMaintenanceSettings = false
            },
        )
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun MemorySection(
    title: String,
    description: String,
    memories: List<AssistantMemory>,
    onAdd: (() -> Unit)? = null,
    onEdit: ((AssistantMemory) -> Unit)? = null,
    onArchive: ((AssistantMemory) -> Unit)? = null,
    onRestore: ((AssistantMemory) -> Unit)? = null,
    onDelete: ((AssistantMemory) -> Unit)? = null,
    onConfirm: ((AssistantMemory) -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(modifier = Modifier.padding(end = if (onAdd != null) 48.dp else 0.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
        onAdd?.let { add ->
            IconButton(onClick = add, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = stringResource(R.string.assistant_page_add_memory),
                )
            }
        }
    }

    if (memories.isEmpty()) {
        Text(
            text = stringResource(R.string.assistant_page_memory_empty),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    memories.fastForEach { memory ->
        key(memory.id) {
            MemoryItem(
                memory = memory,
                onEditMemory = onEdit,
                onArchiveMemory = onArchive,
                onRestoreMemory = onRestore,
                onDeleteMemory = onDelete,
                onConfirmMemory = onConfirm,
            )
        }
    }
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: ((AssistantMemory) -> Unit)?,
    onArchiveMemory: ((AssistantMemory) -> Unit)?,
    onRestoreMemory: ((AssistantMemory) -> Unit)?,
    onDeleteMemory: ((AssistantMemory) -> Unit)?,
    onConfirmMemory: ((AssistantMemory) -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = buildString {
                        append("#${memory.id} · ")
                        append(
                            if (memory.kind == MemoryKind.PROFILE) {
                                profileDimensionLabel(memory.dimensionId)
                            } else {
                                stringResource(R.string.assistant_page_context_memory)
                            }
                        )
                    },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = memory.content,

                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (memory.source == MemorySource.AUTO) {
                    Text(
                        text = "自动整理 · ${(memory.confidence * 100).toInt()}% · ${memory.evidenceConversationIds.size} 条证据",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            onConfirmMemory?.let { confirm ->
                TextButton(onClick = { confirm(memory) }) { Text("确认") }
            }
            onEditMemory?.let { edit ->
                IconButton(onClick = { edit(memory) }) {
                    Icon(HugeIcons.PencilEdit01, stringResource(R.string.assistant_page_edit_memory))
                }
            }
            onArchiveMemory?.let { archive ->
                IconButton(onClick = { archive(memory) }) {
                    Icon(HugeIcons.Eraser, stringResource(R.string.assistant_page_archive_memory))
                }
            }
            onRestoreMemory?.let { restore ->
                IconButton(onClick = { restore(memory) }) {
                    Icon(HugeIcons.Refresh01, stringResource(R.string.assistant_page_restore_memory))
                }
            }
            onDeleteMemory?.let { delete ->
                IconButton(onClick = { delete(memory) }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.assistant_page_delete))
                }
            }
        }
    }
}

@Composable
private fun ProfileMaintenanceCard(
    config: ProfileMaintenanceConfig,
    status: ProfileMaintenanceStatus,
    prerequisitesMet: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onRunNow: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("画像自动维护", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (prerequisitesMet) {
                            "使用快速模型增量整理发生变化的历史对话。"
                        } else {
                            "需要同时开启记忆、全局记忆和参考历史聊天记录。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = onToggle,
                    enabled = prerequisitesMet || config.enabled,
                )
            }
            Text(
                text = profileMaintenanceSummary(config, status),
                style = MaterialTheme.typography.bodySmall,
                color = if (status.lastError.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenSettings) { Text("维护设置") }
                TextButton(
                    onClick = onRunNow,
                    enabled = config.enabled && prerequisitesMet,
                ) { Text("立即维护") }
            }
        }
    }
}

@Composable
private fun ProfileMaintenanceSettingsDialog(
    config: ProfileMaintenanceConfig,
    onDismiss: () -> Unit,
    onSave: (ProfileMaintenanceConfig) -> Unit,
) {
    var draft by remember(config) { mutableStateOf(config) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("画像维护设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingSelectRow(
                    title = "维护间隔",
                    value = draft.intervalHours,
                    options = listOf(1, 3, 6, 12, 24),
                    label = { "$it 小时" },
                    onSelect = { draft = draft.copy(intervalHours = it) },
                )
                SettingSelectRow(
                    title = "维护策略",
                    value = draft.strategy,
                    options = ProfileMaintenanceStrategy.entries,
                    label = {
                        when (it) {
                            ProfileMaintenanceStrategy.CONSERVATIVE -> "保守 · 90%"
                            ProfileMaintenanceStrategy.BALANCED -> "均衡 · 80%"
                            ProfileMaintenanceStrategy.AGGRESSIVE -> "积极 · 70%"
                        }
                    },
                    onSelect = { draft = draft.copy(strategy = it) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动应用高置信度画像")
                        Text("关闭后候选项进入待确认区。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = draft.autoApply,
                        onCheckedChange = { draft = draft.copy(autoApply = it) },
                    )
                }
                SettingSelectRow(
                    title = "最少证据次数",
                    value = draft.minimumEvidence,
                    options = (1..5).toList(),
                    label = { "$it 个对话" },
                    onSelect = { draft = draft.copy(minimumEvidence = it) },
                )
                SettingSelectRow(
                    title = "单轮处理上限",
                    value = draft.maxConversationsPerRun,
                    options = listOf(5, 10, 20, 50, 100),
                    label = { "$it 个对话" },
                    onSelect = { draft = draft.copy(maxConversationsPerRun = it) },
                )
                Text(
                    "模型固定复用“快速模型”设置；Android 后台任务按执行窗口运行，不保证精确到点。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.normalized()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun <T> SettingSelectRow(
    title: String,
    value: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Select(
            options = options,
            selectedOption = value,
            onOptionSelected = onSelect,
            optionToString = label,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun profileMaintenanceSummary(
    config: ProfileMaintenanceConfig,
    status: ProfileMaintenanceStatus,
): String {
    if (status.lastError.isNotBlank()) return "上次维护失败：${status.lastError}"
    if (status.lastSuccessAt == 0L) {
        return if (config.enabled) "尚未完成首次维护 · 每 ${config.intervalHours} 小时" else "当前未启用"
    }
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(status.lastSuccessAt))
    return "上次 $time · 处理 ${status.lastProcessedConversations} 个对话 · " +
        "新增 ${status.lastCreated} · 更新 ${status.lastUpdated} · 待确认 ${status.lastPending}"
}

private fun profileDimensionLabel(dimensionId: String): String = when (dimensionId) {
    ProfileDimensions.IDENTITY_CONTEXT -> "身份与背景"
    ProfileDimensions.PREFERENCES_VALUES -> "偏好与取向"
    ProfileDimensions.CAPABILITIES_KNOWLEDGE -> "能力与知识"
    ProfileDimensions.BEHAVIOR_COLLABORATION -> "行为与协作方式"
    else -> "未分类画像"
}

private fun profileDimensionDescription(dimensionId: String): String = when (dimensionId) {
    ProfileDimensions.IDENTITY_CONTEXT -> "长期角色、领域、语言、设备环境和稳定背景。"
    ProfileDimensions.PREFERENCES_VALUES -> "产品审美、技术选择、兴趣以及明确喜欢或排斥的方案。"
    ProfileDimensions.CAPABILITIES_KNOWLEDGE -> "熟悉领域、技术栈、工具水平和已有经验。"
    ProfileDimensions.BEHAVIOR_COLLABORATION -> "沟通、决策、工作节奏、风险和交付偏好。"
    else -> "旧版本或未来扩展维度中的画像。"
}
