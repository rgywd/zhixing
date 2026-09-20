package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import org.junit.Assert.*
import org.junit.Test

class WorkConfigTransferTest {
    @Test fun transferExcludesChatAndBackupCredentialsAndPreservesDestinationState() {
        val source = Settings(themeId = "test-theme", webDavConfig = WebDavConfig(password = "must-not-leave-chat"), webServerAccessPassword = "private-web-server")
        val target = Settings(launchCount = 7, themeId = "old")
        val exported = Json.encodeToString(WorkConfigTransfer.from(source, "https://work.example.com", "sample-work-token"))
        assertFalse(exported.contains("must-not-leave-chat"))
        assertFalse(exported.contains("private-web-server"))
        assertFalse(exported.contains("assistants"))
        val imported = Json.decodeFromString<WorkConfigTransfer>(exported).applyTo(target)
        assertEquals("test-theme", imported.themeId)
        assertEquals(target.assistantId, imported.assistantId)
        assertEquals(7, imported.launchCount)
        assertEquals(target.webDavConfig, imported.webDavConfig)
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompatibleTransferDoesNotSilentlyApply() {
        WorkConfigTransfer.from(Settings(), "", "").copy(version = 2).applyTo(Settings())
    }
}
