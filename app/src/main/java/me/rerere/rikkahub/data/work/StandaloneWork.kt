package me.rerere.rikkahub.data.work

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.ui.context.LocalNavController

fun openStandaloneWork(context: Context, sessionId: String = "") {
    val peer = context.packageName.replace("dev.sundby.zhixing", "dev.sundby.zhixing.work")
    val intent = context.packageManager.getLaunchIntentForPackage(peer)
    if (intent == null) {
        Toast.makeText(context, "请先安装同一渠道的知行 Work App", Toast.LENGTH_LONG).show()
        return
    }
    context.startActivity(intent.putExtra("workSessionId", sessionId))
}

@Composable
fun StandaloneWorkRedirect(sessionId: String = "") {
    val context = LocalContext.current
    val navigator = LocalNavController.current
    LaunchedEffect(sessionId) {
        openStandaloneWork(context, sessionId)
        navigator.popBackStack()
    }
}
