package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class KnowledgeSpaceManagerTest {
    @Test
    fun initializeIsIdempotentAndPreservesProjectFile() {
        val fixture = fixture()

        val first = fixture.knowledge.initialize(fixture.root, "长篇小说")
        fixture.workspace.writeText(fixture.root, KnowledgeSpaceManager.PROJECT_FILE, "用户自己的项目说明")
        val second = fixture.knowledge.initialize(fixture.root, "另一个名字")

        assertTrue(first.initialized)
        assertTrue(second.initialized)
        assertEquals(
            "用户自己的项目说明",
            fixture.workspace.readText(fixture.root, KnowledgeSpaceManager.PROJECT_FILE),
        )
        assertTrue(
            fixture.workspace.listFiles(fixture.root, "knowledge")
                .map { it.name }
                .containsAll(listOf("sources", "notes", "decisions", "outputs", "drafts"))
        )
    }

    @Test
    fun importedChineseContentCanBeSearchedAndReadWithSourceCitation() {
        val fixture = fixture()
        fixture.knowledge.initialize(fixture.root, "研究项目")

        val imported = fixture.knowledge.importSource(
            root = fixture.root,
            fileName = "访谈记录.docx",
            inputStream = ByteArrayInputStream("original bytes".toByteArray()),
            normalizedText = "# 访谈记录\n\n用户需要离线优先，并且升级时不能丢失内容。",
        )
        val result = fixture.knowledge.search(fixture.root, "离线优先")

        assertEquals("knowledge/sources/访谈记录.docx", imported.sourcePath)
        assertEquals(1, result.matches.size)
        assertEquals(imported.normalizedPath, result.matches.single().path)
        assertEquals(imported.sourcePath, result.matches.single().sourcePath)
        assertTrue(result.matches.single().citation.endsWith("#L5"))
        assertEquals(1, fixture.knowledge.status(fixture.root).sourceCount)
        assertEquals(1, fixture.knowledge.status(fixture.root).indexedDocumentCount)

        val read = fixture.knowledge.read(
            root = fixture.root,
            path = imported.normalizedPath!!,
            startLine = 4,
            endLine = 5,
        )
        assertEquals(imported.sourcePath, read.sourcePath)
        assertTrue(read.text.contains("升级时不能丢失内容"))
        assertEquals("workspace://${imported.normalizedPath}#L4-L5", read.citation)
    }

    @Test
    fun duplicateImportsNeverOverwriteExistingSource() {
        val fixture = fixture()
        fixture.knowledge.initialize(fixture.root, "项目")

        val first = fixture.knowledge.importSource(
            fixture.root,
            "资料.md",
            ByteArrayInputStream("one".toByteArray()),
            "第一份资料",
        )
        val second = fixture.knowledge.importSource(
            fixture.root,
            "资料.md",
            ByteArrayInputStream("two".toByteArray()),
            "第二份资料",
        )

        assertEquals("knowledge/sources/资料.md", first.sourcePath)
        assertEquals("knowledge/sources/资料 (1).md", second.sourcePath)
        assertEquals("one", fixture.workspace.readText(fixture.root, first.sourcePath))
        assertEquals("two", fixture.workspace.readText(fixture.root, second.sourcePath))
        assertFalse(first.normalizedPath == second.normalizedPath)
    }

    @Test
    fun readRejectsPathsOutsideKnowledgeBoundary() {
        val fixture = fixture()
        fixture.knowledge.initialize(fixture.root, "项目")
        fixture.workspace.writeText(fixture.root, "private.txt", "secret")

        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.read(fixture.root, "private.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.read(fixture.root, "../private.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.read(fixture.root, "knowledge/notes/../../private.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.knowledge.read(fixture.root, "knowledge/notes/../sources/private.txt")
        }
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
