package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpleCacheTest {
    @Test
    fun `returns a cached value before expiry`() {
        val cache = SimpleCache<String, String>(Long.MAX_VALUE)

        cache.put("key", "value")

        assertEquals("value", cache.getIfPresent("key"))
    }

    @Test
    fun `removes an expired value`() {
        val cache = SimpleCache<String, String>(-1)

        cache.put("key", "value")

        assertNull(cache.getIfPresent("key"))
    }
}
