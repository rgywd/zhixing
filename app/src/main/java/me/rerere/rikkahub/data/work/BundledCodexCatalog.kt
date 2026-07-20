package me.rerere.rikkahub.data.work

import me.rerere.rikkahub.data.workflow.codex.CodexModelOption
import me.rerere.rikkahub.data.workflow.codex.CodexReasoningOption
import me.rerere.rikkahub.data.workflow.codex.CodexServiceTier

/** Last verified against Codex App Server 0.144.0 on 2026-07-19. */
object BundledCodexCatalog {
    val models: List<CodexModelOption> = listOf(
        model(
            id = "gpt-5.6-sol",
            displayName = "GPT-5.6-Sol",
            description = "Latest frontier agentic coding model.",
            defaultEffort = "low",
            efforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
            isDefault = true,
        ),
        model(
            id = "gpt-5.6-terra",
            displayName = "GPT-5.6-Terra",
            description = "Balanced agentic coding model for everyday work.",
            defaultEffort = "medium",
            efforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
        ),
        model(
            id = "gpt-5.6-luna",
            displayName = "GPT-5.6-Luna",
            description = "Fast and affordable agentic coding model.",
            defaultEffort = "medium",
            efforts = listOf("low", "medium", "high", "xhigh", "max"),
        ),
        model(
            id = "gpt-5.5",
            displayName = "GPT-5.5",
            description = "Frontier model for complex coding, research, and real-world work.",
            defaultEffort = "medium",
            efforts = listOf("low", "medium", "high", "xhigh"),
        ),
        model(
            id = "gpt-5.3-codex-spark",
            displayName = "GPT-5.3-Codex-Spark",
            description = "Ultra-fast coding model.",
            defaultEffort = "high",
            efforts = listOf("low", "medium", "high", "xhigh"),
            inputModalities = listOf("text"),
            fast = false,
        ),
    )

    /** Server values win, while bundled entries remain available during a failed/partial refresh. */
    fun merge(serverModels: List<CodexModelOption>?): List<CodexModelOption> {
        if (serverModels.isNullOrEmpty()) return models
        val serverById = serverModels.associateBy(CodexModelOption::id)
        return models.map { serverById[it.id] ?: it } + serverModels.filter { it.id !in models.map(CodexModelOption::id) }
    }

    private fun model(
        id: String,
        displayName: String,
        description: String,
        defaultEffort: String,
        efforts: List<String>,
        isDefault: Boolean = false,
        inputModalities: List<String> = listOf("text", "image"),
        fast: Boolean = true,
    ) = CodexModelOption(
        id = id,
        model = id,
        displayName = displayName,
        description = description,
        isDefault = isDefault,
        defaultReasoningEffort = defaultEffort,
        supportedReasoningEfforts = efforts.map { CodexReasoningOption(it) },
        inputModalities = inputModalities,
        serviceTiers = if (fast) listOf(CodexServiceTier("priority", "Fast", "1.5x speed, increased usage")) else emptyList(),
    )
}
