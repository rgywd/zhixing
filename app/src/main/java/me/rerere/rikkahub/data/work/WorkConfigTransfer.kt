package me.rerere.rikkahub.data.work

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

/** Only portable Work settings cross the application boundary. No chat data or private file paths. */
@Serializable
data class WorkConfigTransfer(
    val version: Int = 1,
    val baseUrl: String = "",
    val token: String = "",
    val asrProviders: List<ASRProviderSetting>,
    val selectedASRProviderId: Uuid?,
    val ttsProviders: List<TTSProviderSetting>,
    val selectedTTSProviderId: Uuid,
    val dynamicColor: Boolean,
    val themeId: String,
    val customThemes: List<CustomTheme> = emptyList(),
    val colorMode: String = "SYSTEM",
    val amoledDark: Boolean = false,
    val repoPreferences: PhoneWorkRepoPreferences = PhoneWorkRepoPreferences(),
    val providers: List<ProviderSetting> = emptyList(),
    val fastModelId: Uuid? = null,
    val titlePrompt: String? = null,
) {
    fun applyTo(target: Settings): Settings {
        require(version == 1) { "请更新两个 App 后重试" }
        return target.copy(
            asrProviders = asrProviders,
            selectedASRProviderId = selectedASRProviderId,
            ttsProviders = ttsProviders,
            selectedTTSProviderId = selectedTTSProviderId,
            dynamicColor = dynamicColor,
            themeId = themeId,
            customThemes = customThemes,
            providers = providers,
            fastModelId = fastModelId ?: target.fastModelId,
            titlePrompt = titlePrompt ?: target.titlePrompt,
        )
    }

    companion object {
        fun from(settings: Settings, baseUrl: String, token: String) = WorkConfigTransfer(
            baseUrl = baseUrl, token = token,
            asrProviders = settings.asrProviders, selectedASRProviderId = settings.selectedASRProviderId,
            ttsProviders = settings.ttsProviders, selectedTTSProviderId = settings.selectedTTSProviderId,
            dynamicColor = settings.dynamicColor, themeId = settings.themeId,
            customThemes = settings.customThemes,
            providers = settings.providers, fastModelId = settings.fastModelId, titlePrompt = settings.titlePrompt,
        )
    }
}
