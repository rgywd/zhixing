package me.rerere.rikkahub.ui.activity

internal const val ACTION_SEND = "android.intent.action.SEND"
internal const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

internal data class MagicPortalCandidate(
    val action: String?,
    val declaredMimeType: String?,
    val text: String?,
    val streamUris: List<String>,
)

internal data class MagicPortalImage(
    val uri: String,
    val mimeType: String,
)

internal data class MagicPortalPayload(
    val text: String?,
    val images: List<MagicPortalImage>,
)

internal fun MagicPortalCandidate.normalize(
    resolveMimeType: (String) -> String?,
): MagicPortalPayload? {
    if (action != ACTION_SEND && action != ACTION_SEND_MULTIPLE) return null

    val normalizedText = text?.takeIf { it.isNotBlank() }
    val declaredImageMime = declaredMimeType?.takeIf { it.startsWith("image/") }
    val images = streamUris.distinct().mapNotNull { uri ->
        val resolvedImageMime = resolveMimeType(uri)?.takeIf { it.startsWith("image/") }
        val imageMime = resolvedImageMime ?: declaredImageMime ?: return@mapNotNull null
        MagicPortalImage(uri = uri, mimeType = imageMime)
    }

    if (normalizedText == null && images.isEmpty()) return null
    return MagicPortalPayload(text = normalizedText, images = images)
}
