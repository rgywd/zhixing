package me.rerere.rikkahub.ui.pages.agenda

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.agenda.AgendaPlanEditorIssue
import me.rerere.rikkahub.data.agenda.AgendaPlanEditorIssueCode
import me.rerere.rikkahub.data.agenda.AgendaPlanEditorState
import me.rerere.rikkahub.data.agenda.AgendaPlanEditorSubmission
import me.rerere.rikkahub.data.agenda.AgendaPlanStageEditorState
import me.rerere.rikkahub.data.agenda.AgendaPlanStageTimeField
import me.rerere.rikkahub.data.agenda.AgendaPlanTimeEditorValue
import me.rerere.rikkahub.data.agenda.AgendaPlanTimeMode
import me.rerere.rikkahub.data.agenda.evaluateAgendaPlanEditor
import me.rerere.rikkahub.data.agenda.newAgendaPlanEditorState
import me.rerere.rikkahub.data.agenda.toAgendaPlanEditorState
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun AgendaPlanEditorSheet(
    initialPlan: AgendaPlanWithStages?,
    onDismiss: () -> Unit,
    onSubmit: (AgendaPlanEditorSubmission) -> Unit,
    submitting: Boolean = false,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var state by remember(initialPlan?.plan?.id) {
        mutableStateOf(initialPlan?.toAgendaPlanEditorState() ?: newAgendaPlanEditorState())
    }
    var issues by remember(initialPlan?.plan?.id) {
        mutableStateOf<List<AgendaPlanEditorIssue>>(emptyList())
    }

    fun update(next: AgendaPlanEditorState) {
        state = next
        if (issues.isNotEmpty()) issues = emptyList()
    }

    fun updateStage(index: Int, transform: (AgendaPlanStageEditorState) -> AgendaPlanStageEditorState) {
        update(
            state.copy(
                stages = state.stages.mapIndexed { stageIndex, stage ->
                    if (stageIndex == index) transform(stage) else stage
                }
            )
        )
    }

    fun pickEventTime() {
        showAgendaDateTimePicker(
            context = context,
            zone = zone,
            initialAt = state.eventAt,
            defaultAt = ZonedDateTime.now(zone).plusDays(7).toInstant().toEpochMilli(),
        ) { selected -> update(state.copy(eventAt = selected)) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (state.isEditing) "编辑长期计划" else "新建长期计划",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "计划发生时间是阶段相对分钟偏移的基准；负数表示提前，正数表示发生后。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.title,
                onValueChange = { update(state.copy(title = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("计划标题") },
                placeholder = { Text("例如：准备公司年会") },
                isError = issues.has(AgendaPlanEditorIssueCode.PLAN_TITLE_REQUIRED),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.note,
                onValueChange = { update(state.copy(note = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("说明（可选）") },
                minLines = 2,
            )
            OutlinedTextField(
                value = state.location,
                onValueChange = { update(state.copy(location = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("地点（可选）") },
                singleLine = true,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("计划发生时间", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = ::pickEventTime, modifier = Modifier.weight(1f)) {
                        Text(state.eventAt?.let(::formatAgendaEditorTime) ?: "选择日期和时间")
                    }
                    if (state.eventAt != null) {
                        TextButton(onClick = { update(state.copy(eventAt = null)) }) {
                            Text("清除")
                        }
                    }
                }
                if (issues.has(AgendaPlanEditorIssueCode.EVENT_TIME_REQUIRED)) {
                    Text(
                        text = "请设置计划发生时间",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider()
            Text(
                text = "阶段 · ${state.stages.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.isEditing) {
                Text(
                    text = "编辑时保留已有阶段的数量和顺序。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.stages.forEachIndexed { index, stage ->
                AgendaPlanStageEditorCard(
                    index = index,
                    stage = stage,
                    eventAt = state.eventAt,
                    zone = zone,
                    issues = issues,
                    topologyEditable = !state.isEditing,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.stages.lastIndex,
                    canDelete = state.stages.size > 1,
                    onStageChange = { next -> updateStage(index) { next } },
                    onMoveUp = {
                        update(state.copy(stages = state.stages.moved(index, index - 1)))
                    },
                    onMoveDown = {
                        update(state.copy(stages = state.stages.moved(index, index + 1)))
                    },
                    onDelete = {
                        update(state.copy(stages = state.stages.filterIndexed { itemIndex, _ -> itemIndex != index }))
                    },
                )
            }

            if (!state.isEditing) {
                OutlinedButton(
                    onClick = {
                        update(state.copy(stages = state.stages + AgendaPlanStageEditorState()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("添加阶段")
                }
            }

            if (issues.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        issues.distinctBy { Triple(it.code, it.stageIndex, it.timeField) }.forEach { issue ->
                            Text("• ${issue.message}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    enabled = !submitting,
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val evaluation = evaluateAgendaPlanEditor(state)
                        issues = evaluation.issues
                        evaluation.submission?.let(onSubmit)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !submitting,
                ) {
                    Text(if (submitting) "保存中…" else "保存")
                }
            }
        }
    }
}

@Composable
private fun AgendaPlanStageEditorCard(
    index: Int,
    stage: AgendaPlanStageEditorState,
    eventAt: Long?,
    zone: ZoneId,
    issues: List<AgendaPlanEditorIssue>,
    topologyEditable: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onStageChange: (AgendaPlanStageEditorState) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "阶段 ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (topologyEditable) {
                    TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("上移") }
                    TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("下移") }
                    TextButton(onClick = onDelete, enabled = canDelete) { Text("删除") }
                }
            }
            OutlinedTextField(
                value = stage.title,
                onValueChange = { onStageChange(stage.copy(title = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("阶段标题") },
                placeholder = { Text("例如：购买车票") },
                isError = issues.has(AgendaPlanEditorIssueCode.STAGE_TITLE_REQUIRED, index),
                singleLine = true,
            )
            OutlinedTextField(
                value = stage.note,
                onValueChange = { onStageChange(stage.copy(note = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("阶段说明（可选）") },
                minLines = 2,
            )
            AgendaStageTimeFieldEditor(
                label = "计划时间",
                value = stage.scheduled,
                defaultAt = eventAt,
                zone = zone,
                isError = issues.hasTimeIssue(index, AgendaPlanStageTimeField.SCHEDULED),
                onValueChange = { onStageChange(stage.withTime(AgendaPlanStageTimeField.SCHEDULED, it)) },
            )
            AgendaStageTimeFieldEditor(
                label = "截止时间",
                value = stage.due,
                defaultAt = eventAt,
                zone = zone,
                isError = issues.hasTimeIssue(index, AgendaPlanStageTimeField.DUE),
                onValueChange = { onStageChange(stage.withTime(AgendaPlanStageTimeField.DUE, it)) },
            )
            AgendaStageTimeFieldEditor(
                label = "触发时间",
                value = stage.trigger,
                defaultAt = eventAt,
                zone = zone,
                isError = issues.hasTimeIssue(index, AgendaPlanStageTimeField.TRIGGER),
                onValueChange = { onStageChange(stage.withTime(AgendaPlanStageTimeField.TRIGGER, it)) },
            )
            AgendaStageTimeFieldEditor(
                label = "提醒时间",
                value = stage.reminder,
                defaultAt = eventAt,
                zone = zone,
                isError = issues.hasTimeIssue(index, AgendaPlanStageTimeField.REMINDER),
                onValueChange = { onStageChange(stage.withTime(AgendaPlanStageTimeField.REMINDER, it)) },
            )
        }
    }
}

@Composable
private fun AgendaStageTimeFieldEditor(
    label: String,
    value: AgendaPlanTimeEditorValue,
    defaultAt: Long?,
    zone: ZoneId,
    isError: Boolean,
    onValueChange: (AgendaPlanTimeEditorValue) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AgendaTimeModeChip(
                text = "无",
                selected = value.mode == AgendaPlanTimeMode.NONE,
                onClick = { onValueChange(AgendaPlanTimeEditorValue.none()) },
            )
            AgendaTimeModeChip(
                text = "日期时间",
                selected = value.mode == AgendaPlanTimeMode.ABSOLUTE,
                onClick = {
                    onValueChange(
                        AgendaPlanTimeEditorValue.absolute(
                            value.absoluteAt ?: defaultAt ?: ZonedDateTime.now(zone).plusHours(1)
                                .toInstant()
                                .toEpochMilli()
                        )
                    )
                },
            )
            AgendaTimeModeChip(
                text = "相对分钟",
                selected = value.mode == AgendaPlanTimeMode.RELATIVE,
                onClick = {
                    onValueChange(AgendaPlanTimeEditorValue.relative(value.offsetMinutes))
                },
            )
        }
        when (value.mode) {
            AgendaPlanTimeMode.NONE -> Unit
            AgendaPlanTimeMode.ABSOLUTE -> {
                OutlinedButton(
                    onClick = {
                        showAgendaDateTimePicker(
                            context = context,
                            zone = zone,
                            initialAt = value.absoluteAt,
                            defaultAt = defaultAt
                                ?: ZonedDateTime.now(zone).plusHours(1).toInstant().toEpochMilli(),
                        ) { selected ->
                            onValueChange(AgendaPlanTimeEditorValue.absolute(selected))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(value.absoluteAt?.let(::formatAgendaEditorTime) ?: "选择日期和时间")
                }
            }
            AgendaPlanTimeMode.RELATIVE -> {
                OutlinedTextField(
                    value = value.offsetMinutes,
                    onValueChange = { onValueChange(AgendaPlanTimeEditorValue.relative(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("相对发生时间的分钟偏移") },
                    placeholder = { Text("-21600") },
                    supportingText = { Text("负数表示提前，例如 -21600 = 提前 15 天") },
                    isError = isError,
                    singleLine = true,
                )
            }
        }
        if (isError && value.mode == AgendaPlanTimeMode.ABSOLUTE) {
            Text(
                text = "请选择日期和时间",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AgendaTimeModeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
    )
}

private fun showAgendaDateTimePicker(
    context: Context,
    zone: ZoneId,
    initialAt: Long?,
    defaultAt: Long,
    onSelected: (Long) -> Unit,
) {
    val initial = Instant.ofEpochMilli(initialAt ?: defaultAt).atZone(zone)
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onSelected(
                        ZonedDateTime.of(year, month + 1, day, hour, minute, 0, 0, zone)
                            .toInstant()
                            .toEpochMilli()
                    )
                },
                initial.hour,
                initial.minute,
                true,
            ).show()
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).show()
}

private fun List<AgendaPlanStageEditorState>.moved(
    fromIndex: Int,
    toIndex: Int,
): List<AgendaPlanStageEditorState> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    return toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

private fun List<AgendaPlanEditorIssue>.has(
    code: AgendaPlanEditorIssueCode,
    stageIndex: Int? = null,
): Boolean = any { issue ->
    issue.code == code && (stageIndex == null || issue.stageIndex == stageIndex)
}

private fun List<AgendaPlanEditorIssue>.hasTimeIssue(
    stageIndex: Int,
    field: AgendaPlanStageTimeField,
): Boolean = any { issue ->
    issue.stageIndex == stageIndex &&
        issue.timeField == field &&
        issue.code in setOf(
            AgendaPlanEditorIssueCode.ABSOLUTE_TIME_REQUIRED,
            AgendaPlanEditorIssueCode.INVALID_RELATIVE_OFFSET,
        )
}

private fun formatAgendaEditorTime(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneId.systemDefault())
    .format(AGENDA_EDITOR_TIME_FORMATTER)

private val AGENDA_EDITOR_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
