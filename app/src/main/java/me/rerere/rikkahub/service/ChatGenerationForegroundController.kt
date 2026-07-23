package me.rerere.rikkahub.service

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

private const val TAG = "ChatGenerationFg"

/**
 * Process-local owner of foreground-service leases for normal Chat generation.
 *
 * A lease is acquired synchronously from the user-initiated call path, before the generation
 * coroutine starts. This keeps foreground-service startup inside Android's allowed window.
 */
class ChatGenerationForegroundController(
    private val application: Application,
) {
    private val registry = ChatGenerationLeaseRegistry()
    private val _state = MutableStateFlow(registry.snapshot())
    internal val state: StateFlow<ChatGenerationForegroundState> = _state.asStateFlow()

    @Synchronized
    fun acquire(generationId: Uuid, conversationId: Uuid) {
        val previous = _state.value
        val current = registry.acquire(generationId, conversationId)
        _state.value = current
        if (!previous.isActive && current.isActive) {
            runCatching {
                ContextCompat.startForegroundService(
                    application,
                    Intent(application, ChatGenerationForegroundService::class.java),
                )
            }.onFailure {
                // Generation remains usable even if an OEM rejects foreground-service startup.
                Log.e(TAG, "Unable to start Chat generation foreground service", it)
            }
        }
    }

    @Synchronized
    fun release(generationId: Uuid) {
        val previous = _state.value
        val current = registry.release(generationId)
        _state.value = current
        if (previous.isActive && !current.isActive) {
            application.stopService(
                Intent(application, ChatGenerationForegroundService::class.java),
            )
        }
    }
}
