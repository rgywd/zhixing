package me.rerere.rikkahub.ui.components.ui.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.edit
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat

/**
 * 权限状态管理类
 */
@Stable
class PermissionState internal constructor(
    private val permissions: Set<PermissionInfo>,
    private val context: Context,
    private val activity: ComponentActivity
) {
    // 权限状态映射
    private val _permissionStates = mutableStateMapOf<String, PermissionStatus>()
    val permissionStates: Map<String, PermissionStatus> = _permissionStates

    // 是否显示权限说明对话框
    var showRationaleDialog by mutableStateOf(false)
        private set

    // 当前需要显示说明的权限
    var currentRationalePermissions by mutableStateOf<List<PermissionInfo>>(emptyList())
        private set

    // 权限请求启动器
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null

    private val requestHistory = context.getSharedPreferences("permission_requests", Context.MODE_PRIVATE)

    init {
        // 初始化权限状态
        updatePermissionStates()
    }

    /**
     * 设置权限请求启动器
     */
    internal fun setPermissionLaunchers(
        multiplePermissionLauncher: ActivityResultLauncher<Array<String>>
    ) {
        this.permissionLauncher = multiplePermissionLauncher
    }

    /**
     * 更新所有权限状态
     */
    fun updatePermissionStates() {
        permissions.forEach { info ->
            _permissionStates[info.permission] = resolvePermissionStatus(
                granted = ContextCompat.checkSelfPermission(context, info.permission) == PackageManager.PERMISSION_GRANTED,
                showRationale = activity.shouldShowRequestPermissionRationale(info.permission),
                previouslyDenied = requestHistory.getBoolean(info.permission, false),
            )
        }
    }

    /**
     * 检查是否所有权限都已授权
     */
    val allPermissionsGranted: Boolean
        get() = permissions.all { permissionStates[it.permission] == PermissionStatus.Granted }

    /**
     * 检查是否所有必需权限都已授权
     */
    val allRequiredPermissionsGranted: Boolean
        get() = permissions.filter { it.required }.all { permissionStates[it.permission] == PermissionStatus.Granted }

    /**
     * 获取未授权的权限
     */
    val deniedPermissions: List<PermissionInfo>
        get() = permissions.filter { permissionStates[it.permission] != PermissionStatus.Granted }

    /**
     * 获取需要显示说明的权限（包括永久拒绝的权限）
     */
    private val permissionsNeedRationale: List<PermissionInfo>
        get() = permissions.filter {
            val status = permissionStates[it.permission]
            status == PermissionStatus.Denied && activity.shouldShowRequestPermissionRationale(it.permission) ||
            status == PermissionStatus.DeniedPermanently
        }

    /**
     * 获取永久拒绝的权限
     */
    val permanentlyDeniedPermissions: List<PermissionInfo>
        get() = permissions.filter { permissionStates[it.permission] == PermissionStatus.DeniedPermanently }

    /**
     * 请求所有未授权的权限
     */
    fun requestPermissions() {
        updatePermissionStates()
        val deniedPerms = deniedPermissions
        if (deniedPerms.isEmpty()) return

        val rationalePerms = permissionsNeedRationale
        if (rationalePerms.isNotEmpty()) {
            // 显示权限说明对话框
            currentRationalePermissions = rationalePerms
            showRationaleDialog = true
        } else {
            // 直接请求权限
            launchPermissionRequest(deniedPerms)
        }
    }

    /**
     * 请求特定权限
     */
    fun requestPermission(permission: String) {
        updatePermissionStates()
        val permissionInfo = permissions.find { it.permission == permission } ?: return
        val status = permissionStates[permission] ?: return

        if (status == PermissionStatus.Granted) return

        when (status) {
            PermissionStatus.Denied -> {
                if (activity.shouldShowRequestPermissionRationale(permission)) {
                    // 显示权限说明对话框
                    currentRationalePermissions = listOf(permissionInfo)
                    showRationaleDialog = true
                } else {
                    // 直接请求权限
                    permissionLauncher?.launch(arrayOf(permission))
                }
            }
            PermissionStatus.DeniedPermanently -> {
                // 永久拒绝，显示说明对话框并引导到设置
                currentRationalePermissions = listOf(permissionInfo)
                showRationaleDialog = true
            }
            else -> {
                // NotRequested 状态，直接请求权限
                permissionLauncher?.launch(arrayOf(permission))
            }
        }
    }

    /**
     * 从权限说明对话框继续请求权限
     */
    fun proceedFromRationale() {
        showRationaleDialog = false

        // 检查是否有永久拒绝的权限
        val permanentlyDenied = currentRationalePermissions.filter {
            permissionStates[it.permission] == PermissionStatus.DeniedPermanently
        }

        if (permanentlyDenied.isNotEmpty()) {
            // 有永久拒绝的权限，直接跳转到设置页面
            openAppSettings()
        } else {
            // 没有永久拒绝的权限，正常请求权限
            launchPermissionRequest(currentRationalePermissions)
        }

        currentRationalePermissions = emptyList()
    }

    /**
     * 取消权限请求
     */
    fun cancelPermissionRequest() {
        showRationaleDialog = false
        currentRationalePermissions = emptyList()
    }

    /**
     * 启动权限请求
     */
    private fun launchPermissionRequest(permissionInfos: List<PermissionInfo>) {
        val permissionsToRequest = permissionInfos.map { it.permission }.toTypedArray()
        permissionLauncher?.launch(permissionsToRequest)
    }

    /**
     * 跳转到应用设置页面
     */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        activity.startActivity(intent)
    }

    /**
     * 强制刷新权限状态（用于从后台回到前台时）
     * 这个方法会重新检查所有权限状态，特别处理用户可能在设置中修改的权限
     */
    fun refreshPermissionStates() = updatePermissionStates()

    /**
     * 处理权限请求结果
     */
    internal fun handlePermissionResult(results: Map<String, Boolean>) {
        // An empty result means the system dialog was cancelled: leave history unchanged.
        requestHistory.edit {
            results.forEach { (permission, granted) -> putBoolean(permission, !granted) }
        }
        updatePermissionStates()
    }

    /**
     * 处理单个权限请求结果
     */
    internal fun handleSinglePermissionResult(permission: String, granted: Boolean) {
        handlePermissionResult(mapOf(permission to granted))
    }

    /**
     * 获取权限结果
     */
    fun getPermissionResults(): MultiplePermissionResult {
        val results = permissions.associate { permissionInfo ->
            val status = permissionStates[permissionInfo.permission] ?: PermissionStatus.NotRequested
            permissionInfo.permission to PermissionResult(
                permission = permissionInfo.permission,
                status = status
            )
        }

        return MultiplePermissionResult(
            results = results,
            allRequiredGranted = permissions.filter { it.required }
                .all { permissionStates[it.permission] == PermissionStatus.Granted }
        )
    }
}

internal fun resolvePermissionStatus(
    granted: Boolean,
    showRationale: Boolean,
    previouslyDenied: Boolean,
): PermissionStatus = when {
    granted -> PermissionStatus.Granted
    showRationale -> PermissionStatus.Denied
    previouslyDenied -> PermissionStatus.DeniedPermanently
    else -> PermissionStatus.NotRequested
}
