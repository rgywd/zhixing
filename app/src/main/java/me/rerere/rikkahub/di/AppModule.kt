package me.rerere.rikkahub.di

import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.github.GitHubIssueClient
import me.rerere.rikkahub.data.github.GitHubIssueCredentialStore
import me.rerere.rikkahub.data.github.GitHubIssueTokenProvider
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.WorkNotificationManager
import me.rerere.rikkahub.service.WorkSyncWorker
import org.koin.androidx.workmanager.dsl.workerOf
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.telemetry.AppTelemetry
import me.rerere.rikkahub.telemetry.NoOpAppTelemetry
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyAuthApi
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyCredentialsStore
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyProtocol
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySyncApi
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySocketClient
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get(), get(), get())
    }

    single { GitHubIssueCredentialStore(get()) }
    single<GitHubIssueTokenProvider> { get<GitHubIssueCredentialStore>() }
    single { GitHubIssueClient() }

    single { HappyCredentialsStore(get(), get()) }
    single {
        HappyAuthApi(
            client = get(),
            json = get(),
            clientId = HappyProtocol.clientId(BuildConfig.VERSION_NAME),
        )
    }
    single {
        HappySyncApi(
            client = get(),
            json = get(),
            clientId = HappyProtocol.clientId(BuildConfig.VERSION_NAME),
        )
    }
    single {
        HappySocketClient(
            json = get(),
            clientId = HappyProtocol.clientId(BuildConfig.VERSION_NAME),
        )
    }

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

    // 远程任务关键通知：进程存活期走 Socket 增量，周期 Worker 兜底
    single(createdAtStart = true) {
        WorkNotificationManager(
            context = get(),
            appScope = get(),
            repository = get(),
        )
    }
    workerOf(::WorkSyncWorker)

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            knowledgeSpaceService = get(),
            folderRepository = get()
        )
    }

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
