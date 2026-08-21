package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import java.util.ArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import org.koin.android.ext.android.inject

internal const val ACTION_OPEN_MAGIC_PORTAL_DRAFT = "dev.sundby.zhixing.action.OPEN_MAGIC_PORTAL_DRAFT"
internal const val EXTRA_MAGIC_PORTAL_TEXT = "dev.sundby.zhixing.extra.MAGIC_PORTAL_TEXT"
internal const val EXTRA_MAGIC_PORTAL_FILES = "dev.sundby.zhixing.extra.MAGIC_PORTAL_FILES"
internal const val EXTRA_MAGIC_PORTAL_MIME_TYPES = "dev.sundby.zhixing.extra.MAGIC_PORTAL_MIME_TYPES"

/**
 * Explicit-only gateway registered with HONOR Magic Portal.
 *
 * Magic Portal launches its target with a separate task that may be cleared. This activity keeps that
 * vendor-owned task away from [RouteActivity], imports temporary image grants immediately, and forwards only
 * app-managed file URIs to the normal chat UI.
 */
class HonorMagicPortalActivity : ComponentActivity() {
    private val filesManager by inject<FilesManager>()
    private var importJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleMagicPortalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMagicPortalIntent(intent)
    }

    private fun handleMagicPortalIntent(sourceIntent: Intent) {
        importJob?.cancel()
        val candidate = sourceIntent.toMagicPortalCandidate()
        val payload = candidate.normalize { uri ->
            filesManager.getFileMimeType(Uri.parse(uri))
        } ?: run {
            finishAndRemoveTask()
            return
        }

        importJob = lifecycleScope.launch {
            val importedImages = withContext(Dispatchers.IO) {
                payload.images.mapNotNull { image ->
                    val entity = runCatching {
                        filesManager.saveManagedFromUri(
                            folder = FileFolders.UPLOAD,
                            uri = Uri.parse(image.uri),
                            mimeType = image.mimeType,
                        )
                    }.getOrNull() ?: return@mapNotNull null
                    val localFile = filesManager.getFile(entity)
                    if (!localFile.isFile || localFile.length() <= 0L) {
                        filesManager.delete(entity.id)
                        return@mapNotNull null
                    }
                    MagicPortalImage(uri = localFile.toUri().toString(), mimeType = image.mimeType)
                }
            }
            if (payload.text == null && importedImages.isEmpty()) {
                finishAndRemoveTask()
                return@launch
            }

            startActivity(
                Intent(this@HonorMagicPortalActivity, RouteActivity::class.java).apply {
                    action = ACTION_OPEN_MAGIC_PORTAL_DRAFT
                    putExtra(EXTRA_MAGIC_PORTAL_TEXT, payload.text)
                    putStringArrayListExtra(
                        EXTRA_MAGIC_PORTAL_FILES,
                        ArrayList(importedImages.map { it.uri }),
                    )
                    putStringArrayListExtra(
                        EXTRA_MAGIC_PORTAL_MIME_TYPES,
                        ArrayList(importedImages.map { it.mimeType }),
                    )
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            )
            finishAndRemoveTask()
        }
    }
}

internal fun Intent.toMagicPortalCandidate(): MagicPortalCandidate = MagicPortalCandidate(
    action = action,
    declaredMimeType = type,
    text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
    streamUris = readSharedUris().map(Uri::toString),
)

private fun Intent.readSharedUris(): List<Uri> = buildList {
    if (action == Intent.ACTION_SEND_MULTIPLE) {
        addAll(parcelableUriListExtra(Intent.EXTRA_STREAM))
    } else {
        parcelableUriExtra(Intent.EXTRA_STREAM)?.let(::add)
    }
    clipData?.let { clips ->
        repeat(clips.itemCount) { index ->
            clips.getItemAt(index).uri?.let(::add)
        }
    }
}.distinct()

@Suppress("DEPRECATION")
private fun Intent.parcelableUriExtra(name: String): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, Uri::class.java)
    } else {
        getParcelableExtra(name)
    }

@Suppress("DEPRECATION")
private fun Intent.parcelableUriListExtra(name: String): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, Uri::class.java).orEmpty()
    } else {
        getParcelableArrayListExtra<Uri>(name).orEmpty()
    }
