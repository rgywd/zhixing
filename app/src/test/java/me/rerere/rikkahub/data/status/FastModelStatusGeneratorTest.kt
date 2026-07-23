package me.rerere.rikkahub.data.status

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class FastModelStatusGeneratorTest {
    @Test
    fun selectsConfiguredFastModelInsteadOfMainChatModel() {
        val chatModel = Model(modelId = "main", id = Uuid.random())
        val fastModel = Model(modelId = "codex-spark", id = Uuid.random())
        val provider = ProviderSetting.OpenAI(models = listOf(chatModel, fastModel))
        val settings = Settings(
            chatModelId = chatModel.id,
            fastModelId = fastModel.id,
            providers = listOf(provider),
        )

        val selection = selectStatusFastModel(settings)

        assertEquals(fastModel.id, selection?.model?.id)
        assertEquals("codex-spark", selection?.model?.modelId)
    }

    @Test
    fun missingFastModelDoesNotFallBackToMainChatModel() {
        val chatModel = Model(modelId = "main", id = Uuid.random())
        val settings = Settings(
            chatModelId = chatModel.id,
            fastModelId = Uuid.random(),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(chatModel))),
        )

        assertNull(selectStatusFastModel(settings))
    }

    @Test
    fun disabledFastModelProviderIsNotUsed() {
        val fastModel = Model(modelId = "fast", id = Uuid.random())
        val settings = Settings(
            fastModelId = fastModel.id,
            providers = listOf(
                ProviderSetting.OpenAI(
                    enabled = false,
                    models = listOf(fastModel),
                )
            ),
        )

        assertNull(selectStatusFastModel(settings))
    }
}
