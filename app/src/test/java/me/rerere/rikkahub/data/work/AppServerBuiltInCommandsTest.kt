package me.rerere.rikkahub.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppServerBuiltInCommandsTest {
    @Test
    fun `exact native commands resolve to app server actions`() {
        assertEquals("new", AppServerBuiltInCommands.exact(" /new ")?.name)
        assertEquals("compact", AppServerBuiltInCommands.exact("/compact")?.name)
    }

    @Test
    fun `commands embedded in prompts remain model text`() {
        assertNull(AppServerBuiltInCommands.exact("/compact after this answer"))
        assertNull(AppServerBuiltInCommands.exact("please /new"))
        assertNull(AppServerBuiltInCommands.exact("new"))
        assertNull(AppServerBuiltInCommands.exact("/unknown"))
    }
}
