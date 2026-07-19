package me.rerere.rikkahub.data.workflow.codex

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexRuntimeSettingsReducerTest {
    @Test
    fun `settings token usage and model reroute merge into the UI state without losing fields`() {
        val settings = mergeRuntimeSettings(
            current = null,
            event = RuntimeEventPayload(
                machineId = "machine_1",
                threadId = "thread_1",
                eventId = "settings_1",
                type = "thread.settings",
                at = 1,
                model = "gpt-5.4",
                effort = "high",
                serviceTier = "priority",
                permissions = ":workspace",
            ),
        )
        val withUsage = mergeRuntimeSettings(
            current = settings,
            event = RuntimeEventPayload(
                machineId = "machine_1",
                threadId = "thread_1",
                eventId = "usage_1",
                type = "token.usage",
                at = 2,
                usedTokens = 50_000,
                contextWindow = 100_000,
            ),
        )
        val rerouted = mergeRuntimeSettings(
            current = withUsage,
            event = RuntimeEventPayload(
                machineId = "machine_1",
                threadId = "thread_1",
                eventId = "reroute_1",
                type = "thread.settings",
                at = 3,
                model = "gpt-5.4-mini",
            ),
        ).toModel()

        assertEquals("gpt-5.4-mini", rerouted.model)
        assertEquals("high", rerouted.effort)
        assertEquals("priority", rerouted.serviceTier)
        assertEquals(":workspace", rerouted.permissions)
        assertEquals(50, rerouted.contextPercent)
        assertEquals(3, rerouted.updatedAt)
    }
}
