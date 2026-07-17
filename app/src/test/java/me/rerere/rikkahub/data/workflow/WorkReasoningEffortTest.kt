package me.rerere.rikkahub.data.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考深度的两条下发通道互斥：Claude 走 spawn 环境变量，Codex 走 spawn effortLevel + 消息 meta。
 */
class WorkReasoningEffortTest {
    @Test
    fun `claude effort goes through spawn environment only`() {
        assertEquals(
            mapOf("CLAUDE_CODE_EFFORT_LEVEL" to "xhigh"),
            spawnEnvironment(WorkAgent.CLAUDE, "xhigh"),
        )
        assertNull(spawnEffortLevel(WorkAgent.CLAUDE, "xhigh"))
        assertNull(messageReasoningEffort(WorkAgent.CLAUDE, "xhigh"))
    }

    @Test
    fun `codex effort goes through spawn param and message meta only`() {
        assertEquals(emptyMap<String, String>(), spawnEnvironment(WorkAgent.CODEX, "high"))
        assertEquals("high", spawnEffortLevel(WorkAgent.CODEX, "high"))
        assertEquals("high", messageReasoningEffort(WorkAgent.CODEX, "high"))
    }

    @Test
    fun `codex accepts ultra which claude does not`() {
        assertEquals("ultra", spawnEffortLevel(WorkAgent.CODEX, "ultra"))
        assertEquals(emptyMap<String, String>(), spawnEnvironment(WorkAgent.CLAUDE, "ultra"))
    }

    @Test
    fun `unknown level is dropped rather than forwarded`() {
        assertNull(spawnEffortLevel(WorkAgent.CODEX, "turbo"))
        assertNull(messageReasoningEffort(WorkAgent.CODEX, "turbo"))
        assertEquals(emptyMap<String, String>(), spawnEnvironment(WorkAgent.CLAUDE, "turbo"))
    }

    @Test
    fun `null effort yields no channel`() {
        assertEquals(emptyMap<String, String>(), spawnEnvironment(WorkAgent.CLAUDE, null))
        assertNull(spawnEffortLevel(WorkAgent.CODEX, null))
        assertNull(messageReasoningEffort(WorkAgent.CODEX, null))
    }

    @Test
    fun `other agent has no effort channel at all`() {
        assertFalse(WorkReasoningEffort.isRemotelyApplicable(WorkAgent.OTHER))
        assertTrue(WorkReasoningEffort.levelsFor(WorkAgent.OTHER).isEmpty())
        assertEquals(emptyMap<String, String>(), spawnEnvironment(WorkAgent.OTHER, "high"))
        assertNull(spawnEffortLevel(WorkAgent.OTHER, "high"))
    }

    @Test
    fun `both codex and claude are remotely applicable`() {
        assertTrue(WorkReasoningEffort.isRemotelyApplicable(WorkAgent.CLAUDE))
        assertTrue(WorkReasoningEffort.isRemotelyApplicable(WorkAgent.CODEX))
    }
}
