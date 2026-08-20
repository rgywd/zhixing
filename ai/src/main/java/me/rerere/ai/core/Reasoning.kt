package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReasoningLevel(
    val budgetTokens: Int,
    val effort: String
) {
    @SerialName("off")
    OFF(0, "none"),
    /** Used by non-Chat budget settings and kept for older assistant settings decoding. */
    @SerialName("auto")
    AUTO(-1, "auto"),
    @SerialName("low")
    LOW(1_000, "low"),
    @SerialName("medium")
    MEDIUM(2_000, "medium"),
    @SerialName("high")
    HIGH(8_000, "high"),
    @SerialName("xhigh")
    XHIGH(16_000, "xhigh"),
    @SerialName("max")
    MAX(32_000, "max");

    val isEnabled: Boolean
        get() = this != OFF

    val normalizedForChat: ReasoningLevel
        get() = if (this == AUTO) MEDIUM else this

    companion object {
        val selectableEntries = listOf(OFF, LOW, MEDIUM, HIGH, XHIGH, MAX)

        fun fromBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            return entries.minByOrNull { kotlin.math.abs(it.budgetTokens - (budgetTokens ?: AUTO.budgetTokens)) } ?: AUTO
        }
    }
}
