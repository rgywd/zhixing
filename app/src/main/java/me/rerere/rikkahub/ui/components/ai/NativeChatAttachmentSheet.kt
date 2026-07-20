package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import java.io.File
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.isAllowedFileType
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * The single attachment sheet used by both Provider Chat and Codex Work.
 * Platform launchers, crop behavior, validation and input previews must not
 * drift between runtimes; callers only append runtime-specific rows below the
 * shared attachment actions.
 */
@Composable
fun NativeChatAttachmentSheet(
    state: ChatInputState,
    settings: Settings,
    onDismiss: () -> Unit,
    allowVideoAndAudio: Boolean = false,
    filesManager: FilesManager = koinInject(),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            state.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            onDismiss()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        },
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = cameraOutputUri
        if (captured && uri != null) {
            if (settings.displaySetting.skipCropImage) {
                state.addImages(filesManager.createChatFilesByContents(listOf(uri)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                onDismiss()
            } else {
                launchCameraCrop(uri)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val takePhoto = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                requireNotNull(cameraOutputFile),
            )
            cameraLauncher.launch(requireNotNull(cameraOutputUri))
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            state.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            onDismiss()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        },
    )
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (settings.displaySetting.skipCropImage) {
            state.addImages(filesManager.createChatFilesByContents(uris))
            onDismiss()
        } else if (uris.size == 1) {
            val source = uris.first()
            val temporary = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
            runCatching {
                val converted = ImageUtils.isHeifImage(context, source) &&
                    ImageUtils.convertHeifToJpeg(context, source, temporary)
                if (!converted) {
                    context.contentResolver.openInputStream(source)?.use { input ->
                        temporary.outputStream().use(input::copyTo)
                    }
                }
                preCropTempFile = temporary
                launchImageCrop(temporary.toUri())
            }.onFailure {
                Log.e("ImagePickButton", "Failed to prepare image for crop", it)
                launchImageCrop(source)
            }
        } else {
            state.addImages(filesManager.createChatFilesByContents(uris))
            onDismiss()
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            state.addVideos(filesManager.createChatFilesByContents(uris))
            onDismiss()
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            state.addAudios(filesManager.createChatFilesByContents(uris))
            onDismiss()
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val documents = uris.mapNotNull { uri ->
            val name = filesManager.getFileNameFromUri(uri) ?: "file"
            val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
            if (!isAllowedFileType(name, mime)) {
                toaster.show(
                    resources.getString(R.string.chat_input_unsupported_file_type, name),
                    type = ToastType.Error,
                )
                return@mapNotNull null
            }
            val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
            if (localUri == null) {
                toaster.show(
                    resources.getString(R.string.chat_input_file_read_failed, name),
                    type = ToastType.Error,
                )
                null
            } else {
                UIMessagePart.Document(localUri.toString(), name, mime)
            }
        }
        if (documents.isNotEmpty()) {
            state.addFiles(documents)
            onDismiss()
        }
    }

    ModalBottomSheet(
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            ChatAttachmentActions(
                allowVideoAndAudio = allowVideoAndAudio,
                onTakePic = takePhoto,
                onPickImage = { imagePicker.launch("image/*") },
                onPickVideo = { videoPicker.launch("video/*") },
                onPickAudio = { audioPicker.launch("audio/*") },
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            content()
        }
    }
}
