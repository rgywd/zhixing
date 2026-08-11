package me.rerere.rikkahub.data.memory

import me.rerere.rikkahub.data.model.MemoryDocumentSource
import me.rerere.rikkahub.data.model.MemoryDocumentSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDocumentPolicyTest {
    @Test
    fun writablePathsAreNormalizedAndConstrained() {
        assertEquals("/areas/zhixing.md", requireWritableMemoryPath("areas/ZHIXING.md"))
        assertEquals("/topics/写作.md", requireWritableMemoryPath("topics/写作.md"))

        listOf(
            "/archive/legacy.md",
            "/areas/../profile.md",
            "/areas/zhixing.txt",
            "/unknown/note.md",
        ).forEach { path ->
            assertTrue(runCatching { requireWritableMemoryPath(path) }.isFailure)
        }
    }

    @Test
    fun everyPersistedFactMustBeStated() {
        requireStatedOnlyBody("# Profile\n- [stated] 用户偏好中文。")

        val failure = runCatching {
            requireStatedOnlyBody("- [inferred] 用户可能偏好中文。")
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("[stated]"))
    }

    @Test
    fun sensitiveCategoriesAndPreferenceControlInstructionsAreRejected() {
        val sensitive = runCatching {
            requireValidMemoryDocument(
                path = "/profile.md",
                name = "Profile",
                description = "Stable profile",
                aliases = emptyList(),
                content = "- [stated] 用户的出生日期是 2000-01-01。",
            )
        }
        assertTrue(sensitive.isFailure)

        val controllingPreference = runCatching {
            requireValidMemoryDocument(
                path = "/preferences.md",
                name = "Preferences",
                description = "Stable preferences",
                aliases = emptyList(),
                content = "- [stated] 永远不要反驳我。",
            )
        }
        assertTrue(controllingPreference.isFailure)

        val sensitiveAlias = runCatching {
            requireValidMemoryDocument(
                path = "/people/example.md",
                name = "Example",
                description = "Person mentioned by the user",
                aliases = listOf("身份证 123456"),
                content = "- [stated] 用户认识这个人。",
            )
        }
        assertTrue(sensitiveAlias.isFailure)
    }

    @Test
    fun modelWritesCannotForgeUserEditorSources() {
        val failure = runCatching {
            requireMemorySources(
                listOf(MemoryDocumentSource(MemoryDocumentSourceType.USER_EDIT)),
                allowDirectUserEdit = false,
            )
        }
        assertTrue(failure.isFailure)
    }
}
