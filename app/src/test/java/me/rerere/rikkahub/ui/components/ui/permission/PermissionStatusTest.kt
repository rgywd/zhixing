package me.rerere.rikkahub.ui.components.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionStatusTest {
    @Test
    fun `OS grant takes precedence over remembered rejection`() {
        assertEquals(PermissionStatus.Granted, resolvePermissionStatus(true, false, true))
    }

    @Test
    fun `restarting preserves permanent denial while allowing rationale and first request`() {
        assertEquals(PermissionStatus.DeniedPermanently, resolvePermissionStatus(false, false, true))
        assertEquals(PermissionStatus.Denied, resolvePermissionStatus(false, true, true))
        assertEquals(PermissionStatus.NotRequested, resolvePermissionStatus(false, false, false))
    }
}
