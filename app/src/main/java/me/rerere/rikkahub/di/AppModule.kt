package me.rerere.rikkahub.di

import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.tools.local.LocationTravelGateway
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.NavigationLauncher
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchConnectionManager
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbe
import me.rerere.rikkahub.data.profile.LegacyProfileMaintenanceCleanup
import me.rerere.rikkahub.data.agenda.AgendaReminderWorker
import me.rerere.rikkahub.data.agenda.AgendaPlanStageReminderWorker
import me.rerere.rikkahub.data.status.AndroidCoarseLocationProvider
import me.rerere.rikkahub.data.status.CachingWeatherProvider
import me.rerere.rikkahub.data.status.FastModelStatusGenerator
import me.rerere.rikkahub.data.status.LenovoWatchBodyStatusSource
import me.rerere.rikkahub.data.status.LocalAgendaStatusSource
import me.rerere.rikkahub.data.status.MyStatusAgendaSource
import me.rerere.rikkahub.data.status.MyStatusBodySource
import me.rerere.rikkahub.data.status.MyStatusClock
import me.rerere.rikkahub.data.status.MyStatusContextAssembler
import me.rerere.rikkahub.data.status.MyStatusCoordinator
import me.rerere.rikkahub.data.status.MyStatusLocationProvider
import me.rerere.rikkahub.data.status.MyStatusSnapshotStore
import me.rerere.rikkahub.data.status.MyStatusTextGenerator
import me.rerere.rikkahub.data.status.OpenMeteoWeatherProvider
import me.rerere.rikkahub.data.status.WeatherProvider
import me.rerere.rikkahub.data.today.TodayOverviewProvider
import me.rerere.rikkahub.data.work.PhoneWorkSessionCreator
import me.rerere.rikkahub.data.work.PhoneWorkSessionGateway
import me.rerere.rikkahub.data.work.PhoneWorkTitleGenerator
import me.rerere.rikkahub.data.github.GitHubCliRunner
import me.rerere.rikkahub.data.github.GitHubIssueCredentialStore
import me.rerere.rikkahub.data.github.GitHubIssueTokenProvider
import me.rerere.rikkahub.data.location.AmapLocationTravelGateway
import me.rerere.rikkahub.data.location.AndroidAmapNavigationLauncher
import me.rerere.rikkahub.service.ChatGenerationForegroundController
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.telemetry.AppTelemetry
import me.rerere.rikkahub.telemetry.NoOpAppTelemetry
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module
import org.koin.androidx.workmanager.dsl.workerOf

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        AppEventBus()
    }

    single { LenovoWatchProbe(get()) }
    single { LenovoWatchConnectionManager(get(), get()) }
    single<MyStatusClock> { MyStatusClock(System::currentTimeMillis) }
    single<MyStatusLocationProvider> { AndroidCoarseLocationProvider(get(), get()) }
    single<WeatherProvider> {
        CachingWeatherProvider(
            delegate = OpenMeteoWeatherProvider(clock = get()),
            clock = get(),
        )
    }
    single<MyStatusBodySource> { LenovoWatchBodyStatusSource(get(), get()) }
    single<MyStatusAgendaSource> { LocalAgendaStatusSource(get(), get(), get()) }
    single { MyStatusContextAssembler(get(), get(), get(), get(), get()) }
    single<MyStatusTextGenerator> { FastModelStatusGenerator(get(), get()) }
    single { MyStatusSnapshotStore(get()) }
    single(createdAtStart = true) {
        MyStatusCoordinator(
            appScope = get(),
            settingsStore = get(),
            contextAssembler = get(),
            textGenerator = get(),
            snapshotStore = get(),
            watchProbe = get(),
            agendaTaskRepository = get(),
            agendaPlanRepository = get(),
            memoryDocumentRepository = get(),
            clock = get(),
        ).also(MyStatusCoordinator::start)
    }

    single { TodayOverviewProvider(get(), get(), get(), get(), get(), get()) }

    single<LocationTravelGateway> {
        val settingsStore = get<SettingsStore>()
        AmapLocationTravelGateway(get()) {
            settingsStore.settingsFlow.value.locationTravelPrivacyConsent
        }
    }
    single<NavigationLauncher> { AndroidAmapNavigationLauncher(get()) }

    single {
        val settingsStore = get<SettingsStore>()
        LocalTools(
            context = get(),
            eventBus = get(),
            ttsManager = get(),
            settingsStore = settingsStore,
            agendaTaskRepository = get(),
            agendaPlanRepository = get(),
            monthlyLedgerRepository = get(),
            phoneWorkApiClient = get(),
            phoneWorkCredentialStore = get(),
            locationTravelGateway = get(),
            navigationLauncher = get(),
            isLocationTravelConfigured = { BuildConfig.AMAP_API_KEY_CONFIGURED },
            hasLocationTravelPrivacyConsent = {
                settingsStore.settingsFlow.value.locationTravelPrivacyConsent
            },
        )
    }

    single { GitHubIssueCredentialStore(get()) }
    single<GitHubIssueTokenProvider> { get<GitHubIssueCredentialStore>() }
    single { GitHubCliRunner(get(), get()) }

    single {
        UpdateChecker(get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single<AppTelemetry> { NoOpAppTelemetry }

    single {
        SoundEffectPlayer(get())
    }

    single { LegacyProfileMaintenanceCleanup(get()) }
    workerOf(::AgendaReminderWorker)
    workerOf(::AgendaPlanStageReminderWorker)

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single { ChatGenerationForegroundController(get()) }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            generationForegroundController = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryDocumentRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            githubCliRunner = get(),
            workspaceVariableStore = get(),
            knowledgeSpaceService = get(),
            folderRepository = get(),
            assistantTaskRepository = get(),
            runtimeContextStore = get(),
        )
    }

    single<PhoneWorkSessionGateway> { get<me.rerere.rikkahub.data.work.PhoneWorkRepository>() }
    single<PhoneWorkTitleGenerator> {
        val chatService = get<ChatService>()
        PhoneWorkTitleGenerator(chatService::generateWorkTitle)
    }
    single { PhoneWorkSessionCreator(get(), get()) }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
