package me.rerere.rikkahub.data.device.lenovo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.util.ArrayDeque
import java.util.UUID

internal enum class LenovoWatchProbeStage {
    IDLE,
    SCANNING,
    CONNECTING,
    DISCOVERING,
    ENABLING_NOTIFICATIONS,
    HANDSHAKING,
    READY,
    SYNCING,
    ERROR,
}

internal data class LenovoWatchHealthSnapshot(
    val steps: Int? = null,
    val calories: Int? = null,
    val shallowSleepMinutes: Int? = null,
    val deepSleepMinutes: Int? = null,
    val awakeCount: Int? = null,
    val exerciseSeconds: Int? = null,
    val exerciseCount: Int? = null,
    val heartRate: Int? = null,
    val bloodOxygen: Int? = null,
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val temperatureCelsius: Double? = null,
    val immunity: Int? = null,
)

internal data class LenovoWatchProbeState(
    val stage: LenovoWatchProbeStage = LenovoWatchProbeStage.IDLE,
    val address: String? = null,
    val deviceName: String? = null,
    val statusText: String = "尚未连接",
    val error: String? = null,
    val receivedFrames: Int = 0,
    val lastEvent: String? = null,
    val lastFrameHex: String? = null,
    val health: LenovoWatchHealthSnapshot = LenovoWatchHealthSnapshot(),
) {
    val isConnected: Boolean
        get() = stage in setOf(
            LenovoWatchProbeStage.DISCOVERING,
            LenovoWatchProbeStage.ENABLING_NOTIFICATIONS,
            LenovoWatchProbeStage.HANDSHAKING,
            LenovoWatchProbeStage.READY,
            LenovoWatchProbeStage.SYNCING,
        )

    val canSync: Boolean get() = stage == LenovoWatchProbeStage.READY
}

/**
 * Explicit, foreground-only BLE probe. It never scans automatically and does not expose any
 * disconnect/unbind protocol command; [disconnect] only closes Android's local GATT connection.
 */
