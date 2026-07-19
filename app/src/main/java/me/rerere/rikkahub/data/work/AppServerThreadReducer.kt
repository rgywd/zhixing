package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.workflow.codex.CodexItem
import me.rerere.rikkahub.data.workflow.codex.CodexRuntimeState
import me.rerere.rikkahub.data.workflow.codex.CodexThread
import me.rerere.rikkahub.data.workflow.codex.CodexThreadDetail
import me.rerere.rikkahub.data.workflow.codex.CodexTurn

/** Maps the official App Server v2 Thread/Turn/Item wire shape to the existing Zhixing projector model. */
object AppServerThreadReducer {
    fun containsItem(detail: CodexThreadDetail, itemId: String?): Boolean =
        itemId != null && detail.turns.any { turn -> turn.items.any { it.itemId == itemId } }

    fun activeTurnId(detail: CodexThreadDetail): String? = detail.turns
        .lastOrNull { it.status == "inProgress" || it.status == "running" }
        ?.turnId

    fun snapshot(result: JsonElement, repositoryId: String, machineId: String = "direct"): CodexThreadDetail {
        val thread = result.jsonObject["thread"]?.jsonObject ?: result.jsonObject
        return mapThread(thread, repositoryId, machineId)
    }

    fun mapThread(raw: JsonObject, repositoryId: String, machineId: String = "direct"): CodexThreadDetail {
        val threadId = raw.string("id") ?: error("App Server thread is missing id")
        val status = raw["status"].statusName()
        val turns = raw.array("turns").mapNotNull { it as? JsonObject }.map(::mapTurn)
        val createdAt = raw.seconds("createdAt")
        val updatedAt = raw.seconds("updatedAt")
        val recencyAt = raw.secondsOrNull("recencyAt") ?: updatedAt
        val preview = raw.string("preview").orEmpty().visibleUserText()
        val name = raw.string("name").orEmpty().visibleUserText()
        return CodexThreadDetail(
            thread = CodexThread(
                machineId = machineId,
                threadId = threadId,
                projectId = repositoryId,
                name = name.takeIf(String::isNotBlank) ?: preview.lineSequence().firstOrNull().orEmpty(),
                preview = preview,
                createdAt = createdAt,
                updatedAt = updatedAt,
                recencyAt = recencyAt,
                archived = false,
                source = raw["source"]?.toString().orEmpty(),
                parentThreadId = raw.string("parentThreadId"),
                forkedFromId = raw.string("forkedFromId"),
                isSubagent = raw.string("parentThreadId") != null,
                isAutomation = false,
                runtimeState = status.runtimeState(),
                rawStatus = status,
                isPinned = false,
            ),
            turns = turns,
            cwd = raw.string("cwd"),
        )
    }

    fun apply(detail: CodexThreadDetail, notification: AppServerNotification): CodexThreadDetail {
        val params = notification.params as? JsonObject ?: return detail
        if (params.string("threadId")?.let { it != detail.thread?.threadId } == true) return detail
        return when (notification.method) {
            "turn/started" -> {
                val turn = (params["turn"] as? JsonObject)?.let(::mapTurn) ?: return detail
                detail.copy(
                    thread = detail.thread?.copy(runtimeState = CodexRuntimeState.RUNNING, rawStatus = "active"),
                    turns = detail.turns.mergeTurn(turn),
                )
            }
            "turn/completed" -> {
                val turn = (params["turn"] as? JsonObject)?.let(::mapTurn) ?: return detail
                detail.copy(
                    thread = detail.thread?.copy(runtimeState = turn.status.runtimeState(), rawStatus = turn.status),
                    // App Server's completion notification is intentionally sparse and can omit
                    // the Items already streamed through item/* notifications. Replacing the whole
                    // Turn here erased the visible conversation as soon as a response completed.
                    turns = detail.turns.mergeTurn(turn),
                )
            }
            "item/started", "item/completed" -> applyItem(detail, params)
            "item/agentMessage/delta" -> applyTextDelta(detail, params, "agentMessage")
            "item/plan/delta" -> applyTextDelta(detail, params, "plan")
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> applyTextDelta(detail, params, "reasoning")
            "item/commandExecution/outputDelta" -> applyOutputDelta(detail, params, "commandExecution", "aggregatedOutput")
            "item/fileChange/outputDelta" -> applyOutputDelta(detail, params, "fileChange", "output")
            "item/fileChange/patchUpdated" -> applyRawField(detail, params, "fileChange", "changes", params["changes"])
            "item/mcpToolCall/progress" -> applyOutputDelta(detail, params, "mcpToolCall", "output", "message")
            "turn/plan/updated" -> applyPlanUpdate(detail, params)
            "error" -> applyError(detail, params)
            "model/rerouted" -> applyModelReroute(detail, params)
            "thread/status/changed" -> {
                val status = params["status"].statusName()
                detail.copy(thread = detail.thread?.copy(runtimeState = status.runtimeState(), rawStatus = status))
            }
            else -> applyOpaque(detail, notification.method, params)
        }
    }

