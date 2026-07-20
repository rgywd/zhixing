package me.rerere.rikkahub.ui.pages.work

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import androidx.compose.ui.platform.LocalContext

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PhoneWorkReportPage(contentId: String, title: String) {
    val context = LocalContext.current
    val html = remember(contentId) { WebViewContentCache.load(context.cacheDir, contentId).orEmpty() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                    }
                    loadDataWithBaseURL("https://work-report.invalid/", html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
