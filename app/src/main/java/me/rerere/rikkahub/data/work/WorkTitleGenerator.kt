package me.rerere.rikkahub.data.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.service.backgroundTextGenerationParams
import me.rerere.rikkahub.service.normalizeWorkSessionTitle
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale

/** Work title generation only needs provider settings, never the Chat runtime or conversation database. */
class WorkTitleGenerator(private val settingsStore: SettingsStore, private val providers: ProviderManager) : PhoneWorkTitleGenerator {
    override suspend fun generate(message: String): String? {
        if (message.isBlank()) return null
        return try {
            val settings = settingsStore.settingsFlowRaw.first()
            val model = settings.findModelById(settings.fastModelId) ?: return null
            val provider = model.findProvider(settings.providers) ?: return null
            val result = providers.getProviderByType(provider).generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt = settings.titlePrompt.applyPlaceholders(
                    "locale" to Locale.getDefault().displayName, "content" to message.take(2_000),
                ))),
                params = backgroundTextGenerationParams(model),
            )
            normalizeWorkSessionTitle(result.choices.firstOrNull()?.message?.toText().orEmpty())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null // The existing session creator falls back to the first message when title generation is unavailable.
        }
    }
}
