package me.rerere.rikkahub.data.workflow.codex

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.entity.CodexCatalogChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogChunkAssemblerTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val machine = CatalogMachinePayload(
        machineId = "machine_1",
        displayName = "Minecraft",
        platformFamily = "windows",
        platformOs = "windows",
        agentVersion = "0.2.0",
        runtimeWritable = true,
        lastSeenAt = 100,
    )

    @Test
    fun assemblesContiguousChunksOnlyAfterFullHashMatches() {
        val first = content(projects = listOf(project()), threads = listOf(thread("thread_1")))
        val second = content(threads = listOf(thread("thread_2")))
        val chunks = chunks(first, second)

        val snapshot = CatalogChunkAssembler.assemble(chunks, json)

        assertEquals(1, snapshot.projects.size)
        assertEquals(listOf("thread_1", "thread_2"), snapshot.threads.map { it.threadId })
    }

    @Test
    fun rejectsTamperedChunkWithoutReplacingSnapshot() {
        val chunks = chunks(content(threads = listOf(thread("thread_1"))))
        val tampered = chunks.single().copy(contentBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray()))

        assertThrows(IllegalArgumentException::class.java) {
            CatalogChunkAssembler.assemble(listOf(tampered), json)
        }
    }

    private fun chunks(vararg contents: CatalogSnapshotChunkContent): List<CodexCatalogChunkEntity> {
        val raw = contents.map { json.encodeToString(it).toByteArray() }
        val digest = MessageDigest.getInstance("SHA-256")
        raw.forEach(digest::update)
        val contentHash = digest.digest().toHex()
        return raw.mapIndexed { index, bytes ->
            CodexCatalogChunkEntity(
                snapshotId = "snapshot_1",
                chunkIndex = index,
                chunkCount = raw.size,
                revision = 7,
                generatedAt = 100,
                machineJson = json.encodeToString(machine),
                contentHash = contentHash,
                chunkHash = bytes.sha256(),
                contentBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                receivedAt = 100,
            )
        }
    }

    private fun content(
        projects: List<CatalogProjectPayload> = emptyList(),
        threads: List<CatalogThreadPayload> = emptyList(),
    ) = CatalogSnapshotChunkContent(projects, threads)

    private fun project() = CatalogProjectPayload(
        projectId = "project_1",
        machineId = "machine_1",
        displayName = "zhixing",
        canonicalRoot = "C:/zhixing",
        vcs = CatalogVcsPayload("git"),
        updatedAt = 100,
    )

    private fun thread(id: String) = CatalogThreadPayload(
        machineId = "machine_1",
        threadId = id,
        projectId = "project_1",
        name = id,
        preview = "preview",
        createdAt = 100,
        updatedAt = 100,
        recencyAt = 100,
        archived = false,
        source = "appServer",
        isSubagent = false,
        isAutomation = false,
        runtimeState = "unknown",
        rawStatus = "unknown",
    )
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
