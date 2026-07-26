package me.rerere.rikkahub.data.ai.transformers

import me.rerere.common.cache.CacheEntry
import me.rerere.common.cache.CacheStore
import me.rerere.common.cache.LruCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OcrCacheRemovalTest {
    @Test
    fun removesOnlyRequestedUrlsFromMemoryAndStore() {
        val store = InMemoryCacheStore()
        val cache = LruCache<String, String>(
            capacity = 4,
            store = store,
        )
        cache.put("file:///upload/one.png", "one")
        cache.put("file:///upload/two.png", "two")

        removeOcrCacheEntries(
            cache = cache,
            urls = listOf("file:///upload/one.png"),
        )

        assertFalse(cache.containsKey("file:///upload/one.png"))
        assertFalse(store.entries.containsKey("file:///upload/one.png"))
        assertTrue(cache.containsKey("file:///upload/two.png"))
        assertTrue(store.entries.containsKey("file:///upload/two.png"))
    }

    @Test
    fun persistentStoreRemovalFailureIsObservable() {
        val store = InMemoryCacheStore()
        val cache = LruCache<String, String>(
            capacity = 4,
            store = store,
        )
        val failingUrl = "file:///upload/one.png"
        val succeedingUrl = "file:///upload/two.png"
        cache.put(failingUrl, "one")
        cache.put(succeedingUrl, "two")
        store.failedRemovals += failingUrl

        assertThrows(IllegalStateException::class.java) {
            removeOcrCacheEntries(cache, listOf(failingUrl, succeedingUrl))
        }

        assertFalse(cache.keysInMemory().contains(failingUrl))
        assertTrue(store.entries.containsKey(failingUrl))
        assertFalse(cache.keysInMemory().contains(succeedingUrl))
        assertFalse(store.entries.containsKey(succeedingUrl))
    }

    @Test
    fun concurrentReadCannotResurrectEntryWhileCheckedRemovalIsInProgress() {
        val store = BlockingRemoveCacheStore()
        val cache = LruCache<String, String>(
            capacity = 4,
            store = store,
        )
        val url = "file:///upload/bill.png"
        cache.put(url, "sensitive ocr")
        val executor = Executors.newFixedThreadPool(2)

        try {
            val removal = executor.submit<Unit> {
                cache.removeChecked(url)
            }
            assertTrue(store.removeEntered.await(2, TimeUnit.SECONDS))

            val concurrentRead = executor.submit<String?> {
                cache.get(url)
            }
            assertFalse(concurrentRead.isDone)

            store.allowRemove.countDown()
            removal.get(2, TimeUnit.SECONDS)
            assertNull(concurrentRead.get(2, TimeUnit.SECONDS))
            assertFalse(cache.containsKey(url))
        } finally {
            store.allowRemove.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun inFlightOcrCannotWriteBackAfterCleanup() {
        val store = InMemoryCacheStore()
        val cache = LruCache<String, String>(
            capacity = 4,
            store = store,
        )
        val coordinator = OcrCacheCoordinator(cache)
        val url = "file:///upload/bill.png"

        val staleLease = coordinator.begin(url)
        assertNull(staleLease.cachedValue)
        coordinator.remove(listOf(url))

        assertFalse(
            coordinator.complete(
                url = url,
                generation = requireNotNull(staleLease.generation),
                value = "sensitive ocr",
            ),
        )
        assertFalse(cache.containsKey(url))
        assertFalse(store.entries.containsKey(url))

        val freshLease = coordinator.begin(url)
        assertTrue(
            coordinator.complete(
                url = url,
                generation = requireNotNull(freshLease.generation),
                value = "fresh ocr",
            ),
        )
        assertTrue(cache.containsKey(url))
    }

    private class InMemoryCacheStore : CacheStore<String, String> {
        val entries = mutableMapOf<String, CacheEntry<String>>()
        val failedRemovals = mutableSetOf<String>()

        override fun loadEntry(key: String): CacheEntry<String>? = entries[key]

        override fun saveEntry(key: String, entry: CacheEntry<String>) {
            entries[key] = entry
        }

        override fun remove(key: String) {
            check(key !in failedRemovals) { "persistent store unavailable" }
            entries.remove(key)
        }

        override fun clear() {
            entries.clear()
        }

        override fun loadAllEntries(): Map<String, CacheEntry<String>> = entries.toMap()

        override fun keys(): Set<String> = entries.keys
    }

    private class BlockingRemoveCacheStore : CacheStore<String, String> {
        private val entries = mutableMapOf<String, CacheEntry<String>>()
        val removeEntered = CountDownLatch(1)
        val allowRemove = CountDownLatch(1)

        override fun loadEntry(key: String): CacheEntry<String>? = entries[key]

        override fun saveEntry(key: String, entry: CacheEntry<String>) {
            entries[key] = entry
        }

        override fun remove(key: String) {
            removeEntered.countDown()
            check(allowRemove.await(2, TimeUnit.SECONDS)) { "remove was not released" }
            entries.remove(key)
        }

        override fun clear() {
            entries.clear()
        }

        override fun loadAllEntries(): Map<String, CacheEntry<String>> = entries.toMap()

        override fun keys(): Set<String> = entries.keys
    }
}
