package me.rerere.rikkahub.work

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.work.PhoneWorkCredentialStore
import me.rerere.rikkahub.service.PhoneWorkTrackingService
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import me.rerere.rikkahub.ui.pages.work.*
import me.rerere.rikkahub.ui.pages.setting.SettingSpeechPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingThemePage
import me.rerere.rikkahub.ui.pages.webview.WebViewPage
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.koin.android.ext.android.inject

class WorkActivity : ComponentActivity() {
    private val settingsStore: SettingsStore by inject()
    private val credentials: PhoneWorkCredentialStore by inject()
    private val highlighter: Highlighter by inject()
    private var stack: MutableList<NavKey>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context).components {
                    add(OkHttpNetworkFetcherFactory())
                    add(SvgDecoder.Factory())
                }.build()
            }
            RikkahubTheme { WorkRoutes() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (credentials.connection.value.configured) PhoneWorkTrackingService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedSession()
    }

    private fun openRequestedSession() {
        if (!intent.hasExtra("workSessionId")) return
        val navigation = stack ?: return
        val id = intent.getStringExtra("workSessionId").orEmpty()
        navigation.clear()
        navigation.add(Screen.PhoneWorkHome)
        if (id.isNotBlank()) {
            navigation.add(if (credentials.connection.value.configured) Screen.PhoneWorkSession(id) else Screen.Setting)
        }
        intent.removeExtra("workSessionId")
    }

    @Composable
    private fun WorkRoutes() {
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val backStack = rememberNavBackStack(Screen.PhoneWorkHome)
        SideEffect { stack = backStack }
        LaunchedEffect(Unit) { openRequestedSession() }
        val toaster = rememberToasterState()
        val asr = rememberCustomAsrState()
        val tts = rememberCustomTtsState()
        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalSettings provides settings,
                LocalNavController provides Navigator(backStack),
                LocalSharedTransitionScope provides this,
                LocalHighlighter provides highlighter,
                LocalASRState provides asr,
                LocalTTSState provides tts,
                LocalToaster provides toaster,
            ) {
                NavDisplay(
                    backStack = backStack,
                    onBack = { if (backStack.size > 1) backStack.removeLastOrNull() else finish() },
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                    entryProvider = entryProvider {
                        entry<Screen.PhoneWorkHome> { PhoneWorkHomePage() }
                        entry<Screen.PhoneWorkSession> { PhoneWorkSessionPage(it.id) }
                        entry<Screen.PhoneWorkReport> { PhoneWorkReportPage(it.contentId, it.title) }
                        entry<Screen.Setting> { WorkSettingsPage() }
                        entry<Screen.SettingSpeech> { SettingSpeechPage() }
                        entry<Screen.SettingPreferencesTheme> { SettingPreferencesThemePage() }
                        entry<Screen.SettingTheme> { SettingThemePage() }
                        entry<Screen.WebView> { WebViewPage(it.url, it.contentId) }
                    },
                )
                Toaster(state = toaster)
            }
        }
    }
}
