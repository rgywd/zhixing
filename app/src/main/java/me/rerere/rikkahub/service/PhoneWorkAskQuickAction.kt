package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.rikkahub.data.work.PhoneWorkAnswer
import me.rerere.rikkahub.data.work.PhoneWorkAskPayload
import me.rerere.rikkahub.data.work.PhoneWorkRepository
import org.koin.core.context.GlobalContext

private const val TAG = "WorkAskQuickAction"

internal val workQuickActionJson = Json { ignoreUnknownKeys = true }

/**
 * 从 ASK 事件 payload 推导「全部采用推荐方案」的回答。
 * 任何一题缺少推荐选项时返回 null，此时不提供快捷动作。
 */
internal fun recommendedAnswersFromAskPayload(payload: JsonObject): List<PhoneWorkAnswer>? {
    val ask = runCatching { workQuickActionJson.decodeFromJsonElement<PhoneWorkAskPayload>(payload) }
        .getOrNull()
        ?: return null
    if (ask.questions.isEmpty()) return null
    return ask.questions.map { question ->
        if (question.recommendedOptionIds.isEmpty()) return null
        PhoneWorkAnswer(
            questionId = question.id,
            selectedOptionIds = question.recommendedOptionIds,
            otherText = null,
        )
    }
}

/**
 * ask 通知上的「采用推荐方案」动作：直接以全部推荐选项提交回答，
 * 超时落定后的迟到提交由 Core 按契约幂等忽略。
 */
class WorkAskActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACCEPT_RECOMMENDED) return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return
        val askId = intent.getStringExtra(EXTRA_ASK_ID)?.takeIf { it.isNotBlank() } ?: return
        val answersJson = intent.getStringExtra(EXTRA_ANSWERS) ?: return
        val answers = runCatching {
            workQuickActionJson.decodeFromString<List<PhoneWorkAnswer>>(answersJson)
        }.getOrNull() ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                GlobalContext.get().get<PhoneWorkRepository>().answer(sessionId, askId, answers)
                NotificationManagerCompat.from(context).cancel(PhoneWorkTrackingService.askNotificationId(askId))
                showToast(context, "已采用推荐方案")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Unable to accept recommended ask answers", error)
                showToast(context, "提交失败，请打开会话手动处理")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun showToast(context: Context, text: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_ACCEPT_RECOMMENDED = "me.rerere.rikkahub.action.WORK_ASK_ACCEPT_RECOMMENDED"
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val EXTRA_ASK_ID = "askId"
        private const val EXTRA_ANSWERS = "answers"

        fun acceptRecommendedPendingIntent(
            context: Context,
            sessionId: String,
            askId: String,
            answers: List<PhoneWorkAnswer>,
        ): PendingIntent {
            val intent = Intent(context, WorkAskActionReceiver::class.java)
                .setAction(ACTION_ACCEPT_RECOMMENDED)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_ASK_ID, askId)
                .putExtra(EXTRA_ANSWERS, workQuickActionJson.encodeToString(answers))
            return PendingIntent.getBroadcast(
                context,
                askId.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
