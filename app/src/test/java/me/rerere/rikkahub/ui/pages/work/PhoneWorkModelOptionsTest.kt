package me.rerere.rikkahub.ui.pages.work

import me.rerere.rikkahub.data.work.PhoneWorkRuntime
import me.rerere.rikkahub.data.work.effectiveReasoningEfforts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneWorkModelOptionsTest {
    @Test
    fun `default Codex catalog exposes four current models without ultra`() {
        assertEquals(
            listOf(
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "gpt-5.3-codex-spark",
            ),
            PhoneWorkSessionVM.DEFAULT_MODELS,
        )
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            PhoneWorkSessionVM.DEFAULT_EFFORTS,
        )
        assertFalse(PhoneWorkSessionVM.DEFAULT_EFFORTS.contains("ultra"))
    }

    @Test
    fun `Spark uses its model-specific reasoning effort subset`() {
        val runtime = PhoneWorkRuntime(
            id = "codex",
            name = "Codex",
            models = PhoneWorkSessionVM.DEFAULT_MODELS,
            reasoningEfforts = PhoneWorkSessionVM.DEFAULT_EFFORTS,
            reasoningEffortsByModel = PhoneWorkSessionVM.DEFAULT_REASONING_EFFORTS_BY_MODEL,
        )

        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            runtime.effectiveReasoningEfforts(PhoneWorkSessionVM.SPARK_MODEL),
        )
        assertEquals(
            PhoneWorkSessionVM.DEFAULT_EFFORTS,
            runtime.effectiveReasoningEfforts("gpt-5.6-luna"),
        )
    }
}
