package me.rerere.rikkahub.data.workflow.codex

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.entity.CodexCatalogChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThreadDetailChunkAssemblerTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @Test
    fun `assembles out of arrival order only after stored chunks are contiguous`() {
        val detail = detail()
        val raw = json.encodeToString(detail).toByteArray()
        val midpoint = raw.size / 2
        val chunks = storedChunks(listOf(raw.copyOfRange(0, midpoint), raw.copyOfRange(midpoint, raw.size)))

        val assembled = ThreadDetailChunkAssembler.assemble(chunks.reversed().sortedBy { it.chunkIndex }, json)

        assertEquals("thread_1", assembled.threadId)
        assertEquals("hello", assembled.turns.single().items.single().text)
    }

    @Test
    fun `rejects missing or conflicting chunks before replacing history`() {
        val raw = json.encodeToString(detail()).toByteArray()
        val midpoint = raw.size / 2
        val chunks = storedChunks(listOf(raw.copyOfRange(0, midpoint), raw.copyOfRange(midpoint, raw.size)))

        assertThrows(IllegalArgumentException::class.java) {
            ThreadDetailChunkAssembler.assemble(chunks.take(1), json)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThreadDetailChunkAssembler.assemble(listOf(chunks[0], chunks[1].copy(contentHash = "wrong")), json)
        }
    }

    private fun storedChunks(parts: List<ByteArray>): List<CodexCatalogChunkEntity> {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        val fullHash = digest.digest().joinToString("") { "%02x".format(it) }
        return parts.mapIndexed { index, bytes ->
            CodexCatalogChunkEntity(
                snapshotId = "detail:1",
                chunkIndex = index,
                chunkCount = parts.size,
                revision = 0,
                generatedAt = 1,
                machineJson = "machine_1\nthread_1",
                contentHash = fullHash,
                chunkHash = bytes.sha256(),
                contentBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                receivedAt = 1,
            )
        }
    }

    private fun detail() = ThreadDetailPayload(
        machineId = "machine_1",
        threadId = "thread_1",
        turns = listOf(
            CatalogTurnPayload(
                turnId = "turn_1",
                status = "completed",
                items = listOf(
                    CatalogItemPayload(
                        itemId = "item_1",
                        type = "agentMessage",
                        rawType = "agentMessage",
                        role = "agent",
                        text = "hello",
                    )
                ),
            )
        ),
    )
}
