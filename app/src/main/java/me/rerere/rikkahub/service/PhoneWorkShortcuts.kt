package me.rerere.rikkahub.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.work.PhoneWorkSession

private const val TAG = "PhoneWorkShortcuts"

private const val WORK_SHORTCUT_ID_PREFIX = "work_session_"

/**
 * 桌面长按图标的动态快捷方式规格。
 * 动态部分按当前应用静态快捷方式占用后的容量裁剪。
 */
internal data class WorkShortcutSpec(
    val shortcutId: String,
    val sessionId: String,
    val rank: Int,
    val shortLabel: String,
    val longLabel: String,
)

internal fun buildWorkShortcutSpecs(
    active: List<PhoneWorkSession>,
    maxCount: Int,
): List<WorkShortcutSpec> {
    if (maxCount <= 0) return emptyList()
    return active
        .sortedWith(
            compareBy<PhoneWorkSession> { statusPriority(it.status) }
                .thenByDescending { it.updatedAt },
        )
        .take(maxCount)
        .mapIndexed { index, session ->
            val status = statusLabel(session.status)
            WorkShortcutSpec(
                shortcutId = "$WORK_SHORTCUT_ID_PREFIX${session.id}",
                sessionId = session.id,
                rank = index,
                shortLabel = "${session.repoName} · $status",
                longLabel = session.title.takeIf { it.isNotBlank() }?.let { "${session.repoName} · $it · $status" }
                    ?: "${session.repoName} · $status",
            )
        }
}

private fun statusPriority(status: String) = when (status) {
    "WAITING_FOR_USER" -> 0
    "RUNNING" -> 1
    "QUEUED" -> 2
    else -> 3
}

private fun statusLabel(status: String) = when (status) {
    "WAITING_FOR_USER" -> "等你回答"
    "RUNNING" -> "进行中"
    "QUEUED" -> "排队中"
    else -> status
}

internal fun syncWorkShortcuts(context: Context, active: List<PhoneWorkSession>) {
    runCatching {
        val capacity = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) -
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_MANIFEST).size)
            .coerceIn(0, MAX_DYNAMIC_SESSION_SHORTCUTS)
        val shortcuts = buildWorkShortcutSpecs(active, capacity).map { spec ->
            val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                .setAction(Intent.ACTION_VIEW)
                .putExtra(PhoneWorkTrackingService.EXTRA_WORK_SESSION_ID, spec.sessionId)
            ShortcutInfoCompat.Builder(context, spec.shortcutId)
                .setShortLabel(spec.shortLabel)
                .setLongLabel(spec.longLabel)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_lucide_briefcase))
                .setRank(spec.rank)
                .setLongLived(true)
                .setIntent(intent)
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }.onFailure { Log.w(TAG, "Unable to sync Work shortcuts", it) }
}

internal fun clearWorkShortcuts(context: Context) {
    runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
        .onFailure { Log.w(TAG, "Unable to clear Work shortcuts", it) }
}

private const val MAX_DYNAMIC_SESSION_SHORTCUTS = 3
