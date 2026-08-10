package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.KnowledgeSpaceStatus
import me.rerere.workspace.VaultGitAvailability
import me.rerere.workspace.VaultGitStatus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFile(inputStream, fileName)
    }
    val knowledgePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "knowledge_file"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importKnowledgeDocument(
            inputStream = inputStream,
            fileName = fileName,
            mimeType = context.contentResolver.getType(uri),
        )
    }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportFile(entry, outputStream)
    }

    BackHandler(
        enabled = (pagerState.currentPage == 1 && state.path.isNotBlank()) ||
            (pagerState.currentPage == 2 && state.vault.path.isNotBlank())
    ) {
        if (pagerState.currentPage == 2) {
            vm.goUpVault()
        } else {
            vm.goUp()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (pagerState.currentPage == 1) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (pagerState.currentPage == 2) {
                                vm.refreshVault()
                            } else {
                                vm.refresh()
                            }
                        }
                    ) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                    if (state.workspace?.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                            Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    label = { Text(stringResource(R.string.workspace_detail_tab_basic)) },
                    icon = { Icon(HugeIcons.Settings03, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    label = { Text(stringResource(R.string.workspace_detail_tab_vault)) },
                    icon = { Icon(HugeIcons.BookOpen01, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                )
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> WorkspaceBasicPage(
                    workspace = state.workspace,
                    installProgress = installProgress,
                    knowledgeStatus = state.knowledgeStatus,
                    knowledgeBusy = state.knowledgeBusy,
                    error = state.error,
                    onInitializeKnowledge = vm::initializeKnowledgeSpace,
                    onImportKnowledge = { knowledgePicker.launch(arrayOf("*/*")) },
                    onInstallRootfs = { showInstallDialog = true },
                )

                1 -> WorkspaceFilesPage(
                    state = state,
                    contentPadding = PaddingValues(),
                    onSelectArea = vm::selectArea,
                    onGoUp = vm::goUp,
                    onOpen = { entry ->
                        when {
                            entry.isDirectory -> vm.open(entry)

                            else -> when (entry.detectFileType()) {
                                WorkspaceFileType.TEXT -> navController.navigate(
                                    Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                )

                                WorkspaceFileType.IMAGE -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                    // 传绝对路径 (而非 content:// URI): Coil 可直接加载,
                                    // 预览弹窗的保存按钮 saveMessageImage 只认 "/" 开头路径, content URI 会报错
                                    previewImageUri = file.absolutePath
                                }

                                WorkspaceFileType.OTHER -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                        file.extension.lowercase()
                                    ) ?: "*/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(intent, null))
                                    }
                                }
                            }
                        }
                    },
                    onDelete = { deleteTarget = it },
                    onExport = { entry ->
                        exportTarget = entry
                        exportLauncher.launch(entry.name)
                    },
                    onShare = { entry ->
                        vm.exportToCacheFile(entry, context.cacheDir) { file ->
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    },
                )

                2 -> KnowledgeVaultPage(
                    initialized = state.knowledgeStatus?.initialized == true,
                    state = state.vault,
                    onInitialize = vm::initializeKnowledgeSpace,
                    onGoUp = vm::goUpVault,
                    onOpen = { entry ->
                        when {
                            entry.isDirectory -> vm.openVault(entry)

                            else -> when (entry.detectFileType()) {
                                WorkspaceFileType.TEXT -> navController.navigate(
                                    Screen.WorkspaceFileEditor(
                                        id,
                                        WorkspaceStorageArea.FILES.name,
                                        entry.path,
                                    )
                                )

                                WorkspaceFileType.IMAGE -> vm.exportToCacheFile(
                                    entry = entry,
                                    cacheDir = context.cacheDir,
                                    area = WorkspaceStorageArea.FILES,
                                ) { file ->
                                    previewImageUri = file.absolutePath
                                }

                                WorkspaceFileType.OTHER -> vm.exportToCacheFile(
                                    entry = entry,
                                    cacheDir = context.cacheDir,
                                    area = WorkspaceStorageArea.FILES,
                                ) { file ->
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                        file.extension.lowercase()
                                    ) ?: "*/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(intent, null))
                                    }
                                }
                            }
                        }
                    },
                    onInstallGit = vm::installVaultGit,
                    onBindGit = vm::bindVaultGitRemote,
                )
            }
        }
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                onDismiss = { showInstallDialog = false },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
            )
        }
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }

    deleteTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title = if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }

    state.vault.pendingRemoteUrl?.let {
        AlertDialog(
            onDismissRequest = vm::dismissVaultGitRemoteConflict,
            title = { Text(stringResource(R.string.workspace_detail_vault_git_replace_title)) },
            text = { Text(stringResource(R.string.workspace_detail_vault_git_replace_desc)) },
            dismissButton = {
                TextButton(onClick = vm::dismissVaultGitRemoteConflict) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = vm::replaceVaultGitRemote) {
                    Text(stringResource(R.string.workspace_detail_vault_git_replace_confirm))
                }
            },
        )
    }
}

