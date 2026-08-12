package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class KnowledgeSpaceManagerTest {
    @Test
    fun assistantUserPromptIsCreatedOnceAndUsesRevisionCheckedUpdates() {
        val fixture = fixture()
        fixture.knowledge.initialize(fixture.root, "个人知识库")

        val created = fixture.knowledge.ensureAssistantUserPrompt(
            root = fixture.root,
            assistantId = "assistant-1",
            fallbackContent = "初始用户提示词",
        )
        val preserved = fixture.knowledge.ensureAssistantUserPrompt(
            root = fixture.root,
            assistantId = "assistant-1",
            fallbackContent = "不应覆盖",
        )

        assertEquals("vault/99_系统/提示词/助手/assistant-1.md", created.path)
        assertEquals("初始用户提示词", preserved.content)
        assertEquals(created.revision, preserved.revision)

        val updated = fixture.knowledge.writeAssistantUserPrompt(
            root = fixture.root,
            assistantId = "assistant-1",
            content = "",
            expectedRevision = created.revision,
        )
        assertEquals("", updated.content)
        assertEquals(updated, fixture.knowledge.readAssistantUserPrompt(fixture.root, "assistant-1"))

        val conflict = assertThrows(AssistantUserPromptConflictException::class.java) {
            fixture.knowledge.writeAssistantUserPrompt(
                root = fixture.root,
                assistantId = "assistant-1",
                content = "过期覆盖",
                expectedRevision = created.revision,
            )
        }
        assertEquals(updated.revision, conflict.current?.revision)
    }

    @Test
    fun assistantUserPromptRejectsUnsafeAssistantId() {
        val fixture = fixture()
        fixture.knowledge.initialize(fixture.root, "个人知识库")

        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.readAssistantUserPrompt(fixture.root, "../other")
        }
    }

    @Test
    fun initializeCreatesVaultOnlyAndPreservesExistingGuidance() {
        val fixture = fixture()

        val first = fixture.knowledge.initialize(fixture.root, "个人知识库")
        val generatedAgents = fixture.workspace.readText(fixture.root, KnowledgeSpaceManager.AGENTS_FILE)
        fixture.workspace.writeText(fixture.root, KnowledgeSpaceManager.AGENTS_FILE, "用户自己的维护规则")
        val second = fixture.knowledge.initialize(fixture.root, "另一个名字")

        assertTrue(first.initialized)
        assertTrue(second.initialized)
        assertEquals("vault", first.contentRoot)
        assertEquals(9, first.contentFileCount)
        assertEquals(9, first.indexedDocumentCount)
        assertTrue(generatedAgents.contains("C.A.P.：Context / Actions / Progress"))
        assertTrue(generatedAgents.contains("`30_研究/<领域>/<主题>/` | `reference` | `<主题>.md`"))
        assertTrue(generatedAgents.contains("`60_工具/<类别>/` | `tool` | `<工具名>.md`"))
        assertTrue(generatedAgents.contains("`Plan_YYYY-MM-DD_<主题>.md`"))
        assertEquals(
            "用户自己的维护规则",
            fixture.workspace.readText(fixture.root, KnowledgeSpaceManager.AGENTS_FILE),
        )
        assertTrue(
            fixture.workspace.listFiles(fixture.root, KnowledgeSpaceManager.VAULT_DIR)
                .map { it.name }
                .containsAll(
                    listOf(
                        "00_收件箱",
                        "10_日记",
                        "20_项目",
                        "30_研究",
                        "40_知识库",
                        "50_资源",
                        "60_工具",
                        "90_计划",
                        "99_系统",
                    )
                )
        )
        assertTrue(fixture.workspace.exists(fixture.root, "vault/99_系统/模板/Daily_Note.md"))
        assertTrue(fixture.workspace.exists(fixture.root, "vault/99_系统/模板/Content_Template.md"))
        assertTrue(fixture.workspace.exists(fixture.root, "vault/60_工具/README.md"))
        assertTrue(
            fixture.workspace.readText(fixture.root, "vault/.gitignore")
                .contains(".env.*")
        )
        assertTrue(fixture.workspace.exists(fixture.root, KnowledgeSpaceManager.MARKER_FILE))
        assertFalse(fixture.workspace.exists(fixture.root, "PROJECT.md"))
        assertFalse(fixture.workspace.exists(fixture.root, "knowledge"))
    }

    @Test
    fun listContentsShowsVaultFoldersButHidesDotFiles() {
        val fixture = fixture()
        fixture.knowledge.initialize(fixture.root, "个人知识库")
        fixture.workspace.createDirectory(fixture.root, "vault/.git")
        fixture.workspace.writeText(fixture.root, "vault/.env", "SECRET=value")
        fixture.workspace.writeText(fixture.root, "vault/40_知识库/概念.md", "# 概念")

        val rootEntries = fixture.knowledge.listContents(fixture.root)
        val wikiEntries = fixture.knowledge.listContents(fixture.root, "40_知识库")

        assertTrue(rootEntries.any { it.name == "40_知识库" && it.isDirectory })
        assertFalse(rootEntries.any { it.name.startsWith(".") })
        assertEquals(listOf("概念.md"), wikiEntries.map { it.name })
        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.listContents(fixture.root, "../.zhixing")
        }
    }

    @Test
    fun existingVaultIsAdoptedWithoutLocalMarker() {
        val fixture = fixture()
        fixture.workspace.createDirectory(fixture.root, KnowledgeSpaceManager.VAULT_DIR)
        fixture.workspace.writeText(
            fixture.root,
            KnowledgeSpaceManager.AGENTS_FILE,
            "# 手机已有 OrbitOS CN vault",
        )
        fixture.workspace.writeText(
            fixture.root,
            "vault/40_知识库/概念.md",
            "# 概念",
        )

        val status = fixture.knowledge.status(fixture.root)

        assertTrue(status.initialized)
        assertEquals("vault", status.contentRoot)
        assertEquals(2, status.contentFileCount)
        assertEquals(2, status.indexedDocumentCount)
        assertFalse(fixture.workspace.exists(fixture.root, KnowledgeSpaceManager.MARKER_FILE))
    }

    @Test
    fun researchAndToolNotesCanBeSearchedAndReadWithVaultCitation() {
        val fixture = fixture()
        fixture.knowledge.initialize(fixture.root, "研究知识库")
        fixture.workspace.writeText(
            fixture.root,
            "vault/30_研究/影视评论/无限中的锚点/无限中的锚点.md",
            """
                ---
                type: reference
                ---
                # 无限中的锚点

                车库是角色回到现实关系的叙事锚点。
            """.trimIndent(),
        )
        fixture.workspace.writeText(
            fixture.root,
            "vault/60_工具/账单流水线/dedup脚本.md",
            """
                ---
                type: tool
                ---
                # dedup 脚本

                用于账单去重。
            """.trimIndent(),
        )

        val research = fixture.knowledge.search(fixture.root, "叙事锚点")
        val tool = fixture.knowledge.search(fixture.root, "账单去重")

        assertEquals(1, research.matches.size)
        assertEquals(
            "vault/30_研究/影视评论/无限中的锚点/无限中的锚点.md",
            research.matches.single().path,
        )
        assertEquals(
            "workspace://vault/30_研究/影视评论/无限中的锚点/无限中的锚点.md#L6",
            research.matches.single().citation,
        )
        assertEquals("vault/60_工具/账单流水线/dedup脚本.md", tool.matches.single().sourcePath)

        val read = fixture.knowledge.read(
            root = fixture.root,
            path = research.matches.single().path,
            startLine = 4,
            endLine = 6,
        )
        assertTrue(read.text.contains("叙事锚点"))
        assertEquals(
            "workspace://vault/30_研究/影视评论/无限中的锚点/无限中的锚点.md#L4-L6",
            read.citation,
        )
    }

    @Test
    fun importsLandInInboxAndDirectTextDoesNotCreateDuplicateIndex() {
        val fixture = fixture()
        fixture.knowledge.initialize(fixture.root, "个人知识库")

        val imported = fixture.knowledge.importSource(
            root = fixture.root,
            fileName = "随手记录.md",
            inputStream = ByteArrayInputStream("离线优先".toByteArray()),
            normalizedText = "离线优先",
        )

        assertEquals("vault/00_收件箱/随手记录.md", imported.sourcePath)
        assertNull(imported.normalizedPath)
        assertTrue(imported.indexed)
        assertEquals(1, fixture.knowledge.search(fixture.root, "离线优先").matches.size)
    }

    @Test
    fun binaryImportsKeepOriginalInInboxAndUseDerivedIndexOutsideVault() {
        val fixture = fixture()
        fixture.knowledge.initialize(fixture.root, "个人知识库")
        val contentCountBeforeImport = fixture.knowledge.status(fixture.root).contentFileCount

        val imported = fixture.knowledge.importSource(
            root = fixture.root,
            fileName = "访谈记录.docx",
            inputStream = ByteArrayInputStream("original bytes".toByteArray()),
            normalizedText = "# 访谈记录\n\n升级时不能丢失内容。",
        )
        val result = fixture.knowledge.search(fixture.root, "不能丢失")

        assertEquals("vault/00_收件箱/访谈记录.docx", imported.sourcePath)
        assertTrue(imported.normalizedPath!!.startsWith(".zhixing/knowledge/normalized/"))
        assertEquals(imported.normalizedPath, result.matches.single().path)
        assertEquals(imported.sourcePath, result.matches.single().sourcePath)
        assertEquals(contentCountBeforeImport + 1, fixture.knowledge.status(fixture.root).contentFileCount)
        assertTrue(fixture.knowledge.status(fixture.root).indexedDocumentCount > 0)
    }

    @Test
    fun duplicateImportsNeverOverwriteExistingInboxEntry() {
        val fixture = fixture()
        fixture.knowledge.initialize(fixture.root, "个人知识库")

        val first = fixture.knowledge.importSource(
            fixture.root,
            "资料.md",
            ByteArrayInputStream("one".toByteArray()),
            "one",
        )
        val second = fixture.knowledge.importSource(
            fixture.root,
            "资料.md",
            ByteArrayInputStream("two".toByteArray()),
            "two",
        )

        assertEquals("vault/00_收件箱/资料.md", first.sourcePath)
        assertEquals("vault/00_收件箱/资料 (1).md", second.sourcePath)
        assertEquals("one", fixture.workspace.readText(fixture.root, first.sourcePath))
        assertEquals("two", fixture.workspace.readText(fixture.root, second.sourcePath))
    }

    @Test
    fun readRejectsPathsOutsideVaultKnowledgeBoundary() {
        val fixture = fixture()
        fixture.knowledge.initialize(fixture.root, "个人知识库")
        fixture.workspace.writeText(fixture.root, "private.txt", "secret")
        fixture.workspace.writeText(fixture.root, "vault/.gemini/settings.json", """{"secret":"value"}""")
        fixture.workspace.writeText(fixture.root, "vault/.git/config", "credential = secret")
        fixture.workspace.writeText(fixture.root, "vault/40_知识库/.env.json", """{"token":"secret"}""")

        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.read(fixture.root, "private.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.read(fixture.root, "../private.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.read(fixture.root, "vault/40_知识库/../../private.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.read(fixture.root, "vault/.gemini/settings.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.read(fixture.root, "vault/.git/config")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.read(fixture.root, "vault/40_知识库/.env.json")
        }
        assertTrue(fixture.knowledge.search(fixture.root, "secret").matches.isEmpty())
    }

    private fun fixture(): Fixture {
        val baseDir = Files.createTempDirectory("knowledge-space-test").toFile()
        val workspace = WorkspaceManager(baseDir)
        val root = "knowledge-workspace"
        workspace.ensureWorkspace(root)
        return Fixture(root, workspace, KnowledgeSpaceManager(workspace))
    }

    private data class Fixture(
        val root: String,
        val workspace: WorkspaceManager,
        val knowledge: KnowledgeSpaceManager,
    )
}
