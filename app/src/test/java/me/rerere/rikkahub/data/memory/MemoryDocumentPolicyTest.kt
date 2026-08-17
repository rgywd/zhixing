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
    fun personalFactsIncludingBirthdayAndHealthAreAccepted() {
        listOf(
            "- [stated] 用户的公历生日是 10 月 17 日，出生于 2002 年。",
            "- [stated] 用户的出生日期是 2000-01-01。",
            "- [stated] 用户的生日是 10 月 17 日。",
            "- [stated] 用户出生于 2002 年。",
            "- [stated] 用户在减重，目标是体脂降到 18%。",
        ).forEach { content ->
            assertTrue(
                "expected accepted: $content",
                runCatching {
                    requireValidMemoryDocument(
                        path = "/profile.md",
                        name = "Profile",
                        description = "Stable profile",
                        aliases = emptyList(),
                        content = content,
                    )
                }.isSuccess,
            )
        }
    }

    @Test
    fun canonicalMemoryContentAcceptsStableFormats() {
        requireCanonicalMemoryContent(
            """
            - [stated] 用户的出生日期是 2002-10-17（公历）。
            - [stated] 用户从 2026-08 开始维护知行。
            - [stated] 用户的年度纪念日是 10-17。
            - [stated] 用户出生于 2002。
            - [stated] 用户通常在 09:30 开始工作。
            - [stated] 用户所在时区是 Asia/Shanghai。
            - [stated] 用户的身高是 175 cm，目标体脂是 18%。
            - [stated] 用户偏好的回复语言是 zh-CN，常用币种是 CNY。
            """.trimIndent()
        )
    }

    @Test
    fun canonicalMemoryContentDoesNotTreatRangesOrUrlsAsDates() {
        requireCanonicalMemoryContent(
            """
            - [stated] 用户的目标体重范围是 60-70 kg。
            - [stated] 项目归档位于 https://example.com/2026/8/17/report。
            """.trimIndent()
        )
    }

    @Test
    fun canonicalMemoryContentRejectsLocalizedOrInvalidFormats() {
        listOf(
            "- [stated] 用户的生日是 10 月 17 日。",
            "- [stated] 用户出生于 2002 年。",
            "- [stated] 用户的生日是 02-30。",
            "- [stated] 用户通常在 9:30 开始工作。",
            "- [stated] 用户的体重是 70公斤。",
            "- [stated] 用户的目标体脂是 18 %。",
        ).forEach { content ->
            assertTrue(
                "expected a format issue: $content",
                runCatching { requireCanonicalMemoryContent(content) }.exceptionOrNull() is
                    MemoryDocumentFormatException,
            )
        }
    }

    @Test
    fun sensitiveCategoriesAndPreferenceControlInstructionsAreRejected() {
        listOf(
            "- [stated] 用户的身份证号是 110101200001010011。",
            "- [stated] 用户的银行卡号是 6222 0000 0000 0000。",
            "- [stated] 用户的信用卡号 4000 0000 0000 0000。",
            "- [stated] 用户的密码是 mypass123。",
            "- [stated] 用户的 API key 是 sk-abc123。",
        ).forEach { content ->
            assertTrue(
                "expected rejected: $content",
                runCatching {
                    requireValidMemoryDocument(
                        path = "/profile.md",
                        name = "Profile",
                        description = "Stable profile",
                        aliases = emptyList(),
                        content = content,
                    )
                }.isFailure,
            )
        }

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
