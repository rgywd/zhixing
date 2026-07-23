package me.rerere.rikkahub.ui.pages.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class AgendaDrawerStructureTest {
    @Test
    fun `life overview does not own device controls or protocol diagnostics`() {
        val source = agendaDrawerSource()

        listOf(
            "WatchProbeCard(",
            "watchProbe.start(",
            "watchProbe.disconnect(",
            "watchProbe::sync",
            "state.address",
            "state.lastFrameHex",
            "state.statusText",
        ).forEach { forbidden ->
            assertFalse(
                "AgendaDrawer must not contain device concern: $forbidden",
                source.contains(forbidden),
            )
        }
    }

    private fun agendaDrawerSource(): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val relativePath = "src/main/java/me/rerere/rikkahub/ui/pages/chat/AgendaDrawer.kt"
        val source = sequenceOf(
            File(workingDirectory, relativePath),
            File(workingDirectory, "app/$relativePath"),
        ).firstOrNull(File::isFile)
        checkNotNull(source) { "Unable to locate AgendaDrawer.kt from $workingDirectory" }
        return source.readText()
    }
}
