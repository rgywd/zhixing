package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.memory.findMemoryContentFormatIssues
import me.rerere.rikkahub.data.memory.requireValidMemoryDocument
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryDocument
import me.rerere.rikkahub.data.repository.MemoryDocumentRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val documents by vm.memoryDocuments.collectAsStateWithLifecycle()
    val error by vm.memoryDocumentError.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_memory)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        MemoryDocumentContent(
            innerPadding = innerPadding,
            assistant = assistant,
            documents = documents,
            error = error,
            onUpdateAssistant = vm::update,
            onSave = vm::saveMemoryDocument,
            onDelete = vm::deleteMemoryDocument,
            onDismissError = vm::clearMemoryDocumentError,
        )
    }
}

internal data class MemoryPage<T>(
    val items: List<T>,
    val page: Int,
    val totalPages: Int,
)

internal fun <T> paginateItems(
    items: List<T>,
    requestedPage: Int,
    pageSize: Int = 5,
): MemoryPage<T> {
    require(pageSize > 0)
    val totalPages = maxOf(1, (items.size + pageSize - 1) / pageSize)
    val page = requestedPage.coerceIn(0, totalPages - 1)
    val start = page * pageSize
    return MemoryPage(
        items = items.drop(start).take(pageSize),
        page = page,
        totalPages = totalPages,
    )
}

@Composable
private fun MemoryDocumentContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    documents: List<MemoryDocument>,
    error: String?,
    onUpdateAssistant: (Assistant) -> Unit,
    onSave: (MemoryDocument) -> Unit,
    onDelete: (MemoryDocument) -> Unit,
    onDismissError: () -> Unit,
) {
    var editing by remember { mutableStateOf<MemoryDocument?>(null) }
    var pendingDelete by remember { mutableStateOf<MemoryDocument?>(null) }
    val regularDocuments = documents.filterNot { it.path.startsWith("/archive/") }
    val legacyDocuments = documents.filter { it.path.startsWith("/archive/") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("两套机制，互不混用", fontWeight = FontWeight.SemiBold)
                Text(
                    "记忆文档只保存可追溯的用户原话；历史聊天是原始全文检索，不会自动写进记忆。" +
                        "对话开场只加载文件清单以及 profile/preferences，其他文档由 AI 按需读取。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        CardGroup {
            item(
                headlineContent = { Text("记忆文档") },
                supportingContent = { Text("允许读取和维护长期笔记") },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMemory,
                        onCheckedChange = { onUpdateAssistant(assistant.copy(enableMemory = it)) },
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_global_memory_desc)) },
                trailingContent = {
                    Switch(
                        checked = assistant.useGlobalMemory,
                        onCheckedChange = { onUpdateAssistant(assistant.copy(useGlobalMemory = it)) },
                        enabled = assistant.enableMemory,
                    )
                },
            )
            item(
                headlineContent = { Text("历史对话检索") },
                supportingContent = { Text("允许按关键词或最近时间读取原始聊天；不会改写记忆文档") },
                trailingContent = {
                    Switch(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = {
                            onUpdateAssistant(assistant.copy(enableRecentChatsReference = it))
                        },
                    )
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("记忆文件", style = MaterialTheme.typography.titleMedium)
                Text(
                    "profile/preferences 常驻；areas/topics/people 仅按需读取",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    editing = MemoryDocument(
                        scopeId = if (assistant.useGlobalMemory) {
                            MemoryDocumentRepository.GLOBAL_SCOPE_ID
                        } else {
                            assistant.id.toString()
                        },
                        path = "/areas/new-area.md",
                        name = "New area",
                        description = "User-stated facts about this active area.",
                        version = 0,
                    )
                },
            ) {
                Icon(HugeIcons.Add01, contentDescription = null)
                Text("新增")
            }
        }

        regularDocuments.forEach { document ->
            MemoryDocumentCard(
                document = document,
                onEdit = { editing = document },
                onDelete = if (document.path in MemoryDocumentRepository.PINNED_PATHS) {
                    null
                } else {
                    { pendingDelete = document }
                },
            )
        }

        if (legacyDocuments.isNotEmpty()) {
            Text("旧版迁移归档", style = MaterialTheme.typography.titleMedium)
            Text(
                "旧画像和情境记录不会自动送入上下文；可直接整理，删除 legacy 条目会同步清理旧记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            legacyDocuments.forEach { document ->
                MemoryDocumentCard(
                    document = document,
                    onEdit = { editing = document },
                    onDelete = { pendingDelete = document },
                )
            }
        }
    }

    editing?.let { document ->
        MemoryDocumentEditor(
            initial = document,
            onDismiss = { editing = null },
            onSave = {
                onSave(it)
                editing = null
            },
        )
    }

    RikkaConfirmDialog(
        show = pendingDelete != null,
        title = "删除整个记忆文件？",
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDelete?.let(onDelete)
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
        text = { Text(pendingDelete?.path.orEmpty()) },
    )

    if (error != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("记忆未保存") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("知道了") } },
        )
    }
}