internal class LenovoWatchProbe(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val packetAssembler = LenovoWatchPacketAssembler()
    private val writeQueue = ArrayDeque<ByteArray>()
    private val _state = MutableStateFlow(LenovoWatchProbeState())
    val state: StateFlow<LenovoWatchProbeState> = _state.asStateFlow()

    private val bluetoothManager: BluetoothManager?
        get() = context.getSystemService(BluetoothManager::class.java)

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var writeInFlight = false
    private var requestedUid = 0L
    private var requestedConnectionKind = LenovoWatchProtocol.ConnectionKind.NEW

    private val scanTimeout = Runnable {
        stopScan()
        fail("未在 12 秒内发现 Lenovo Watch Pro；请点亮手表并暂时关闭官方 App")
    }

    private val syncQuietTimeout = Runnable {
        if (_state.value.stage == LenovoWatchProbeStage.SYNCING) {
            _state.update { it.copy(stage = LenovoWatchProbeStage.READY, statusText = "同步完成（3 秒无新数据）") }
        }
    }

    private val handshakeTimeout = Runnable {
        if (_state.value.stage == LenovoWatchProbeStage.HANDSHAKING) {
            failAndDisconnect("手表握手 20 秒无响应；请确认官方 App 已暂时关闭")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = runCatching { result.device.address }.getOrNull() ?: return
            if (!address.equals(TARGET_ADDRESS, ignoreCase = true)) return
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            fail("蓝牙扫描失败：$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndDisconnect("GATT 连接失败：$status", gatt)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.update { it.copy(stage = LenovoWatchProbeStage.DISCOVERING, statusText = "已连接，正在发现服务") }
                    val started = runCatching { gatt.discoverServices() }
                        .getOrElse {
                            failAndDisconnect("无法开始发现 GATT 服务：${it.message}", gatt)
                            false
                        }
                    if (!started && _state.value.stage != LenovoWatchProbeStage.ERROR) {
                        failAndDisconnect("系统拒绝开始发现 GATT 服务", gatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGatt(gatt)
                    _state.update {
                        if (it.stage == LenovoWatchProbeStage.ERROR) it else LenovoWatchProbeState(statusText = "连接已断开")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndDisconnect("GATT 服务发现失败：$status", gatt)
                return
            }
            val service = gatt.getService(LenovoWatchProtocol.mainServiceUuid)
            val write = service?.getCharacteristic(LenovoWatchProtocol.writeCharacteristicUuid)
            val notify = service?.getCharacteristic(LenovoWatchProtocol.notifyCharacteristicUuid)
            if (service == null || write == null || notify == null) {
                val discovered = gatt.services.joinToString { it.uuid.toString() }
                failAndDisconnect("未发现官方主通道 6E400001；设备服务：$discovered", gatt)
                return
            }
            writeCharacteristic = write
            _state.update { it.copy(stage = LenovoWatchProbeStage.ENABLING_NOTIFICATIONS, statusText = "正在开启手表通知") }
            if (!enableNotifications(gatt, notify)) failAndDisconnect("无法开启手表通知", gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIGURATION) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndDisconnect("通知描述符写入失败：$status", gatt)
                return
            }
            _state.update { it.copy(stage = LenovoWatchProbeStage.HANDSHAKING, statusText = "通知已开启，等待手表能力信息") }
            mainHandler.removeCallbacks(handshakeTimeout)
            mainHandler.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS)
            enqueue(LenovoWatchProtocol.phoneSystem())
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let(::handleNotification)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                writeQueue.clear()
                failAndDisconnect("手表命令写入失败：$status", gatt)
                return
            }
            mainHandler.postDelayed(::drainWriteQueue, WRITE_GAP_MS)
        }
    }

    fun start(
        uid: Long = 0,
        connectionKind: LenovoWatchProtocol.ConnectionKind = LenovoWatchProtocol.ConnectionKind.NEW,
    ) {
        if (!hasBluetoothPermissions()) {
            fail("缺少附近设备权限")
            return
        }
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            fail("此设备不支持蓝牙")
            return
        }
        if (!adapter.isEnabled) {
            fail("请先打开蓝牙")
            return
        }

        disconnect(resetState = false)
        requestedUid = uid
        requestedConnectionKind = connectionKind
        _state.value = LenovoWatchProbeState(
            stage = LenovoWatchProbeStage.SCANNING,
            statusText = "正在扫描 Lenovo Watch Pro",
        )
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            fail("蓝牙扫描器不可用")
            return
        }
        try {
            scanner.startScan(scanCallback)
            mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (error: SecurityException) {
            fail("附近设备权限不可用：${error.message}")
        }
    }

    fun sync() {
        if (_state.value.stage != LenovoWatchProbeStage.READY) return
        _state.update { it.copy(stage = LenovoWatchProbeStage.SYNCING, statusText = "正在同步健康数据", error = null) }
        enqueue(LenovoWatchProtocol.healthSync(since = null))
        enqueue(LenovoWatchProtocol.gpsSync())
        enqueue(LenovoWatchProtocol.sleepSync(LocalDate.now().minusDays(7)))
        scheduleSyncQuietTimeout()
    }

    fun disconnect() = disconnect(resetState = true)

    private fun disconnect(resetState: Boolean) {
        stopScan()
        mainHandler.removeCallbacks(syncQuietTimeout)
        mainHandler.removeCallbacks(handshakeTimeout)
        writeQueue.clear()
        writeInFlight = false
        writeCharacteristic = null
        packetAssembler.reset()
        gatt?.let { current ->
            runCatching { current.disconnect() }
            closeGatt(current)
        }
        gatt = null
        if (resetState) _state.value = LenovoWatchProbeState()
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        _state.update {
            it.copy(
                stage = LenovoWatchProbeStage.CONNECTING,
                address = runCatching { device.address }.getOrNull(),
                deviceName = runCatching { device.name }.getOrNull(),
                statusText = "已发现手表，正在连接",
            )
        }
        try {
            val connection = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (connection == null) {
                fail("系统未创建 GATT 连接")
                return
            }
            gatt = connection
        } catch (error: SecurityException) {
            fail("连接权限不可用：${error.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleNotification(chunk: ByteArray) {
        when (val result = packetAssembler.accept(chunk)) {
            LenovoWatchPacketAssembly.Incomplete -> Unit
            is LenovoWatchPacketAssembly.Rejected -> failAndDisconnect("通知分包错误：${result.reason}")
            is LenovoWatchPacketAssembly.Complete -> handleFrame(result.frame)
        }
    }

    private fun handleFrame(frame: ByteArray) {
        val event = LenovoWatchHealthParser.parse(frame)
        _state.update {
            it.copy(
                receivedFrames = it.receivedFrames + 1,
                lastFrameHex = frame.toHex(),
                lastEvent = event.summary(),
            )
        }

        if (frame.size >= 5 && frame[0].toInt() and 0xFF == 0xEA && frame[4].toInt() and 0xFF == 0x0B) {
            enqueue(LenovoWatchProtocol.heartbeatReply())
        }

        when (event) {
            is LenovoWatchEvent.PairingCapabilities -> {
                _state.update { it.copy(stage = LenovoWatchProbeStage.HANDSHAKING, statusText = "已收到能力信息，正在安全握手") }
                enqueue(
                    LenovoWatchProtocol.connectionRequest(
                        phoneModel = Build.MODEL ?: "Android",
                        uid = requestedUid,
                        kind = requestedConnectionKind,
                    ),
                )
            }

            is LenovoWatchEvent.ConnectionResult -> handleConnectionResult(event.state)
            is LenovoWatchEvent.CurrentActivity -> updateHealth(event)
            is LenovoWatchEvent.HourlyVitals -> updateHealth(event)
            is LenovoWatchEvent.HourlyRecovery -> updateHealth(event)
            is LenovoWatchEvent.Measurement -> updateHealth(event)
            is LenovoWatchEvent.OneKeyMeasurement -> updateHealth(event)
            else -> Unit
        }
        if (_state.value.stage == LenovoWatchProbeStage.SYNCING) scheduleSyncQuietTimeout()
    }

    private fun handleConnectionResult(state: LenovoWatchConnectionState) {
        mainHandler.removeCallbacks(handshakeTimeout)
        when (state) {
            LenovoWatchConnectionState.ACCEPTED -> {
                _state.update { it.copy(stage = LenovoWatchProbeStage.READY, statusText = "手表已连接，可手动同步") }
                enqueue(LenovoWatchProtocol.deviceInfo())
                enqueue(LenovoWatchProtocol.foreground())
            }

            LenovoWatchConnectionState.REJECTED -> failAndDisconnect("手表拒绝了连接")
            LenovoWatchConnectionState.TIMED_OUT -> failAndDisconnect("手表确认超时")
            LenovoWatchConnectionState.WATCH_UNBOUND -> failAndDisconnect("手表尚未绑定官方账户")
            LenovoWatchConnectionState.ACCOUNT_MISMATCH -> failAndDisconnect("官方账户 UID 不匹配；未执行任何解绑操作")
            LenovoWatchConnectionState.UNKNOWN -> failAndDisconnect("手表返回未知连接状态")
        }
    }

    private fun updateHealth(event: LenovoWatchEvent.CurrentActivity) {
        _state.update {
            it.copy(
                health = it.health.copy(
                    steps = event.steps,
                    calories = event.calories,
                    shallowSleepMinutes = event.shallowSleepMinutes,
                    deepSleepMinutes = event.deepSleepMinutes,
                    awakeCount = event.awakeCount,
                    exerciseSeconds = event.exerciseSeconds,
                    exerciseCount = event.exerciseCount,
                ),
            )
        }
    }

    private fun updateHealth(event: LenovoWatchEvent.HourlyVitals) {
        _state.update {
            it.copy(
                health = it.health.copy(
                    heartRate = event.heartRate.takeIf { value -> value > 0 } ?: it.health.heartRate,
                    bloodOxygen = event.bloodOxygen.takeIf { value -> value > 0 } ?: it.health.bloodOxygen,
                    exerciseSeconds = event.exerciseSeconds ?: it.health.exerciseSeconds,
                    exerciseCount = event.exerciseCount ?: it.health.exerciseCount,
                ),
            )
        }
    }

    private fun updateHealth(event: LenovoWatchEvent.HourlyRecovery) {
        _state.update {
            it.copy(
                health = it.health.copy(
                    immunity = event.immunity.takeIf { value -> value > 0 } ?: it.health.immunity,
                    temperatureCelsius = event.temperatureCelsius.takeIf { value -> value > 0 } ?: it.health.temperatureCelsius,
                ),
            )
        }
    }

    private fun updateHealth(event: LenovoWatchEvent.Measurement) {
        _state.update {
            val health = when (event.kind) {
                LenovoWatchMeasurementKind.HEART_RATE -> it.health.copy(heartRate = event.primaryValue.toInt())
                LenovoWatchMeasurementKind.BLOOD_OXYGEN -> it.health.copy(bloodOxygen = event.primaryValue.toInt())
                LenovoWatchMeasurementKind.TEMPERATURE -> it.health.copy(temperatureCelsius = event.primaryValue)
                LenovoWatchMeasurementKind.IMMUNITY -> it.health.copy(immunity = event.primaryValue.toInt())
                else -> it.health
            }
            it.copy(health = health)
        }
    }

    private fun updateHealth(event: LenovoWatchEvent.OneKeyMeasurement) {
        _state.update {
            it.copy(
                health = it.health.copy(
                    heartRate = event.heartRate.takeIf { value -> value > 0 } ?: it.health.heartRate,
                    bloodOxygen = event.bloodOxygen.takeIf { value -> value >= 60 } ?: it.health.bloodOxygen,
                    systolic = event.systolic.takeIf { value -> value > 0 } ?: it.health.systolic,
                    diastolic = event.diastolic.takeIf { value -> value > 0 } ?: it.health.diastolic,
                ),
            )
        }
    }

    private fun enqueue(frame: ByteArray) {
        LenovoWatchProtocol.splitForBle(frame).forEach(writeQueue::addLast)
        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInFlight) return
        val gatt = gatt ?: return
        val characteristic = writeCharacteristic ?: return
        val chunk = writeQueue.pollFirst() ?: return
        writeInFlight = true
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (error: SecurityException) {
            failAndDisconnect("写入权限不可用：${error.message}")
            false
        }
        if (!started) {
            writeInFlight = false
            writeQueue.clear()
            if (_state.value.stage != LenovoWatchProbeStage.ERROR) {
                failAndDisconnect("系统未接受手表写入请求")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
        if (!hasBluetoothPermissions()) return
        runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun scheduleSyncQuietTimeout() {
        mainHandler.removeCallbacks(syncQuietTimeout)
        mainHandler.postDelayed(syncQuietTimeout, SYNC_QUIET_MS)
    }

    private fun fail(message: String) {
        _state.update { it.copy(stage = LenovoWatchProbeStage.ERROR, statusText = "连接失败", error = message) }
    }

    private fun failAndDisconnect(message: String, target: BluetoothGatt? = gatt) {
        fail(message)
        stopScan()
        mainHandler.removeCallbacks(syncQuietTimeout)
        mainHandler.removeCallbacks(handshakeTimeout)
        writeQueue.clear()
        writeInFlight = false
        writeCharacteristic = null
        packetAssembler.reset()
        target?.let { current ->
            runCatching { current.disconnect() }
            closeGatt(current)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(target: BluetoothGatt) {
        runCatching { target.close() }
        if (gatt === target) gatt = null
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun LenovoWatchEvent.summary(): String = when (this) {
        is LenovoWatchEvent.PairingCapabilities -> "能力信息 ${bytes.joinToString("/")}"
        is LenovoWatchEvent.ConnectionResult -> "连接状态 ${state.name}"
        is LenovoWatchEvent.CurrentActivity -> "当前活动 $steps 步"
        is LenovoWatchEvent.HourlyVitals -> "整点健康 ${recordedHour.toLocalDate()} ${recordedHour.hour}:00"
        is LenovoWatchEvent.HourlyRecovery -> "体温/免疫力 ${temperatureCelsius}℃ / $immunity"
        is LenovoWatchEvent.SleepSegment -> "睡眠片段 $durationMinutes 分钟"
        is LenovoWatchEvent.Measurement -> "测量 ${kind.name} $primaryValue"
        is LenovoWatchEvent.InstantMeasurement -> "即时测量 ${kind.name} $primaryValue"
        is LenovoWatchEvent.OneKeyMeasurement -> "一键测量 心率$heartRate / 血氧$bloodOxygen / 血压$systolic/$diastolic"
        is LenovoWatchEvent.Unknown -> "未解析帧 ${command?.toString(16) ?: "--"}/${subcommand?.toString(16) ?: "--"}"
        is LenovoWatchEvent.Malformed -> "无效帧 $reason"
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) }

    private companion object {
        const val TARGET_ADDRESS = "C8:01:00:29:26:AF"
        const val SCAN_TIMEOUT_MS = 12_000L
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
        const val SYNC_QUIET_MS = 3_000L
        const val WRITE_GAP_MS = 100L
        val CLIENT_CHARACTERISTIC_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
