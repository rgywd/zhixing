package me.rerere.rikkahub.ui.pages.workflow.happy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 只保存非敏感的中继 origin；账户 secret/token 仍由 [HappyCredentialsStore] 加密保存。 */
class HappyRelaySettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _serverUrl = MutableStateFlow(
        runCatching {
            HappyProtocol.normalizeServerUrl(
                preferences.getString(SERVER_URL_KEY, HappyProtocol.SERVER_URL)
                    ?: HappyProtocol.SERVER_URL
            )
        }.getOrDefault(HappyProtocol.SERVER_URL)
    )

    val serverUrl = _serverUrl.asStateFlow()

    fun updateServerUrl(raw: String): String {
        val normalized = HappyProtocol.normalizeServerUrl(raw)
        check(preferences.edit().putString(SERVER_URL_KEY, normalized).commit()) {
            "无法保存中继服务器地址"
        }
        _serverUrl.value = normalized
        return normalized
    }

    private companion object {
        const val PREFERENCES_NAME = "zhixing_happy_relay"
        const val SERVER_URL_KEY = "server_url"
    }
}
