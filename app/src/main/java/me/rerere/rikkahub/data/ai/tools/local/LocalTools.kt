package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.data.repository.MonthlyLedgerRepository
import me.rerere.rikkahub.data.work.PhoneWorkApiClient
import me.rerere.rikkahub.data.work.PhoneWorkCredentialStore
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val agendaTaskRepository: AgendaTaskRepository,
    private val agendaPlanRepository: AgendaPlanRepository,
    private val monthlyLedgerRepository: MonthlyLedgerRepository,
    private val phoneWorkApiClient: PhoneWorkApiClient,
    private val phoneWorkCredentialStore: PhoneWorkCredentialStore,
    private val locationTravelGateway: LocationTravelGateway,
    private val navigationLauncher: NavigationLauncher,
    private val isLocationTravelConfigured: () -> Boolean,
    private val hasLocationTravelPrivacyConsent: () -> Boolean,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val agendaTaskTools by lazy { buildAgendaTaskTools(agendaTaskRepository) }

    val agendaPlanTools by lazy { buildAgendaPlanTools(agendaPlanRepository) }

    val monthlySpendingSummaryTool by lazy {
        buildMonthlySpendingSummaryTool(monthlyLedgerRepository)
    }

    val inboxMonitorTool by lazy {
        buildInboxMonitorTool(phoneWorkApiClient, phoneWorkCredentialStore)
    }

    val locationTravelTools by lazy {
        buildLocationTravelTools(
            gateway = locationTravelGateway,
            navigationLauncher = navigationLauncher,
            isConfigured = isLocationTravelConfigured,
            hasPrivacyConsent = hasLocationTravelPrivacyConsent,
        )
    }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = agendaTaskTools.toMutableList()
        tools.addAll(agendaPlanTools)
        tools.add(monthlySpendingSummaryTool)
        if (phoneWorkCredentialStore.connection.value.configured) {
            tools.add(inboxMonitorTool)
        }
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.LocationTravel)) {
            tools.addAll(locationTravelTools)
        }
        return tools
    }
}
