package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Serializes profile-maintenance writes with user-directed memory mutations.
 *
 * The context marker makes nested repository calls re-entrant while keeping unrelated
 * coroutines mutually exclusive.
 */
internal object ProfileMemoryMutationGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T {
        if (currentCoroutineContext()[GateContextKey] != null) {
            return block()
        }
        return mutex.withLock {
            withContext(GateContext) {
                block()
            }
        }
    }

    private object GateContextKey : CoroutineContext.Key<GateContext>

    private object GateContext : AbstractCoroutineContextElement(GateContextKey)
}