    /** Materializes request_user_input as the same native ask_user tool used by ordinary chats. */
    fun applyServerRequest(detail: CodexThreadDetail, request: AppServerRequest): CodexThreadDetail {
        if (request.method != "item/tool/requestUserInput") return detail
        val params = request.params as? JsonObject ?: return detail
        if (params.string("threadId")?.let { it != detail.thread?.threadId } == true) return detail
        val turnId = params.string("turnId") ?: "request-turn-${request.id}"
        val itemId = params.string("itemId") ?: "request-${request.id}"
        val questions = params.array("questions")
        val item = CodexItem(
            itemId = itemId,
            type = "dynamicToolCall",
            rawType = "dynamicToolCall",
            role = "agent",
            text = null,
            status = "inProgress",
            raw = buildJsonObject {
                put("id", itemId)
                put("type", "dynamicToolCall")
                put("tool", "ask_user")
                put("status", "inProgress")
                put("arguments", buildJsonObject {
                    put("questions", buildJsonArray {
                        questions.mapNotNull { it as? JsonObject }.forEach { question ->
                            add(buildJsonObject {
                                put("id", question.string("id").orEmpty())
                                put("question", question.string("question").orEmpty())
                                val options = question.array("options").mapNotNull { option ->
                                    (option as? JsonObject)?.string("label")
                                }
                                put("options", buildJsonArray { options.forEach { add(JsonPrimitive(it)) } })
                                put("selection_type", if (options.isEmpty()) "text" else "single")
                            })
                        }
                    })
                })
            },
        )
        val turns = detail.turns.ensureTurn(turnId)
        return detail.copy(turns = turns.map { turn ->
            if (turn.turnId != turnId) turn else turn.copy(items = turn.items.replaceBy(CodexItem::itemId, item))
        })
    }

    private fun applyItem(detail: CodexThreadDetail, params: JsonObject): CodexThreadDetail {
        val turnId = params.string("turnId") ?: return detail
        val item = (params["item"] as? JsonObject)?.let(::mapItem) ?: return detail
        return detail.copy(turns = detail.turns.map { turn ->
            if (turn.turnId != turnId) turn else turn.copy(items = turn.items.replaceBy(CodexItem::itemId, item))
        })
    }

    private fun applyTextDelta(detail: CodexThreadDetail, params: JsonObject, rawType: String): CodexThreadDetail {
        val turnId = params.string("turnId") ?: return detail
        val itemId = params.string("itemId") ?: return detail
        val delta = params.string("delta") ?: return detail
        return detail.copy(turns = detail.turns.map { turn ->
            if (turn.turnId != turnId) return@map turn
            val existing = turn.items.firstOrNull { it.itemId == itemId }
            val item = (existing ?: CodexItem(
                itemId = itemId,
                type = rawType,
                rawType = rawType,
                role = "agent",
                text = "",
                status = "inProgress",
            )).copy(text = existing?.text.orEmpty() + delta, status = "inProgress")
            turn.copy(items = turn.items.replaceBy(CodexItem::itemId, item))
        })
    }

    private fun applyOutputDelta(
        detail: CodexThreadDetail,
        params: JsonObject,
        rawType: String,
        field: String,
        sourceField: String = "delta",
    ): CodexThreadDetail {
        val delta = params.string(sourceField) ?: return detail
        val turnId = params.string("turnId") ?: return detail
        val itemId = params.string("itemId") ?: return detail
        return updateRawItem(detail, turnId, itemId, rawType) { raw ->
            val previous = raw.string(field).orEmpty()
            val separator = if (sourceField == "message" && previous.isNotBlank()) "\n" else ""
            copyRaw(raw) { put(field, previous + separator + delta) }
        }
    }

