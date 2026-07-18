package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemoryState
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstantPretty

internal fun selectMemoriesForPrompt(memories: List<AssistantMemory>): List<AssistantMemory> {
    val active = memories.filter { it.state == MemoryState.ACTIVE }
    return active.filter { it.kind == MemoryKind.PROFILE }
        .take(MemoryRepository.PROFILE_PROMPT_LIMIT) +
        active.filter { it.kind == MemoryKind.CONTEXT }
            .take(MemoryRepository.CONTEXT_PROMPT_LIMIT)
}

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        val selected = selectMemoriesForPrompt(memories)
        val profile = selected.filter { it.kind == MemoryKind.PROFILE }
        val context = selected.filter { it.kind == MemoryKind.CONTEXT }
        appendLine()
        appendLine("**Long-term memory**")
        appendLine(
            "The user profile contains stable global facts and preferences. " +
                "Remembered context contains scoped, changeable information. " +
                "Use record IDs when correcting existing memories; do not create contradictory duplicates."
        )
        appendLine("User profile:")
        appendLine(JsonInstantPretty.encodeToString(buildMemoryArray(profile)))
        appendLine("Remembered context:")
        append(JsonInstantPretty.encodeToString(buildMemoryArray(context)))
        appendLine()
    }

private fun buildMemoryArray(memories: List<AssistantMemory>) = buildJsonArray {
    memories.forEach { memory ->
        add(buildJsonObject {
            put("id", memory.id)
            put("content", memory.content)
        })
    }
}