@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    installProgress: RootfsInstallProgress?,
    knowledgeStatus: KnowledgeSpaceStatus?,
    knowledgeBusy: Boolean,
    error: String?,
    onInitializeKnowledge: () -> Unit,
    onImportKnowledge: () -> Unit,
    onInstallRootfs: () -> Unit,
) {
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { message ->
            item { ErrorCard(message) }
        }

        item {
            KnowledgeSpaceCard(
                workspaceAvailable = workspace != null,
                status = knowledgeStatus,
                busy = knowledgeBusy,
                onInitialize = onInitializeKnowledge,
                onImport = onImportKnowledge,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_workspace_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_name), workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_shell_status), workspace?.shellStatus?.toShellStatusLabel() ?: "-")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onInstallRootfs,
                        enabled = workspace != null && !installing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Bash, contentDescription = null)
                        Text(
                            text = installButtonText,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    installProgress?.let { progress ->
                        RootfsProgress(progress)
                    }
                }
            }
        }

    }
}

@Composable
private fun KnowledgeSpaceCard(
    workspaceAvailable: Boolean,
    status: KnowledgeSpaceStatus?,
    busy: Boolean,
    onInitialize: () -> Unit,
    onImport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HugeIcons.BookOpen01, contentDescription = null)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.workspace_detail_knowledge_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_knowledge_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (status?.initialized == true) {
                Text(
                    text = stringResource(
                        R.string.workspace_detail_knowledge_counts,
                        status.contentFileCount,
                        status.indexedDocumentCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onImport,
                    enabled = workspaceAvailable && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.FileImport, contentDescription = null)
                    Text(
                        text = stringResource(R.string.workspace_detail_knowledge_import),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.workspace_detail_knowledge_not_initialized),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onInitialize,
                    enabled = workspaceAvailable && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.workspace_detail_knowledge_initialize))
                }
            }

            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun KnowledgeVaultPage(
    initialized: Boolean,
    state: KnowledgeVaultState,
    onInitialize: () -> Unit,
    onGoUp: () -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onInstallGit: () -> Unit,
    onBindGit: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { message ->
            item { ErrorCard(message) }
        }

        if (!initialized) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CustomColors.cardColorsOnSurfaceContainer,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_detail_knowledge_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.workspace_detail_knowledge_not_initialized),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onInitialize,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.workspace_detail_knowledge_initialize))
                        }
                    }
                }
            }
        } else {
            item {
                VaultGitBindingCard(
                    status = state.gitStatus,
                    busy = state.gitBusy,
                    onInstallGit = onInstallGit,
                    onBind = onBindGit,
                )
            }
            item {
                WorkspacePathBar(
                    path = buildString {
                        append("/workspace/vault")
                        if (state.path.isNotBlank()) append("/${state.path}")
                    },
                    canGoUp = state.path.isNotBlank(),
                    onGoUp = onGoUp,
                )
            }

            if (state.loading) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (state.entries.isEmpty() && state.error == null) {
                item {
                    EmptyDirectoryState()
                }
            }

            items(state.entries, key = { "vault:${it.path}" }) { entry ->
                WorkspaceFileCard(
                    entry = entry,
                    onOpen = { onOpen(entry) },
                    onDelete = {},
                    onExport = {},
                    onShare = {},
                    showActions = false,
                )
            }
        }
    }
}

