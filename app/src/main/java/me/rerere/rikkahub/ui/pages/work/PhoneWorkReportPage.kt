package me.rerere.rikkahub.ui.pages.work

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.context.LocalToaster
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PhoneWorkReportPage(contentId: String, title: String) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val html = remember(contentId) { WebViewContentCache.load(context.cacheDir, contentId).orEmpty() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            runCatching {
                                val file = File(context.cacheDir, "work-report-$contentId.html")
                                file.writeText(html)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/html"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TITLE, title)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, title))
                            }.onFailure { toaster.show(message = "分享失败：${it.message}", type = ToastType.Error) }
                        },
                    ) {
                        Icon(HugeIcons.Share08, "分享报告")
                    }
                },
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
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url ?: return true
                            if (url.scheme == "http" || url.scheme == "https") {
                                runCatching { view?.context?.startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri())) }
                            }
                            return true
                        }
                    }
                    loadDataWithBaseURL("https://work-report.invalid/", html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
