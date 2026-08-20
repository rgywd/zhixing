package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContextWindowTest {
    @Test
    fun `missing context window decodes to 500k`() {
        val model = Json { ignoreUnknownKeys = true }
            .decodeFromString<Model>("""{"modelId":"chat-model"}""")

        assertEquals(500_000, model.contextWindowTokens)
    }

    @Test
    fun `configured context window survives serialization`() {
        val json = Json { encodeDefaults = true }
        val model = Model(modelId = "small-model", contextWindowTokens = 128_000)

        assertEquals(model, json.decodeFromString<Model>(json.encodeToString(model)))
    }
}
