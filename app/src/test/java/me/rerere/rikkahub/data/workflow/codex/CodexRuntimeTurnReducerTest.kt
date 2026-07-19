package me.rerere.rikkahub.data.workflow.codex

import me.rerere.rikkahub.data.db.entity.CodexTurnEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexRuntimeTurnReducerTest {
    @Test
    fun `completion preserves start time and computes a non-negative duration`() {
        val current = CodexTurnEntity(
            machineId = "machine_1",
            threadId = "thread_1",
            turnId = "turn_1",
            status = "inProgress",
            startedAt = 2_000L,
            completedAt = null,
            durationMs = null,
            error = null,
            position = 3,
        )

        val completed = mergeRuntimeTurn(
            current = current,
            event = RuntimeEventPayload(
                machineId = "machine_1",
                threadId = "thread_1",
                eventId = "event_2",
                type = "turn.completed",
                at = 1_000L,
                turnId = "turn_1",
                status = "completed",
            ),
            position = current.position,
        )

        assertEquals(2_000L, completed.startedAt)
        assertEquals(1_000L, completed.completedAt)
        assertEquals(0L, completed.durationMs)
        assertEquals(3, completed.position)
    }

    @Test
    fun `start creates the timestamp once`() {
        val started = mergeRuntimeTurn(
            current = null,
            event = RuntimeEventPayload(
                machineId = "machine_1",
                threadId = "thread_1",
                eventId = "event_1",
                type = "turn.started",
                at = 10L,
                turnId = "turn_1",
            ),
            position = 0,
        )

        assertEquals(10L, started.startedAt)
        assertNull(started.completedAt)
    }
}
