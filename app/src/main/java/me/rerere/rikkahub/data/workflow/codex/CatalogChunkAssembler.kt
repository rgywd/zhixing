package me.rerere.rikkahub.data.workflow.codex

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.entity.CodexCatalogChunkEntity

internal object CatalogChunkAssembler {
    fun assemble(chunks: List<CodexCatalogChunkEntity>, json: Json): CatalogSnapshotPayload {
        require(chunks.isNotEmpty()) { "catalog chunks are empty" }
        val first = chunks.first()
        require(chunks.size == first.chunkCount)
        require(chunks.map { it.chunkIndex } == (0 until first.chunkCount).toList()) {
            "catalog chunks are not contiguous"
        }
        require(chunks.all {
            it.chunkCount == first.chunkCount && it.revision == first.revision &&
                it.generatedAt == first.generatedAt && it.contentHash == first.contentHash &&
                it.machineJson == first.machineJson
        }) { "catalog chunk metadata mismatch" }
        val decoded = chunks.map { stored ->
            Base64.getUrlDecoder().decode(stored.contentBase64).also { bytes ->
                require(bytes.sha256() == stored.chunkHash) { "stored catalog chunk hash mismatch" }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        decoded.forEach(digest::update)
        require(digest.digest().toHex() == first.contentHash) { "catalog snapshot hash mismatch" }
        val contents = decoded.map { json.decodeFromString<CatalogSnapshotChunkContent>(it.toString(Charsets.UTF_8)) }
        return CatalogSnapshotPayload(
            revision = first.revision,
            generatedAt = first.generatedAt,
            machine = json.decodeFromString(first.machineJson),
            projects = contents.flatMap(CatalogSnapshotChunkContent::projects),
            threads = contents.flatMap(CatalogSnapshotChunkContent::threads),
        )
    }
}

internal object ThreadDetailChunkAssembler {
    fun assemble(chunks: List<CodexCatalogChunkEntity>, json: Json): ThreadDetailPayload {
        require(chunks.isNotEmpty()) { "thread detail chunks are empty" }
        val first = chunks.first()
        require(chunks.size == first.chunkCount)
        require(chunks.map { it.chunkIndex } == (0 until first.chunkCount).toList()) {
            "thread detail chunks are not contiguous"
        }
        require(chunks.all {
            it.chunkCount == first.chunkCount && it.contentHash == first.contentHash &&
                it.machineJson == first.machineJson
        }) { "thread detail chunk metadata mismatch" }
        val decoded = chunks.map { stored ->
            Base64.getUrlDecoder().decode(stored.contentBase64).also { bytes ->
                require(bytes.sha256() == stored.chunkHash) { "stored thread detail chunk hash mismatch" }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        decoded.forEach(digest::update)
        require(digest.digest().toHex() == first.contentHash) { "thread detail hash mismatch" }
        val target = first.machineJson.split('\n', limit = 2)
        require(target.size == 2) { "thread detail target metadata is invalid" }
        return json.decodeFromString<ThreadDetailPayload>(
            decoded.fold(ByteArray(0)) { result, bytes -> result + bytes }.toString(Charsets.UTF_8)
        ).also { detail ->
            require(detail.machineId == target[0] && detail.threadId == target[1]) {
                "thread detail target mismatch"
            }
        }
    }
}

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