    private fun applyRawField(
        detail: CodexThreadDetail,
        params: JsonObject,
        rawType: String,
        field: String,
        value: JsonElement?,
    ): CodexThreadDetail {
        val turnId = params.string("turnId") ?: return detail
        val itemId = params.string("itemId") ?: return detail
        val incoming = value ?: return detail
        return updateRawItem(detail, turnId, itemId, rawType) { raw ->
            copyRaw(raw) { put(field, incoming) }
        }
    }

    private fun applyPlanUpdate(detail: CodexThreadDetail, params: JsonObject): CodexThreadDetail {
        val turnId = params.string("turnId") ?: return detail
        val itemId = "turn-plan-$turnId"
        return updateRawItem(detail, turnId, itemId, "plan") { raw ->
            copyRaw(raw) {
                put("plan", params["plan"] ?: JsonArray(emptyList()))
                params["explanation"]?.let { put("explanation", it) }
            }
        }
    }

    private fun applyError(detail: CodexThreadDetail, params: JsonObject): CodexThreadDetail {
        val turnId = params.string("turnId") ?: return detail
        val error = params["error"] as? JsonObject
        val message = error?.string("message") ?: "Codex 执行失败"
        val willRetry = (params["willRetry"] as? JsonPrimitive)?.contentOrNull == "true"
        val itemId = "turn-error-$turnId-${message.hashCode()}"
        val updated = upsertSyntheticItem(
            detail = detail,
            turnId = turnId,
            item = CodexItem(
                itemId = itemId,
                type = "error",
                rawType = "error",
                role = "system",
                text = message,
                status = "completed",
                raw = buildJsonObject {
                put("message", message)
                put("willRetry", willRetry)
                error?.let { put("error", it) }
                },
            ),
        ).copy(
            thread = detail.thread?.copy(
                runtimeState = if (willRetry) CodexRuntimeState.RUNNING else CodexRuntimeState.SYSTEM_ERROR,
                rawStatus = if (willRetry) "active" else "systemError",
            )
        )
        return updated.copy(turns = updated.turns.map { turn ->
            if (turn.turnId == turnId) turn.copy(error = message) else turn
        })
    }

    private fun applyModelReroute(detail: CodexThreadDetail, params: JsonObject): CodexThreadDetail {
        val turnId = params.string("turnId") ?: return detail
        val from = params.string("fromModel").orEmpty()
        val to = params.string("toModel").orEmpty()
        val itemId = "model-rerouted-$turnId-${from.hashCode()}-${to.hashCode()}"
        return upsertSyntheticItem(
            detail = detail,
            turnId = turnId,
            item = CodexItem(
                itemId = itemId,
                type = "modelRerouted",
                rawType = "modelRerouted",
                role = "system",
                text = "模型已从 $from 切换到 $to",
                status = "completed",
                raw = params,
            ),
        )
    }

    private fun applyOpaque(detail: CodexThreadDetail, method: String, params: JsonObject): CodexThreadDetail {
        val turnId = params.string("turnId") ?: return detail
        val payload = params.toString().take(MAX_OPAQUE_CHARS)
        return upsertSyntheticItem(
            detail = detail,
            turnId = turnId,
            item = CodexItem(
                itemId = "opaque-${method.hashCode()}-${payload.hashCode()}",
                type = "opaqueNotification",
                rawType = "opaqueNotification",
                role = "system",
                text = null,
                status = "completed",
                raw = buildJsonObject {
                    put("method", method)
                    put("receivedAt", System.currentTimeMillis())
                    put("payload", payload)
                },
            ),
        )
    }

    private fun updateRawItem(
        detail: CodexThreadDetail,
        turnId: String,
        itemId: String,
        rawType: String,
        update: (JsonObject) -> JsonObject,
    ): CodexThreadDetail {
        val existing = detail.turns.firstOrNull { it.turnId == turnId }?.items?.firstOrNull { it.itemId == itemId }
        val base = existing ?: CodexItem(
            itemId = itemId,
            type = rawType,
            rawType = rawType,
            role = "agent",
            text = null,
            status = "inProgress",
            raw = buildJsonObject {
                put("id", itemId)
                put("type", rawType)
                put("status", "inProgress")
            },
        )
        return upsertSyntheticItem(detail, turnId, base.copy(raw = update(base.raw), status = "inProgress"))
    }

