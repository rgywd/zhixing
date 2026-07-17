package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.workflow.WorkAgent
import me.rerere.rikkahub.data.workflow.WorkReasoningEffort
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkNewTaskPage(
    presetId: String? = null,
    machineId: String? = null,
    path: String? = null,
    vm: WorkNewTaskVM = koinViewModel(),
) {
    val navController = LocalNavController.current
    var fullAccessConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.prefill(presetId, machineId, path) }
    LaunchedEffect(vm.createdSessionId) {
        vm.createdSessionId?.let { id ->
            vm.consumeCreatedSession()
            navController.popBackStack()
            navController.navigate(Screen.WorkflowSession(id))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建任务") },
                navigationIcon = { BackButton() },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                FormItem(
                    label = { Text("仓库预设") },
                    description = { Text("选择预设后自动填入机器、目录与默认配置") },
                ) {
                    OptionDropdown(
                        current = vm.presets.firstOrNull { it.id == vm.selectedPresetId }?.name ?: "不使用预设",
                        enabled = !vm.isSubmitting,
                        options = listOf<Pair<String, () -> Unit>>("不使用预设" to { vm.clearPreset() }) +
                            vm.presets.map { preset -> preset.name to { vm.applyPreset(preset) } },
                    )
                }
            }
            item {
                FormItem(label = { Text("开发机") }) {
                    val online = vm.machines.filter { it.active }
                    OptionDropdown(
                        current = vm.machines.firstOrNull { it.id == vm.machineId }?.label ?: "选择在线开发机",
                        enabled = !vm.isSubmitting,
                        options = online.map { machine -> machine.label to { vm.updateMachine(machine.id) } },
                        emptyHint = "没有在线开发机",
                    )
                }
            }
            item {
                FormItem(
                    label = { Text("项目目录") },
                    description = { Text("开发机上的绝对路径或 ~ 路径") },
                ) {
                    OutlinedTextField(
                        value = vm.path,
                        onValueChange = vm::updatePath,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !vm.isSubmitting,
                    )
                }
            }
            item {
                FormItem(label = { Text("Agent") }) {
                    val agents = listOf(WorkAgent.CODEX to "Codex", WorkAgent.CLAUDE to "Claude Code")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        agents.forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = vm.agent == value,
                                onClick = { vm.updateAgent(value) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = agents.size),
                                enabled = !vm.isSubmitting,
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }
            item {
                FormItem(
                    label = { Text("模型") },
                    description = { Text("留空使用开发机上的默认模型") },
                ) {
                    OutlinedTextField(
                        value = vm.model,
                        onValueChange = vm::updateModel,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("默认模型") },
                        enabled = !vm.isSubmitting,
                    )
                }
            }
            item {
                FormItem(
                    label = { Text("思考深度") },
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
                        enabled = !vm.isSubmitting && WorkReasoningEffort.isRemotelyApplicable(vm.agent),
                        options = listOf<Pair<String, () -> Unit>>("默认" to { vm.updateReasoningEffort(null) }) +
                            WorkReasoningEffort.levelsFor(vm.agent).map { effort ->
                                effort.toReasoningLabel() to { vm.updateReasoningEffort(effort) }
                            },
                    )
                }
            }
            item {
                FormItem(
                    label = { Text("完全访问") },
                    description = {
                        Text("远端自动执行命令与写入，仅在失败或触碰硬性限制时回来找你；Codex 侧硬性限制不生效")
                    },
                    tail = {
                        Switch(
                            checked = vm.fullAccess,
                            onCheckedChange = { checked ->
                                if (checked) fullAccessConfirmation = true else vm.updateFullAccess(false)
                            },
                            enabled = !vm.isSubmitting,
                        )
                    },
                )
            }
            item {
                FormItem(label = { Text("首条指令") }) {
                    OutlinedTextField(
                        value = vm.prompt,
                        onValueChange = vm::updatePrompt,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如：修复 #42 的 CI 失败，先复现再改") },
                        minLines = 3,
                        maxLines = 8,
                        enabled = !vm.isSubmitting,
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
                    onClick = { vm.submit() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = vm.canSubmit,
                ) {
                    if (vm.isSubmitting) CircularProgressIndicator(strokeWidth = 2.dp)
                    else Text("启动任务")
                }
            }
        }
    }

    if (fullAccessConfirmation) {
        AlertDialog(
            onDismissRequest = { fullAccessConfirmation = false },
            title = { Text("开启完全访问？") },
            text = {
                Text(
                    "本次任务将以 bypassPermissions 在开发机上自主执行读写、命令、测试和 Git 操作，" +
                        "不再逐步请求确认。任务完成、失败或被阻止时才会通知你。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    fullAccessConfirmation = false
                    vm.updateFullAccess(true)
                }) { Text("开启") }
            },
            dismissButton = {
                TextButton(onClick = { fullAccessConfirmation = false }) { Text("取消") }
            },
        )
    }
    vm.pendingDirectory?.let { directory ->
        AlertDialog(
            onDismissRequest = vm::dismissDirectoryApproval,
            title = { Text("创建项目目录？") },
            text = { Text("开发机上不存在 $directory。确认后将创建目录并启动任务。") },
            confirmButton = { Button(onClick = vm::approveDirectoryCreation) { Text("创建并启动") } },
            dismissButton = { TextButton(onClick = vm::dismissDirectoryApproval) { Text("取消") } },
        )
    }
}

@Composable
internal fun OptionDropdown(
    current: String,
    enabled: Boolean,
    options: List<Pair<String, () -> Unit>>,
    emptyHint: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            Text(current)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty() && emptyHint != null) {
                DropdownMenuItem(text = { Text(emptyHint) }, onClick = { expanded = false })
            }
            options.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        action()
                        expanded = false
                    },
                )
            }
        }
    }
}

internal fun String?.toReasoningLabel(): String = when (this) {
    "none" -> "关闭 (none)"
    "minimal" -> "极简 (minimal)"
    "low" -> "轻度 (low)"
    "medium" -> "中 (medium)"
    "high" -> "高 (high)"
    "xhigh" -> "极高 (xhigh)"
    "max" -> "最大 (max)"
    "ultra" -> "Ultra（更快消耗额度）"
    else -> "默认"
}
