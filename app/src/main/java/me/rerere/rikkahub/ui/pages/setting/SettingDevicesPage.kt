package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbe
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbeStage
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbeState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingDevicesPage() {
    val watchProbe: LenovoWatchProbe = koinInject()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val state by watchProbe.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissions = remember { requiredWatchPermissions() }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasWatchPermissions(context, permissions)) watchProbe.start()
    }

    fun connect() {
        if (hasWatchPermissions(context, permissions)) {
            watchProbe.start()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("我的设备") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("device-behavior") {
                Text(
                    text = "已记住的设备会在知行运行期间自动恢复连接；连接保持时约每 30 分钟同步一次。",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item("supported-devices") {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "已支持设备",
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LenovoWatchDeviceCard(
                        state = state,
                        onConnect = ::connect,
                        onDisconnect = watchProbe::disconnect,
                        onSync = watchProbe::sync,
                    )
                }
            }

            item("device-diagnostics") {
                DeviceDiagnostics(
                    state = state,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LenovoWatchDeviceCard(
    state: LenovoWatchProbeState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
) {
    val active = state.stage !in setOf(LenovoWatchProbeStage.IDLE, LenovoWatchProbeStage.ERROR)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(HugeIcons.SmartPhone01, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lenovo Watch Pro",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = connectionLabel(state.stage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.stage in CONNECTING_STAGES) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }

            Text(
                text = state.error ?: state.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = formatLastSync(state.lastSuccessfulSyncAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.stage == LenovoWatchProbeStage.SYNCING) {
                Text(
                    text = "已处理 ${state.processedRecords} 条记录，完成后刷新健康数据",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (active) onDisconnect else onConnect) {
                    Text(
                        when {
                            active -> "暂时断开"
                            state.stage == LenovoWatchProbeStage.ERROR -> "重试连接"
                            state.remembered -> "重新连接"
                            else -> "连接设备"
                        },
                    )
                }
                if (state.canSync) {
                    OutlinedButton(onClick = onSync) {
                        Text("立即同步")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceDiagnostics(
    state: LenovoWatchProbeState,
    modifier: Modifier = Modifier,
) {
    CardGroup(
        modifier = modifier,
        title = { Text("诊断") },
    ) {
        item(
            headlineContent = { Text("连接状态") },
            supportingContent = { Text(state.statusText) },
        )
        item(
            headlineContent = { Text("设备地址") },
            supportingContent = { Text(state.address ?: "尚未发现") },
        )
        item(
            headlineContent = { Text("协议记录") },
            supportingContent = {
                Text(
                    text = state.lastEvent ?: "尚无协议事件",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { Text("${state.receivedFrames} 帧") },
        )
        state.lastFrameHex?.let { frame ->
            item(
                headlineContent = { Text("最近原始帧") },
                supportingContent = {
                    Text(
                        text = frame,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
        state.error?.let { error ->
            item(
                headlineContent = {
                    Text(
                        text = "最近错误",
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                supportingContent = {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

private fun connectionLabel(stage: LenovoWatchProbeStage): String = when (stage) {
    LenovoWatchProbeStage.READY -> "已连接"
    LenovoWatchProbeStage.SYNCING -> "已连接 · 正在同步"
    LenovoWatchProbeStage.SCANNING,
    LenovoWatchProbeStage.CONNECTING,
    LenovoWatchProbeStage.DISCOVERING,
    LenovoWatchProbeStage.ENABLING_NOTIFICATIONS,
    LenovoWatchProbeStage.HANDSHAKING,
    -> "正在连接"
    LenovoWatchProbeStage.ERROR -> "连接需要处理"
    LenovoWatchProbeStage.IDLE -> "未连接"
}

private fun formatLastSync(value: Instant?): String = value?.let {
    "上次同步：${DEVICE_TIME_FORMATTER.format(it.atZone(ZoneId.systemDefault()))}"
} ?: "上次同步：尚无"

private fun requiredWatchPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun hasWatchPermissions(context: Context, permissions: Array<String>): Boolean = permissions.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private val CONNECTING_STAGES = setOf(
    LenovoWatchProbeStage.SCANNING,
    LenovoWatchProbeStage.CONNECTING,
    LenovoWatchProbeStage.DISCOVERING,
    LenovoWatchProbeStage.ENABLING_NOTIFICATIONS,
    LenovoWatchProbeStage.HANDSHAKING,
    LenovoWatchProbeStage.SYNCING,
)
private val DEVICE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
