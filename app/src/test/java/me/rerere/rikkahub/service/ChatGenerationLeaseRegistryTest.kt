package me.rerere.rikkahub.service

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGenerationLeaseRegistryTest {
    @Test
    fun `parallel conversations remain protected until the final generation ends`() {
        val registry = ChatGenerationLeaseRegistry()
        val firstGeneration = id(1)
        val firstConversation = id(2)
        val secondGeneration = id(3)
        val secondConversation = id(4)

        registry.acquire(firstGeneration, firstConversation)
        val parallel = registry.acquire(secondGeneration, secondConversation)

        assertEquals(2, parallel.activeGenerationCount)
        assertEquals(2, parallel.activeConversationCount)
        assertEquals(secondConversation, parallel.targetConversationId)

        val firstFinished = registry.release(firstGeneration)

        assertTrue(firstFinished.isActive)
        assertEquals(1, firstFinished.activeGenerationCount)
        assertEquals(1, firstFinished.activeConversationCount)
        assertEquals(secondConversation, firstFinished.targetConversationId)

        val allFinished = registry.release(secondGeneration)

        assertFalse(allFinished.isActive)
        assertEquals(0, allFinished.activeGenerationCount)
        assertEquals(0, allFinished.activeConversationCount)
        assertEquals(null, allFinished.targetConversationId)
    }

    @Test
    fun `completion of an old run cannot unprotect its replacement`() {
        val registry = ChatGenerationLeaseRegistry()
        val conversationId = id(10)
        val oldGeneration = id(11)
        val replacementGeneration = id(12)

        registry.acquire(oldGeneration, conversationId)
        val replaced = registry.acquire(replacementGeneration, conversationId)

        assertEquals(2, replaced.activeGenerationCount)
        assertEquals(1, replaced.activeConversationCount)

        val oldFinished = registry.release(oldGeneration)

        assertTrue(oldFinished.isActive)
        assertEquals(1, oldFinished.activeGenerationCount)
        assertEquals(1, oldFinished.activeConversationCount)
        assertEquals(conversationId, oldFinished.targetConversationId)
    }

    @Test
    fun `releasing an unknown generation leaves active state unchanged`() {
        val registry = ChatGenerationLeaseRegistry()
        val activeGeneration = id(20)
        val conversationId = id(21)
        registry.acquire(activeGeneration, conversationId)

        val state = registry.release(id(22))

        assertTrue(state.isActive)
        assertEquals(1, state.activeGenerationCount)
        assertEquals(conversationId, state.targetConversationId)
    }

    private fun id(value: Int): Uuid =
        Uuid.parse("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
}