    private fun upsertSyntheticItem(detail: CodexThreadDetail, turnId: String, item: CodexItem): CodexThreadDetail {
        val turns = detail.turns.ensureTurn(turnId)
        return detail.copy(turns = turns.map { turn ->
            if (turn.turnId != turnId) turn else turn.copy(items = turn.items.replaceBy(CodexItem::itemId, item))
        })
    }

    private fun copyRaw(raw: JsonObject, update: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            raw.forEach { (key, value) -> put(key, value) }
            update()
        }

    private fun mapTurn(raw: JsonObject): CodexTurn = CodexTurn(
        turnId = raw.string("id") ?: error("App Server turn is missing id"),
        status = raw["status"].statusName(),
        startedAt = raw.secondsOrNull("startedAt"),
        completedAt = raw.secondsOrNull("completedAt"),
        error = (raw["error"] as? JsonObject)?.string("message"),
        items = raw.array("items").mapNotNull { it as? JsonObject }.map(::mapItem),
    )

    private fun mapItem(raw: JsonObject): CodexItem {
        val type = raw.string("type") ?: "opaque"
        val text = when (type) {
            "userMessage" -> raw.array("content")
                .mapNotNull { it as? JsonObject }
                .filter { it.string("type") == "text" }
                .mapNotNull { it.string("text") }
                .joinToString("\n")
            "agentMessage", "plan" -> raw.string("text")
            "reasoning" -> (raw.array("summary") + raw.array("content"))
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .joinToString("\n")
            else -> null
        }
        return CodexItem(
            itemId = raw.string("id") ?: "opaque-${raw.hashCode()}",
            type = type,
            rawType = type,
            role = if (type == "userMessage") "user" else "agent",
            text = text,
            status = when (raw.string("status")) {
                "completed", "failed", "declined" -> "completed"
                else -> "inProgress"
            },
            raw = raw,
        )
    }

    private fun String.runtimeState(): CodexRuntimeState = when (this) {
        "active", "running", "inProgress" -> CodexRuntimeState.RUNNING
        "waitingForApproval" -> CodexRuntimeState.WAITING_APPROVAL
        "waitingForUserInput" -> CodexRuntimeState.WAITING_USER
        "failed", "systemError" -> CodexRuntimeState.SYSTEM_ERROR
        "idle", "completed", "interrupted" -> CodexRuntimeState.IDLE
        else -> CodexRuntimeState.UNKNOWN
    }

    private fun String.visibleUserText(): String = AppServerAttachmentManifest.parse(this).visibleText

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.seconds(key: String): Long = secondsOrNull(key) ?: System.currentTimeMillis()
    private fun JsonObject.secondsOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull?.times(1_000)

    private fun JsonElement?.statusName(): String = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonObject -> string("type") ?: keys.firstOrNull().orEmpty()
        null, JsonNull -> "unknown"
        else -> toString()
    }

    private fun <T, K> List<T>.replaceBy(key: (T) -> K, incoming: T): List<T> {
        val incomingKey = key(incoming)
        val index = indexOfFirst { key(it) == incomingKey }
        if (index < 0) return this + incoming
        return toMutableList().also { it[index] = incoming }
    }

    private fun List<CodexTurn>.mergeTurn(incoming: CodexTurn): List<CodexTurn> {
        val existing = firstOrNull { it.turnId == incoming.turnId }
        val merged = if (existing == null) incoming else incoming.copy(
            startedAt = incoming.startedAt ?: existing.startedAt,
            completedAt = incoming.completedAt ?: existing.completedAt,
            error = incoming.error ?: existing.error,
            items = if (incoming.items.isEmpty()) existing.items else incoming.items,
        )
        return replaceBy(CodexTurn::turnId, merged)
    }

    private fun List<CodexTurn>.ensureTurn(turnId: String): List<CodexTurn> =
        if (any { it.turnId == turnId }) this else this + CodexTurn(
            turnId = turnId,
            status = "inProgress",
            startedAt = System.currentTimeMillis(),
            completedAt = null,
            error = null,
            items = emptyList(),
        )

    private const val MAX_OPAQUE_CHARS = 8_192
}
