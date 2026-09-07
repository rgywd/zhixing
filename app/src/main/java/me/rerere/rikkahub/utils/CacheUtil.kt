package me.rerere.rikkahub.utils

import java.util.concurrent.ConcurrentHashMap

class SimpleCache<K, V>(
    private val expireAfterWriteMillis: Long
) {
    private data class CacheEntry<V>(
        val value: V,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(expireAfterWriteMillis: Long) = System.currentTimeMillis() - timestamp > expireAfterWriteMillis
    }

    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()

    fun getIfPresent(key: K): V? {
        val entry = cache[key] ?: return null
        return if (entry.isExpired(expireAfterWriteMillis)) {
            cache.remove(key)
            null
        } else {
            entry.value
        }
    }

    fun put(key: K, value: V) {
        cache[key] = CacheEntry(value)
    }
}
