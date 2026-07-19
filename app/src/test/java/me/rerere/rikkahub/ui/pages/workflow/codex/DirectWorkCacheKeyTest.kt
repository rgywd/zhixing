package me.rerere.rikkahub.ui.pages.workflow.codex

import me.rerere.rikkahub.data.work.AppServerCompatibilityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DirectWorkCacheKeyTest {
    @Test
    fun `direct cache is namespaced by connection instead of legacy machine id`() {
        assertEquals("direct:connection-1", directCacheMachineId("connection-1"))
        assertNotEquals("legacy-machine", directCacheMachineId("connection-1"))
        assertNotEquals(directCacheMachineId("connection-1"), directCacheMachineId("connection-2"))
    }

    @Test
    fun `write actions stay locked until a connected compatibility gate passes`() {
        assertEquals(false, directWorkWritable(true, AppServerCompatibilityLevel.READ_ONLY))
        assertEquals(false, directWorkWritable(true, AppServerCompatibilityLevel.INCOMPATIBLE))
        assertEquals(false, directWorkWritable(false, AppServerCompatibilityLevel.FULL))
        assertEquals(true, directWorkWritable(true, AppServerCompatibilityLevel.TEXT_ONLY))
        assertEquals(true, directWorkWritable(true, AppServerCompatibilityLevel.FULL))
    }

    @Test
    fun `write lease is invalidated by disconnect or a newer connection generation`() {
        val lease = DirectWorkWriteLease(4, 9)

        assertEquals(true, directWorkLeaseValid(true, 4, 9, lease))
        assertEquals(false, directWorkLeaseValid(false, 4, 9, lease))
        assertEquals(false, directWorkLeaseValid(true, 5, 9, lease))
        assertEquals(false, directWorkLeaseValid(true, 4, 10, lease))
    }
}