@Composable
private fun MemoryDocumentCard(
    document: MemoryDocument,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(document.path, fontWeight = FontWeight.SemiBold)
                    Text(
                        document.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                onEdit?.let {
                    IconButton(onClick = it) {
                        Icon(HugeIcons.PencilEdit01, contentDescription = "编辑")
                    }
                }
                onDelete?.let {
                    IconButton(onClick = it) {
                        Icon(HugeIcons.Delete01, contentDescription = "删除")
                    }
                }
            }
            if (document.content.isNotBlank()) {
                Text(
                    document.content,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "version ${document.version} · ${document.sources.size} 个来源" +
                    document.aliases.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = " · ").orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MemoryDocumentEditor(
    initial: MemoryDocument,
    onDismiss: () -> Unit,
    onSave: (MemoryDocument) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val isArchive = initial.path.startsWith("/archive/")
    val validationError = remember(draft) {
        runCatching {
            requireValidMemoryDocument(
                path = draft.path,
                name = draft.name,
                description = draft.description,
                aliases = draft.aliases,
                content = draft.content.trim(),
            )
        }.exceptionOrNull()?.message
    }
    val formatIssues = remember(draft.content, isArchive) {
        if (isArchive) emptyList() else findMemoryContentFormatIssues(draft.content)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.version == 0L) "新增记忆文件" else "编辑记忆文件") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextField(
                    value = draft.path,
                    onValueChange = { draft = draft.copy(path = it) },
                    label = { Text("路径") },
                    supportingText = { Text("/areas、/topics 或 /people 下的 .md；profile/preferences 固定") },
                    enabled = initial.version == 0L,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = draft.description,
                    onValueChange = { draft = draft.copy(description = it) },
                    label = { Text("一句话描述") },
                    supportingText = { Text("AI 依靠这句话决定是否按需读取") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = draft.aliases.joinToString(", "),
                    onValueChange = { value ->
                        draft = draft.copy(
                            aliases = value.split(',').map(String::trim).filter(String::isNotBlank)
                        )
                    },
                    label = { Text("别名（逗号分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = draft.content,
                    onValueChange = { draft = draft.copy(content = it) },
                    label = { Text("正文") },
                    supportingText = {
                        Text(
                            if (isArchive) {
                                "可整理说明或移除 legacy 条目；已有 #id 可保留或删除，不能新增或改写 ID"
                            } else {
                                "每条事实单独使用 - [stated]；日期 YYYY-MM-DD，时间 HH:mm，物理量使用 kg、cm 等标准单位"
                            }
                        )
                    },
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
                validationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (validationError == null && formatIssues.isNotEmpty()) {
                    Text(
                        "格式建议：" + formatIssues.joinToString("；") { it.uiMessage },
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft.copy(content = draft.content.trim())) },
                enabled = validationError == null,
            ) { Text(stringResource(R.string.assistant_page_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.assistant_page_cancel)) }
        },
    )
}
