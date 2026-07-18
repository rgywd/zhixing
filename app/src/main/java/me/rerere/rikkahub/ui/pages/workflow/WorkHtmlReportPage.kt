package me.rerere.rikkahub.ui.pages.workflow

import android.webkit.WebSettings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkHtmlReportPage(title: String, contentId: String) {
    val context = LocalContext.current
    val content = remember(contentId) { WebViewContentCache.load(context.cacheDir, contentId).orEmpty() }
    val state = rememberWebViewState(
        data = content,
        baseUrl = "https://zhixing.local",
        mimeType = "text/html",
        settings = {
            javaScriptEnabled = false
            domStorageEnabled = false
            allowContentAccess = false
            allowFileAccess = false
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        },
    )
    state.javaScriptEnabled = false

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(title, maxLines = 1) },
            )
        },
    ) { padding ->
        WebView(state = state, modifier = Modifier.fillMaxSize().padding(padding))
    }
}
