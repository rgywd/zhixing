package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class RootfsInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extract skips OTHER entry data exactly once`() {
        // OTHER 条目 (如 GNU sparse) 带 size>0 数据区, 双重 skip 会让后续 header 错位
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("a.txt", '0', "hello".toByteArray())
            out.writeTarEntry("sparse.bin", 'S', ByteArray(700) { 1 })
            out.writeTarEntry("b.txt", '0', "world".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals("hello", File(target, "a.txt").readText())
        assertEquals("world", File(target, "b.txt").readText())
        assertFalse(File(target, "sparse.bin").exists())
    }

    @Test
    fun `extract handles directories and zero size entries`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("dir/", '5', ByteArray(0))
            out.writeTarEntry("dir/file.txt", '0', "content".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals(true, File(target, "dir").isDirectory)
        assertEquals("content", File(target, "dir/file.txt").readText())
    }

    @Test
    fun `extract keeps chained absolute symlinks inside guest rootfs`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("usr/bin/sensible-pager", '0', "pager".toByteArray())
            out.writeTarEntry(
                name = "etc/alternatives/pager",
                type = '2',
                data = ByteArray(0),
                linkName = "/usr/bin/sensible-pager",
            )
            out.writeTarEntry(
                name = "usr/bin/pager",
                type = '2',
                data = ByteArray(0),
                linkName = "/etc/alternatives/pager",
            )
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        val alternative = File(target, "etc/alternatives/pager")
        val pager = File(target, "usr/bin/pager")
        assertTrue(Files.isSymbolicLink(alternative.toPath()) || alternative.isFile)
        assertTrue(Files.isSymbolicLink(pager.toPath()) || pager.isFile)
        if (File.separatorChar != '\\') {
            assertEquals(
                "/etc/alternatives/pager",
                Files.readSymbolicLink(pager.toPath()).toString(),
            )
        }
    }

    @Test
    fun `extract keeps top level relative symlink inside guest rootfs`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("usr/bin/", '5', ByteArray(0))
            out.writeTarEntry(
                name = "bin",
                type = '2',
                data = ByteArray(0),
                linkName = "usr/bin",
            )
            out.writeTarEntry("bin/more", '0', "more".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        val bin = File(target, "bin")
        assertTrue(Files.isSymbolicLink(bin.toPath()) || bin.isDirectory)
        if (Files.isSymbolicLink(bin.toPath())) {
            assertEquals("usr/bin", Files.readSymbolicLink(bin.toPath()).toString())
        }
        assertEquals("more", File(target, "bin/more").readText())
    }

    private fun createInstaller() = RootfsInstaller(WorkspaceManager(tmp.newFolder()))

    private fun OutputStream.writeTarEntry(
        name: String,
        type: Char,
        data: ByteArray,
        linkName: String = "",
    ) {
        val header = ByteArray(TAR_BLOCK)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        "0000755".toByteArray().copyInto(header, 100)
        data.size.toLong().toOctalField().copyInto(header, 124)
        header[156] = type.code.toByte()
        linkName.toByteArray(Charsets.UTF_8).copyInto(header, 157)
        write(header)
        write(data)
        val padding = (TAR_BLOCK - data.size % TAR_BLOCK) % TAR_BLOCK
        write(ByteArray(padding))
    }

    private fun Long.toOctalField(): ByteArray =
        toString(8).padStart(11, '0').toByteArray(Charsets.UTF_8)

    companion object {
        private const val TAR_BLOCK = 512
    }
}
