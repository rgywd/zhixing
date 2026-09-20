package me.rerere.rikkahub.data.agent

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AgentRunToolsTest {
    private val actor = Assistant(name = "Main")
    private val child = Assistant(name = "Research", managedBy = actor.id, capabilities = setOf("search"))
    private val parent = Conversation.ofId(Uuid.random(), actor.id).updateCurrentMessages(listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("private history"), UIMessagePart.Image("file:///old.png"))),
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("research this"), UIMessagePart.Image("file:///new.png"))),
    ))
    private val dao = FakeRunDao()
    private val conversations = mutableMapOf<Uuid, Conversation>()
    private var starts = 0
    private var resumes = 0
    private var capturedAttachments = emptyList<UIMessagePart>()
    private var ask = false
    private val runtime = AgentRunTools(dao, { Settings(assistants = listOf(actor, child)) }, { conversations[it] },
        start = { run, agent, instruction, attachments ->
            starts++; capturedAttachments = attachments
            assertEquals("focused request", instruction)
            val parts = if (ask) listOf(UIMessagePart.Tool(toolCallId = "question", toolName = "ask_user",
                input = """{"questions":[{"id":"q","question":"Which year?"}]}""", approvalState = ToolApprovalState.Pending))
                else listOf(UIMessagePart.Text("Research complete"))
            conversations[Uuid.parse(run.id)] = Conversation.ofId(Uuid.parse(run.id), agent.id)
                .updateCurrentMessages(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = parts)))
            dao.finish(run.id, if (ask) "WAITING_FOR_INPUT" else "COMPLETED")
        },
        continueRun = { run, answer, question ->
            resumes++; assertEquals("question", question); assertEquals("2026", answer)
            conversations[Uuid.parse(run.id)] = Conversation.ofId(Uuid.parse(run.id), child.id).updateCurrentMessages(
                listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("2026 findings")))))
            dao.finish(run.id, "COMPLETED")
        }, stop = { dao.stop(it.toString()) },
    )
    private suspend fun call(input: String, conversation: Conversation = parent): JsonObject = Json.parseToJsonElement(
        (runtime.tools(actor, conversation).single().execute(Json.parseToJsonElement(input)).single() as UIMessagePart.Text).text,
    ).jsonObject
    private fun startInput(attachments: Boolean = false) = """{"action":"start","agent_id":"${child.id}","request_id":"request-1","instruction":"focused request","include_attachments":$attachments}"""

    @Test fun `delegation is idempotent isolated and returns durable result`() = runBlocking {
        val first = call(startInput(true))
        val repeated = call(startInput(true))
        assertEquals(1, starts)
        assertEquals(first["run_id"], repeated["run_id"])
        assertEquals("COMPLETED", first["status"]!!.jsonPrimitive.content)
        assertEquals("Research complete", first["result"]!!.jsonPrimitive.content)
        assertEquals(listOf(UIMessagePart.Image("file:///new.png")), capturedAttachments)
        assertFalse(first.toString().contains("private history"))
        assertTrue(runtime.tools(child, parent).isEmpty())
    }

    @Test fun `question answers resume the exact child once`() = runBlocking {
        ask = true
        val waiting = call(startInput())
        assertEquals("WAITING_FOR_INPUT", waiting["status"]!!.jsonPrimitive.content)
        val id = waiting["run_id"]!!.jsonPrimitive.content
        val answer = """{"action":"answer","run_id":"$id","request_id":"answer-1","if_revision":1,"question_id":"question","instruction":"2026"}"""
        assertEquals("COMPLETED", call(answer)["status"]!!.jsonPrimitive.content)
        assertEquals("COMPLETED", call(answer)["status"]!!.jsonPrimitive.content)
        assertEquals(1, resumes)
        assertTrue(capturedAttachments.isEmpty())
    }

    @Test fun `another parent cannot inspect or stop a child run`() = runBlocking {
        val result = call(startInput())
        val id = result["run_id"]!!.jsonPrimitive.content
        val unrelated = Conversation.ofId(Uuid.random(), actor.id)
        assertEquals("CAPABILITY_DENIED", call("""{"action":"get","run_id":"$id"}""", unrelated)["error"]!!.jsonPrimitive.content)
    }

    @Test fun `invalid continuation returns recovery fields without resuming or bypassing revision`() = runBlocking {
        ask = true
        val waiting = call(startInput())
        val id = waiting["run_id"]!!.jsonPrimitive.content
        val missing = call("""{"action":"continue","run_id":"$id","request_id":"followup","instruction":"2026"}""")
        assertEquals("IF_REVISION_REQUIRED", missing["error"]!!.jsonPrimitive.content)
        assertEquals("answer", missing["next_action"]!!.jsonPrimitive.content)
        assertEquals(waiting["revision"], missing["revision"])
        assertEquals(waiting["questions"], missing["questions"])
        assertTrue(missing["required_fields"]!!.jsonArray.contains(JsonPrimitive("question_id")))
        val stale = call("""{"action":"answer","run_id":"$id","request_id":"answer","if_revision":0,"question_id":"question","instruction":"2026"}""")
        assertEquals("REVISION_CONFLICT", stale["error"]!!.jsonPrimitive.content)
        val wrongAction = call("""{"action":"continue","run_id":"$id","request_id":"followup","if_revision":1,"instruction":"2026"}""")
        assertEquals("INVALID_RUN_STATE", wrongAction["error"]!!.jsonPrimitive.content)
        assertEquals(0, resumes)
        assertEquals(waiting["revision"], call("""{"action":"get","run_id":"$id"}""")["revision"])
        assertEquals("COMPLETED", call("""{"action":"answer","run_id":"$id","request_id":"answer","if_revision":1,"question_id":"question","instruction":"2026"}""")["status"]!!.jsonPrimitive.content)
        assertEquals(1, resumes)
    }

    @Test fun `recovery preserves history and requires explicit retry`() = runBlocking {
        val pending = AgentRun(Uuid.random().toString(), parent.id.toString(), "seed", child.id.toString(), "{}")
        dao.insert(pending); dao.recover()
        assertEquals("INTERRUPTED", dao.get(pending.id)!!.status)
        assertEquals(0, starts)
        assertEquals(0, dao.resume(pending.id, 0, "stale"))
        assertEquals(1, dao.resume(pending.id, 1, "retry"))
    }
}

