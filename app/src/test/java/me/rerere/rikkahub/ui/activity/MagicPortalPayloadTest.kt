package me.rerere.rikkahub.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MagicPortalPayloadTest {
    @Test
    fun `accepts text send without images`() {
        val payload = MagicPortalCandidate(
            action = ACTION_SEND,
            declaredMimeType = "text/plain",
            text = "  帮我总结这段话  ",
            streamUris = emptyList(),
        ).normalize { null }

        assertEquals("  帮我总结这段话  ", payload?.text)
        assertEquals(emptyList<MagicPortalImage>(), payload?.images)
    }

    @Test
    fun `accepts and deduplicates multiple declared images`() {
        val payload = MagicPortalCandidate(
            action = ACTION_SEND_MULTIPLE,
            declaredMimeType = "image/*",
            text = null,
            streamUris = listOf("content://images/1", "content://images/2", "content://images/1"),
        ).normalize { uri ->
            when (uri) {
                "content://images/1" -> "image/png"
                "content://images/2" -> "image/jpeg"
                else -> null
            }
        }

        assertEquals(
            listOf(
                MagicPortalImage("content://images/1", "image/png"),
                MagicPortalImage("content://images/2", "image/jpeg"),
            ),
            payload?.images,
        )
    }

    @Test
    fun `accepts mixed text and resolver confirmed image`() {
        val payload = MagicPortalCandidate(
            action = ACTION_SEND,
            declaredMimeType = "text/plain",
            text = "看看这张图",
            streamUris = listOf("content://shared/photo"),
        ).normalize { "image/webp" }

        assertEquals("看看这张图", payload?.text)
        assertEquals(
            listOf(MagicPortalImage("content://shared/photo", "image/webp")),
            payload?.images,
        )
    }

    @Test
    fun `filters non image streams from wildcard shares`() {
        val payload = MagicPortalCandidate(
            action = ACTION_SEND_MULTIPLE,
            declaredMimeType = "*/*",
            text = null,
            streamUris = listOf("content://shared/photo", "content://shared/document"),
        ).normalize { uri ->
            if (uri.endsWith("photo")) "image/jpeg" else "application/pdf"
        }

        assertEquals(
            listOf(MagicPortalImage("content://shared/photo", "image/jpeg")),
            payload?.images,
        )
    }

    @Test
    fun `rejects unsupported actions and empty payloads`() {
        assertNull(
            MagicPortalCandidate(
                action = "android.intent.action.VIEW",
                declaredMimeType = "text/plain",
                text = "ignored",
                streamUris = emptyList(),
            ).normalize { null },
        )
        assertNull(
            MagicPortalCandidate(
                action = ACTION_SEND,
                declaredMimeType = "text/plain",
                text = "   ",
                streamUris = emptyList(),
            ).normalize { null },
        )
    }
}