@Composable
private fun VaultGitBindingCard(
    status: VaultGitStatus?,
    busy: Boolean,
    onInstallGit: () -> Unit,
    onBind: (String) -> Unit,
) {
    var remoteUrl by rememberSaveable { mutableStateOf("") }
    val canBind = status?.availability == VaultGitAvailability.READY && !busy

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HugeIcons.Folder01, contentDescription = null)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.workspace_detail_vault_git_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_vault_git_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (status?.availability) {
                null -> Text(
                    text = stringResource(R.string.workspace_detail_vault_git_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                VaultGitAvailability.ROOTFS_REQUIRED -> Text(
                    text = stringResource(R.string.workspace_detail_vault_git_rootfs_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                VaultGitAvailability.GIT_REQUIRED -> {
                    Text(
                        text = stringResource(R.string.workspace_detail_vault_git_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = onInstallGit,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (busy) {
                                    R.string.workspace_detail_vault_git_installing
                                } else {
                                    R.string.workspace_detail_vault_git_install
                                }
                            )
                        )
                    }
                }

                VaultGitAvailability.READY -> {
                    val statusText = when {
                        status.isBound -> stringResource(
                            R.string.workspace_detail_vault_git_bound,
                            status.remoteUrl.orEmpty(),
                            status.branch ?: "main",
                        )

                        status.isRepository ->
                            stringResource(R.string.workspace_detail_vault_git_repo_only)

                        else -> stringResource(R.string.workspace_detail_vault_git_unbound)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status.hasChanges) {
                        Text(
                            text = stringResource(R.string.workspace_detail_vault_git_has_changes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = remoteUrl,
                onValueChange = { remoteUrl = it },
                enabled = canBind,
                singleLine = true,
                label = { Text(stringResource(R.string.workspace_detail_vault_git_remote_label)) },
                placeholder = { Text("https://github.com/owner/vault.git") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onBind(remoteUrl) },
                enabled = canBind && remoteUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (status?.isBound == true) {
                            R.string.workspace_detail_vault_git_update
                        } else {
                            R.string.workspace_detail_vault_git_bind
                        }
                    )
                )
            }
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(DEFAULT_ROOTFS_URL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    contentPadding: PaddingValues,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            WorkspaceAreaSelector(
                selected = state.area,
                onSelected = onSelectArea,
            )
        }

        item {
            WorkspacePathBar(
                path = state.path,
                canGoUp = state.path.isNotBlank(),
                onGoUp = onGoUp,
            )
        }

        state.error?.let { error ->
            item {
                ErrorCard(error)
            }
        }

        if (!state.loading && state.entries.isEmpty() && state.error == null) {
            item {
                EmptyDirectoryState()
            }
        }

        items(state.entries, key = { "${state.area.name}:${it.path}" }) { entry ->
            WorkspaceFileCard(
                entry = entry,
                onOpen = { onOpen(entry) },
                onDelete = { onDelete(entry) },
                onExport = { onExport(entry) },
                onShare = { onShare(entry) },
            )
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    val areas = listOf(
        WorkspaceStorageArea.FILES to stringResource(R.string.workspace_detail_area_files),
        WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_detail_area_rootfs),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        areas.forEachIndexed { index, (area, label) ->
            SegmentedButton(
                selected = selected == area,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            enabled = canGoUp,
            onClick = onGoUp,
        ) {
            Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
        }
        Text(
            text = path.ifBlank { "/" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    showActions: Boolean = true,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) entry.path else "${entry.path} · ${entry.sizeBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showActions) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(HugeIcons.MoreVertical, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (!entry.isDirectory) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_export)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = HugeIcons.FileImport,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onExport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_share)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = HugeIcons.Share08,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onShare()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.common_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDirectoryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_detail_empty_directory),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun String.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
    else -> lowercase()
}

private val DEFAULT_ROOTFS_URL: String
    get() {
        val archiveArchitecture = when {
            Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "amd64"
            else -> "arm64"
        }
        return "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" +
            "ubuntu-base-24.04.3-base-$archiveArchitecture.tar.gz"
    }
