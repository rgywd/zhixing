package me.rerere.rikkahub.data.status

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbe
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import me.rerere.rikkahub.data.repository.MemoryDocumentRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.util.concurrent.atomic.AtomicBoolean

internal enum class MyStatusRefreshTrigger {
    APP_START,
    USER_VISIBLE,
    LOCATION_PERMISSION,
    BODY_CHANGED,
    AGENDA_CHANGED,
    PROFILE_CHANGED,
    SETTINGS_CHANGED,
    HOURLY,
}

internal class MyStatusSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): MyStatusSnapshot? = preferences.getString(KEY_SNAPSHOT, null)?.let { json ->
        runCatching { JsonInstant.decodeFromString<MyStatusSnapshot>(json) }.getOrNull()
    }

    fun save(snapshot: MyStatusSnapshot) {
        preferences.edit {
            putString(KEY_SNAPSHOT, JsonInstant.encodeToString(snapshot))
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "zhixing.my_status"
        const val KEY_SNAPSHOT = "snapshot"
    }
}

@OptIn(FlowPreview::class)
internal class MyStatusCoordinator(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val contextAssembler: MyStatusContextAssembler,
    private val textGenerator: MyStatusTextGenerator,
    private val snapshotStore: MyStatusSnapshotStore,
    private val watchProbe: LenovoWatchProbe,
    private val agendaTaskRepository: AgendaTaskRepository,
    private val agendaPlanRepository: AgendaPlanRepository,
    private val memoryDocumentRepository: MemoryDocumentRepository,
    private val clock: MyStatusClock = MyStatusClock(System::currentTimeMillis),
) {
    private val started = AtomicBoolean(false)
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow(
        MyStatusUiState(
            snapshot = snapshotStore.load(),
            locationPermissionRequired = contextAssembler.locationPermissionRequired(),
        )
    )
    val state = _state.asStateFlow()

    private var lastFingerprint: String? = null
    private var lastAttemptAtEpochMillis: Long? = null
    private var lastSuccessfulAtEpochMillis: Long? = _state.value.snapshot?.generatedAtEpochMillis
    private var lastContextPolicyKey: String? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        requestRefresh(MyStatusRefreshTrigger.APP_START, force = true)

        appScope.launch {
            while (isActive) {
                delay(MY_STATUS_VALIDITY_MS)
                refresh(MyStatusRefreshTrigger.HOURLY)
            }
        }
        appScope.launch {
            watchProbe.state
                .map { it.health }
                .distinctUntilChanged()
                .drop(1)
                .debounce(SOURCE_CHANGE_DEBOUNCE_MS)
                .collect {
                    refresh(MyStatusRefreshTrigger.BODY_CHANGED)
                }
        }
        appScope.launch {
            agendaTaskRepository.observeVisibleTasks()
                .map { tasks -> tasks.map { "${it.id}:${it.status}:${it.dueAt}:${it.updatedAt}" } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(SOURCE_CHANGE_DEBOUNCE_MS)
                .collect {
                    refresh(MyStatusRefreshTrigger.AGENDA_CHANGED)
                }
        }
        appScope.launch {
            agendaPlanRepository.observeVisiblePlans()
                .map { plans ->
                    plans.map { item ->
                        buildString {
                            append(item.plan.id)
                            append(':')
                            append(item.plan.status)
                            append(':')
                            append(item.plan.eventAt)
                            append(':')
                            append(item.plan.updatedAt)
                            append(':')
                            append(
                                item.stages.joinToString(",") { stage ->
                                    "${stage.id}:${stage.status}:${stage.triggerAt}:${stage.dueAt}:${stage.updatedAt}"
                                }
                            )
                        }
                    }
                }
                .distinctUntilChanged()
                .drop(1)
                .debounce(SOURCE_CHANGE_DEBOUNCE_MS)
                .collect {
                    refresh(MyStatusRefreshTrigger.AGENDA_CHANGED)
                }
        }
        appScope.launch {
            settingsStore.settingsFlowRaw
                .map { settings ->
                    val assistant = settings.getCurrentAssistant()
                    listOf(
                        settings.allowAiHealthData,
                        assistant.id,
                        assistant.enableMemory,
                        assistant.useGlobalMemory,
                    ).joinToString(":")
                }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    refresh(MyStatusRefreshTrigger.SETTINGS_CHANGED, force = true)
                }
        }
        appScope.launch {
            memoryDocumentRepository.observeDocuments(MemoryDocumentRepository.GLOBAL_SCOPE_ID)
                .map { documents -> documents.map { "${it.path}:${it.version}:${it.updatedAt}:${it.content}" } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(SOURCE_CHANGE_DEBOUNCE_MS)
                .collect {
                    refresh(MyStatusRefreshTrigger.PROFILE_CHANGED)
                }
        }
    }

    fun onVisible() {
        start()
        requestRefresh(MyStatusRefreshTrigger.USER_VISIBLE)
    }

    fun refreshNow() {
        start()
        requestRefresh(MyStatusRefreshTrigger.USER_VISIBLE, force = true)
    }

    fun onLocationPermissionResult() {
        requestRefresh(MyStatusRefreshTrigger.LOCATION_PERMISSION, force = true)
    }

    private fun requestRefresh(trigger: MyStatusRefreshTrigger, force: Boolean = false) {
        appScope.launch(Dispatchers.Default) {
            refresh(trigger, force)
        }
    }

    private suspend fun refresh(
        trigger: MyStatusRefreshTrigger,
        force: Boolean = false,
    ) = refreshMutex.withLock {
        val settings = settingsStore.settingsFlowRaw.first()
        val facts = contextAssembler.collect()
        val now = clock.nowEpochMillis()
        val assistant = settings.getCurrentAssistant()
        val allowPersonalContext = assistant.enableMemory
        val personalContext = if (allowPersonalContext) {
            buildMyStatusDocumentContext(
                memoryDocumentRepository.getPromptDocuments(MemoryDocumentRepository.GLOBAL_SCOPE_ID)
            )
        } else {
            emptyList()
        }
        val interventionPolicy = buildMyStatusInterventionPolicy(facts)
        val fingerprint = significantStatusFingerprint(
            facts = facts,
            allowBodyInAiContext = settings.allowAiHealthData,
            personalContext = personalContext,
        )
        val previousFingerprint = lastFingerprint
        if (
            !shouldRefreshMyStatus(
                previousFingerprint = previousFingerprint,
                currentFingerprint = fingerprint,
                lastAttemptAtEpochMillis = lastAttemptAtEpochMillis,
                lastSuccessfulAtEpochMillis = lastSuccessfulAtEpochMillis,
                nowEpochMillis = now,
                force = force,
            )
        ) {
            _state.value = _state.value.copy(
                locationPermissionRequired = contextAssembler.locationPermissionRequired(),
            )
            return@withLock
        }

        val contextPolicyKey =
            "${settings.allowAiHealthData}:$allowPersonalContext:${assistant.id}"
        val contextPolicyChanged = lastContextPolicyKey != null &&
            lastContextPolicyKey != contextPolicyKey
        val factsChanged = previousFingerprint != null && previousFingerprint != fingerprint
        lastContextPolicyKey = contextPolicyKey
        lastFingerprint = fingerprint
        lastAttemptAtEpochMillis = now
        _state.value = _state.value.copy(
            refreshing = true,
            locationPermissionRequired = contextAssembler.locationPermissionRequired(),
            statusMessage = null,
        )

        val modelInput = buildMyStatusModelInput(
            facts = facts,
            allowBodyInAiContext = settings.allowAiHealthData,
            personalContext = personalContext,
            interventionPolicy = interventionPolicy,
        )
        val modelPolicy = modelInput.interventionPolicy
        val generated = if (modelPolicy.shouldGenerateInterpretation) {
            textGenerator.generate(encodeMyStatusModelInput(modelInput))
                ?.let { raw ->
                    parseGeneratedMyStatus(
                        raw = raw,
                        availableEvidence = modelInput.evidence,
                        nowEpochMillis = now,
                        locationArea = facts.location?.area,
                    )
                }
                ?.let { snapshot ->
                    enforceMyStatusInterventionPolicy(snapshot, modelPolicy)
                }
        } else {
            null
        }
        val previous = _state.value.snapshot
        val preservePrevious = generated == null &&
            modelPolicy.shouldGenerateInterpretation &&
            !contextPolicyChanged &&
            !factsChanged &&
            previous != null &&
            previous.validUntilEpochMillis > now
        val resolved = when {
            generated != null -> generated
            preservePrevious -> requireNotNull(previous)
            else -> buildLocalMyStatusFallback(facts, now)
        }
        val stabilized = if (preservePrevious) resolved else stabilizeMyStatusSnapshot(previous, resolved)
        val next = stabilized.copy(
            discussionEvidenceIds = modelInput.evidence
                .map(MyStatusEvidence::id)
                .distinct(),
            contextMemoryIds = personalContext
                .map(MyStatusPersonalContext::memoryId)
                .filter { it > 0 }
                .distinct(),
        )
        if (!preservePrevious) {
            snapshotStore.save(next)
            lastSuccessfulAtEpochMillis = now
        }
        _state.value = MyStatusUiState(
            snapshot = next,
            refreshing = false,
            locationPermissionRequired = contextAssembler.locationPermissionRequired(),
            statusMessage = when {
                facts.weatherUnavailable -> "天气暂不可用，其他状态已更新"
                preservePrevious -> "快速模型暂不可用，显示上次状态"
                !modelPolicy.shouldGenerateInterpretation -> null
                generated == null -> "快速模型未就绪，使用本地判断"
                trigger == MyStatusRefreshTrigger.LOCATION_PERMISSION && facts.location == null ->
                    "暂时无法取得位置"
                else -> null
            },
        )
    }

    private companion object {
        const val SOURCE_CHANGE_DEBOUNCE_MS = 5_000L
    }
}
