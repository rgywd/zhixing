package me.rerere.rikkahub.data.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Opt-in live model evaluation. Credentials stay in the loopback host proxy, never on the device. */
@RunWith(AndroidJUnit4::class)
class DeepSeekLiveInstrumentedTest {
    @Test fun liveAgentJourney() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val modelName = InstrumentationRegistry.getArguments().getString("live_model")
        assumeTrue(modelName in listOf("deepseek-flash", "deepseek-v4-pro"))
        val store = GlobalContext.get().get<SettingsStore>()
        val chat = GlobalContext.get().get<ChatService>()
        val db = GlobalContext.get().get<AppDatabase>()
        val original = store.settingsFlowRaw.first()
        val model = Model(modelId = modelName!!, displayName = modelName,
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING))
        val main = Assistant(name = "真实模型验收", chatModelId = model.id, streamOutput = false,
            reasoningLevel = ReasoningLevel.HIGH, maxTokens = 8192, capabilities = setOf("agents"),
            systemPrompt = "你是知行主助手，实际执行用户要求，用中文回答。")
        val conversationId = Uuid.random()
        val turns = mutableListOf<JsonObject>()
        val checks = linkedMapOf<String, Boolean>()
        var failure: String? = null
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
            me.rerere.rikkahub.RouteActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        suspend fun capture(label: String, started: Long): String {
            delay(1000)
            withTimeout(480_000) { chat.getGenerationJobStateFlow(conversationId).first { it == null } }
            val conversation = chat.getConversationFlow(conversationId).value
            val messages = conversation.currentMessages
            val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
            val parts = messages.drop(lastUserIndex + 1).flatMap { it.parts }
            val answer = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.parts
                ?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }.orEmpty()
            turns += buildJsonObject {
                put("label", label); put("seconds", (System.currentTimeMillis() - started) / 1000.0)
                put("answer", answer)
                put("tools", buildJsonArray { parts.filterIsInstance<UIMessagePart.Tool>().forEach { tool -> add(buildJsonObject {
                    put("name", tool.toolName); put("input", tool.input); put("pending", tool.isPending)
                    put("output", tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
                }) } })
                put("errors", buildJsonArray { chat.errors.value.filter { it.conversationId == conversationId }.forEach {
                    add(it.error.message?.take(2000).orEmpty())
                } })
            }
            File(instrumentation.targetContext.filesDir, "deepseek-$modelName-progress.json").writeText(buildJsonObject {
                put("model", modelName); put("turns", JsonArray(turns))
                put("checks", buildJsonObject { checks.forEach { (key, value) -> put(key, value) } })
            }.toString())
            return answer
        }
        try {
            store.update { it.copy(assistants = listOf(main), assistantId = main.id, chatModelId = model.id,
                providers = listOf(ProviderSetting.OpenAI(name = "DeepSeek live loopback", apiKey = "local-live-test",
                    baseUrl = "http://127.0.0.1:18765/v1", models = listOf(model))), enableSuggestion = false) }
            chat.clearAllErrors()
            chat.saveConversation(conversationId, Conversation.ofId(conversationId, main.id).copy(title = "DeepSeek live $modelName"))
            suspend fun turn(label: String, prompt: String): String {
                val started = System.currentTimeMillis()
                chat.sendMessage(conversationId, listOf(UIMessagePart.Text(prompt)))
                return capture(label, started)
            }
            turn("self_configuration", "把你的名字改为“验收主助手”，并在你自己的持久提示词末尾增加一条规则：每条回复最后单独一行写“知行验收完成”。保留已有提示词内容。请实际修改，完成后简短告诉我。")
            val configured = store.settingsFlowRaw.first().assistants.single { it.id == main.id }
            checks["self_name_persisted"] = configured.name == "验收主助手"
            checks["self_prompt_persisted"] = configured.systemPrompt.contains("知行验收完成") && configured.systemPrompt.contains("你是知行主助手")
            val next = turn("next_turn_prompt", "17乘23等于多少？")
            checks["next_turn_prompt_effective"] = next.contains("391") && next.trim().endsWith("知行验收完成")
            val delegated = turn("create_and_delegate", "请创建一个名叫“计算验收助手”的子智能体，只给它计算脚本能力，不给联网、账单或其他业务能力。它的提示词应要求：计算必须使用脚本，缺少信息时先向用户提问，不得猜测。然后把19.8、32.5、17.2三项求和交给它实际执行。拿到子任务结果后向我报告总和与实际子任务ID，不要由你自己代算。")
            val child = store.settingsFlowRaw.first().assistants.firstOrNull { it.managedBy == main.id }
            checks["child_configuration"] = child?.name == "计算验收助手" && child.capabilities == setOf("javascript")
            val firstRuns = db.agentRunDao().list(conversationId.toString())
            checks["delegation_completed"] = firstRuns.any { it.status == "COMPLETED" } && delegated.contains("69.5")
            checks["child_executed_script"] = firstRuns.any { run ->
                chat.getConversationFlow(Uuid.parse(run.id)).value.currentMessages.flatMap { it.parts }
                    .filterIsInstance<UIMessagePart.Tool>().any { it.toolName == "eval_javascript" && it.isExecuted }
            }
            turn("delegate_question", "请让刚才那个子助手处理另一个任务：先向我确认要统计哪一年，拿到我的回答后再返回“统计年份=<我回答的年份>”。年份尚未提供，别替我猜测。你负责转达子助手的问题和我的回答。")
            val waitingRuns = db.agentRunDao().list(conversationId.toString())
            checks["child_waited_for_input"] = waitingRuns.any { it.status == "WAITING_FOR_INPUT" }
            val pending = chat.getConversationFlow(conversationId).value.currentMessages.flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Tool>().firstOrNull { it.isPending }
            val started = System.currentTimeMillis()
            if (pending != null) chat.handleToolApproval(conversationId, pending.toolCallId, approved = true, answer = "2026")
            else chat.sendMessage(conversationId, listOf(UIMessagePart.Text("2026")))
            val answered = capture("answer_relay", started)
            val finalRuns = db.agentRunDao().list(conversationId.toString())
            checks["answer_relay_completed"] = answered.contains("2026") && waitingRuns.filter { it.status == "WAITING_FOR_INPUT" }
                .any { waiting -> finalRuns.any { it.id == waiting.id && it.status == "COMPLETED" } }
        } catch (error: Exception) {
            failure = error.javaClass.simpleName + ": " + error.message.orEmpty().take(1500)
        } finally {
            val runs = db.agentRunDao().list(conversationId.toString())
            val report = buildJsonObject {
                put("model", modelName); put("failure", failure); put("conversation_id", conversationId.toString())
                put("checks", buildJsonObject { checks.forEach { (key, value) -> put(key, value) } })
                put("turns", JsonArray(turns))
                put("runs", buildJsonArray { runs.forEach { add(buildJsonObject {
                    put("id", it.id); put("agent_id", it.agentId); put("status", it.status); put("revision", it.revision)
                }) } })
            }
            File(instrumentation.targetContext.filesDir, "deepseek-$modelName.json").writeText(report.toString())
            chat.stopGeneration(conversationId)
            runs.forEach { chat.stopGeneration(Uuid.parse(it.id)) }
            store.restore(original)
            instrumentation.runOnMainSync { activity.finish() }
        }
        assertTrue("Live evaluation failed: $failure; checks=$checks", failure == null && checks.size == 8 && checks.values.all { it })
    }
}