private class FakeRunDao : AgentRunDao {
    private val rows = MutableStateFlow<Map<String, AgentRun>>(emptyMap())
    private fun set(row: AgentRun) { rows.value = rows.value + (row.id to row) }
    override suspend fun insert(run: AgentRun) { check(findRequest(run.parentConversationId, run.requestId) == null); set(run) }
    override suspend fun get(id: String) = rows.value[id]
    override suspend fun findRequest(parent: String, request: String) = rows.value.values.find { it.parentConversationId == parent && it.requestId == request }
    override suspend fun list(parent: String) = rows.value.values.filter { it.parentConversationId == parent }
    override fun observe(id: String) = rows.map { it[id] }
    override suspend fun finish(id: String, status: String, now: Long) { get(id)?.takeIf { it.status == "RUNNING" }?.let { set(it.copy(status = status, revision = it.revision + 1)) } }
    override suspend fun failIfRunning(id: String, revision: Long) { get(id)?.takeIf { it.revision == revision }?.let { finish(id, "FAILED", 0) } }
    override suspend fun stop(id: String) { get(id)?.let { set(it.copy(status = "STOPPED", revision = it.revision + 1)) } }
    override suspend fun recover() { rows.value.values.filter { it.status == "RUNNING" }.forEach { set(it.copy(status = "INTERRUPTED", revision = it.revision + 1)) } }
    override suspend fun resume(id: String, revision: Long, command: String, now: Long): Int {
        val old = get(id)?.takeIf { it.revision == revision && it.status != "RUNNING" } ?: return 0
        set(old.copy(status = "RUNNING", revision = revision + 1, lastCommandId = command)); return 1
    }
}
