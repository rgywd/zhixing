package me.rerere.rikkahub.service

import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.work.PhoneWorkAnswer
import me.rerere.rikkahub.data.work.PhoneWorkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneWorkQuickEntryTest {

    // recommendedAnswersFromAskPayload

    @Test
    fun `single question maps recommended option into answer`() {
        val answers = recommendedAnswersFromAskPayload(
            askPayload(
                """
                {"id":"deployTarget","header":"部署位置","question":"部署到哪？","options":[
                  {"id":"cpa","label":"CPA"},
                  {"id":"vps","label":"myVPS"}
                ],"recommendedOptionIds":["cpa"]}
                """,
            ),
        )

        assertEquals(
            listOf(PhoneWorkAnswer(questionId = "deployTarget", selectedOptionIds = listOf("cpa"))),
            answers,
        )
    }

    @Test
    fun `multi select keeps every recommended option and question order`() {
        val answers = recommendedAnswersFromAskPayload(
            askPayload(
                """
                {"id":"scope","header":"改动范围","multiSelect":true,"question":"改哪些模块？","options":[
                  {"id":"app","label":"App"},
                  {"id":"core","label":"Core"},
                  {"id":"docs","label":"Docs"}
                ],"recommendedOptionIds":["app","core"]}
                """,
                """
                {"id":"release","header":"发布","question":"是否发布？","options":[
                  {"id":"yes","label":"发布"},
                  {"id":"no","label":"暂不"}
                ],"recommendedOptionIds":["no"]}
                """,
            ),
        )

        assertEquals(
            listOf(
                PhoneWorkAnswer(questionId = "scope", selectedOptionIds = listOf("app", "core")),
                PhoneWorkAnswer(questionId = "release", selectedOptionIds = listOf("no")),
            ),
            answers,
        )
    }

    @Test
    fun `question without recommendation disables quick action`() {
        val answers = recommendedAnswersFromAskPayload(
            askPayload(
                """
                {"id":"target","header":"目标","question":"去哪？","options":[
                  {"id":"a","label":"A"}
                ],"recommendedOptionIds":["a"]}
                """,
                """
                {"id":"other","header":"其他","question":"另一个？","options":[
                  {"id":"x","label":"X"}
                ]}
                """,
            ),
        )

        assertNull(answers)
    }

    @Test
    fun `empty or malformed payload disables quick action`() {
        assertNull(
            recommendedAnswersFromAskPayload(
                workQuickActionJson.parseToJsonElement("""{"askId":"a1","questions":[]}""").jsonObject,
            ),
        )
        assertNull(
            recommendedAnswersFromAskPayload(
                workQuickActionJson.parseToJsonElement("""{"askId":"a1"}""").jsonObject,
            ),
        )
    }

    // buildWorkShortcutSpecs

    @Test
    fun `waiting sessions rank before running and queued`() {
        val specs = buildWorkShortcutSpecs(
            active = listOf(
                session("run", status = "RUNNING", updatedAt = "2026-08-18T03:00:00Z"),
                session("queue", status = "QUEUED", updatedAt = "2026-08-18T02:00:00Z"),
                session("wait", status = "WAITING_FOR_USER", updatedAt = "2026-08-18T01:00:00Z"),
            ),
            maxCount = 3,
        )

        assertEquals(listOf("wait", "run", "queue"), specs.map { it.sessionId })
        assertEquals(listOf(0, 1, 2), specs.map { it.rank })
    }

    @Test
    fun `same status prefers most recently updated session`() {
        val specs = buildWorkShortcutSpecs(
            active = listOf(
                session("older", status = "RUNNING", updatedAt = "2026-08-18T01:00:00Z"),
                session("newer", status = "RUNNING", updatedAt = "2026-08-18T02:00:00Z"),
            ),
            maxCount = 3,
        )

        assertEquals(listOf("newer", "older"), specs.map { it.sessionId })
    }

    @Test
    fun `spec count respects launcher capacity`() {
        val active = (1..5).map { session("s$it", status = "RUNNING", updatedAt = "2026-08-18T0${it}:00:00Z") }

        assertEquals(2, buildWorkShortcutSpecs(active, maxCount = 2).size)
        assertEquals(emptyList<WorkShortcutSpec>(), buildWorkShortcutSpecs(active, maxCount = 0))
        assertEquals(emptyList<WorkShortcutSpec>(), buildWorkShortcutSpecs(emptyList(), maxCount = 3))
    }

    @Test
    fun `labels carry repo status and optional title`() {
        val waiting = session("wait", repoName = "zhixing", status = "WAITING_FOR_USER")
        val titled = session(
            "titled",
            repoName = "zhixing",
            status = "RUNNING",
            title = "修复登录闪退",
        )

        val specs = buildWorkShortcutSpecs(listOf(titled, waiting), maxCount = 2)

        assertEquals("zhixing · 等你回答", specs[0].shortLabel)
        assertEquals("zhixing · 等你回答", specs[0].longLabel)
        assertEquals("zhixing · 进行中", specs[1].shortLabel)
        assertEquals("zhixing · 修复登录闪退 · 进行中", specs[1].longLabel)
        assertEquals("work_session_wait", specs[0].shortcutId)
    }

    private fun askPayload(vararg questions: String) = workQuickActionJson.parseToJsonElement(
        """{"askId":"ask_1","deadlineAt":"2026-08-18T03:00:00Z","questions":[${questions.joinToString(",")}]}""",
    ).jsonObject

    private fun session(
        id: String,
        repoName: String = id,
        status: String = "RUNNING",
        title: String = "",
        updatedAt: String = "2026-08-18T00:00:00Z",
    ) = PhoneWorkSession(
        id = id,
        runnerId = "runner",
        repoId = "repo-$id",
        repoName = repoName,
        title = title,
        model = "gpt-5.6",
        reasoningEffort = "high",
        status = status,
        createdAt = "2026-08-18T00:00:00Z",
        updatedAt = updatedAt,
    )
}
