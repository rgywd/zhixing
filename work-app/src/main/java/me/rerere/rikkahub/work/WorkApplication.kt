package me.rerere.rikkahub.work

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import com.whl.quickjs.android.QuickJSLoader
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.work.*
import me.rerere.rikkahub.ui.pages.work.PhoneWorkHomeVM
import me.rerere.rikkahub.ui.pages.work.PhoneWorkSessionVM
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.tts.provider.TTSManager
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

class WorkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        QuickJSLoader.init()
        startKoin {
            androidContext(this@WorkApplication)
            modules(module {
                single { AppScope() }
                single<Json> { JsonInstant }
                single { SettingsStore(get(), get()) }
                single { Room.databaseBuilder(get(), WorkDatabase::class.java, "work.db").build() }
                single { get<WorkDatabase>().sessions() }
                single { get<WorkDatabase>().files() }
                single { FilesManager(get(), get(), get()) }
                single { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES).build() }
                single { PhoneWorkCredentialStore(get()) }
                single { PhoneWorkCatalogStore(get()) }
                single { PhoneWorkDraftStore(get()) }
                single { PhoneWorkRepoPreferenceStore(get()) }
                single { PhoneWorkApiClient(get(), get()) }
                single { PhoneWorkRepository(get(), get(), get(), get(), get()) }
                single { me.rerere.ai.provider.ProviderManager(get(), get()) }
                single<PhoneWorkTitleGenerator> { WorkTitleGenerator(get(), get()) }
                single { PhoneWorkSessionCreator(get<PhoneWorkRepository>(), get()) }
                single { Highlighter(get()) }
                single { SoundEffectPlayer(get()) }
                single { AppEventBus() }
                single { TTSManager(get()) }
                viewModelOf(::PhoneWorkHomeVM)
                viewModelOf(::SettingVM)
                viewModel { params -> PhoneWorkSessionVM(params.get(), get(), get(), get(), get()) }
            })
        }
        val notifications = getSystemService(NotificationManager::class.java)
        listOf(
            Triple(WORK_TRACKING_NOTIFICATION_CHANNEL_ID, "Work 任务状态", NotificationManager.IMPORTANCE_LOW),
            Triple(WORK_ALERT_NOTIFICATION_CHANNEL_ID, "Work 关键进展", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(WORK_ASK_NOTIFICATION_CHANNEL_ID, "Work 等待回答", NotificationManager.IMPORTANCE_HIGH),
        ).forEach { (id, name, importance) -> notifications.createNotificationChannel(NotificationChannel(id, name, importance)) }
    }
}
