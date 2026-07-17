package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.workflow.WorkAgent
import me.rerere.rikkahub.data.workflow.WorkReasoningEffort
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkPresetEditPage(
    presetId: String? = null,
    machineId: String? = null,
    path: String? = null,
    vm: WorkPresetEditVM = koinViewModel(),
) {
    val navController = LocalNavController.current
    var deleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.prefill(presetId, machineId, path) }
    LaunchedEffect(vm.finished) {
        if (vm.finished) navController.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isExisting) "编辑仓库预设" else "新建仓库预设") },
                navigationIcon = { BackButton() },
                actions = {
                    if (vm.isExisting) {
                        TextButton(onClick = { deleteConfirmation = true }, enabled = !vm.isSaving) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                FormItem(label = { Text("名称") }) {
                    OutlinedTextField(
                        value = vm.name,
                        onValueChange = vm::updateName,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !vm.isSaving,
                    )
                }
            }
            item {
                FormItem(label = { Text("开发机") }) {
                    OptionDropdown(
                        current = vm.machines.firstOrNull { it.id == vm.machineId }?.label ?: "选择开发机",
                        enabled = !vm.isSaving,
                        options = vm.machines.map { machine -> machine.label to { vm.updateMachine(machine.id) } },
                        emptyHint = "尚未同步到开发机",
                    )
                }
            }
            item {
                FormItem(
                    label = { Text("工作目录") },
                    description = { Text("开发机上的绝对路径或 ~ 路径") },
                ) {
                    OutlinedTextField(
                        value = vm.path,
                        onValueChange = vm::updatePath,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !vm.isSaving,
                    )
                }
            }
            item {
                FormItem(
                    label = { Text("默认分支") },
                    description = { Text("可选，用于任务描述与后续的保护分支限制") },
                ) {
                    OutlinedTextField(
                        value = vm.defaultBranch,
                        onValueChange = vm::updateDefaultBranch,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("main") },
                        enabled = !vm.isSaving,
                    )
                }
            }
            item {
                FormItem(label = { Text("默认 Agent") }) {
                    val agents = listOf(WorkAgent.CODEX to "Codex", WorkAgent.CLAUDE to "Claude Code")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        agents.forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = vm.agent == value,
                                onClick = { vm.updateAgent(value) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = agents.size),
                                enabled = !vm.isSaving,
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }
            item {
                FormItem(
                    label = { Text("默认模型") },
                    description = { Text("留空使用开发机上的默认模型") },
                ) {
                    OutlinedTextField(
                        value = vm.model,
                        onValueChange = vm::updateModel,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("默认模型") },
                        enabled = !vm.isSaving,
                    )
                }
            }
            item {
                FormItem(
                    label = { Text("默认思考深度") },
                    description = {
                        Text(
                            if (WorkReasoningEffort.isRemotelyApplicable(vm.agent)) {
                                "经 CLAUDE_CODE_EFFORT_LEVEL 下发到开发机"
                            } else {
                                "Codex 暂不支持远程设置，请在开发机 ~/.codex/config.toml 配置 model_reasoning_effort"
                            }
                        )
                    },
                ) {
                    OptionDropdown(
                        current = vm.reasoningEffort.toReasoningLabel(),
                        enabled = !vm.isSaving && WorkReasoningEffort.isRemotelyApplicable(vm.agent),
                        options = listOf<Pair<String, () -> Unit>>("默认" to { vm.updateReasoningEffort(null) }) +
                            WorkReasoningEffort.levelsFor(vm.agent).map { effort ->
                                effort.toReasoningLabel() to { vm.updateReasoningEffort(effort) }
                            },
                    )
                }
            }
            item {
                FormItem(
                    label = { Text("默认完全访问") },
                    description = { Text("此仓库的新任务默认以完全访问模式启动") },
                    tail = {
                        Switch(
                            checked = vm.fullAccess,
                            onCheckedChange = vm::updateFullAccess,
                            enabled = !vm.isSaving,
                        )
                    },
                )
            }
            item {
                FormItem(
                    label = { Text("硬性限制") },
                    description = {
                        Text(
                            "每行一条 disallowedTools 规则，即使完全访问模式也会远端强制，" +
                                "例如 Bash(git push --force*)。仅 Claude Code 生效，Codex 侧不支持。"
                        )
                    },
                ) {
                    OutlinedTextField(
                        value = vm.disallowedToolsText,
                        onValueChange = vm::updateDisallowedTools,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Bash(git push --force*)\nBash(rm -rf*)") },
                        minLines = 2,
                        maxLines = 6,
                        enabled = !vm.isSaving,
                    )
                }
            }
            vm.error?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Button(
                    onClick = vm::save,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = vm.canSave,
                ) {
                    if (vm.isSaving) CircularProgressIndicator(strokeWidth = 2.dp)
                    else Text("保存预设")
                }
            }
        }
    }

    if (deleteConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text("删除这个仓库预设？") },
            text = { Text("已有会话不受影响，仅移除预设配置。") },
            confirmButton = {
                Button(onClick = {
                    deleteConfirmation = false
                    vm.delete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmation = false }) { Text("取消") }
            },
        )
    }
}
