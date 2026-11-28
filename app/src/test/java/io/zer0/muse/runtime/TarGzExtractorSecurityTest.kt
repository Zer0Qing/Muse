package io.zer0.muse.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.GZIPOutputStream

class TarGzExtractorSecurityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `rejects traversal into sibling whose name shares destination prefix`() {
        val destination = temporaryFolder.newFolder("out")
        val sibling = File(temporaryFolder.root, "outside")
        val archive = tar(entry("../outside/payload.txt", '0', "escape".toByteArray()))

        val error = runCatching { TarGzExtractor.extract(archive, destination) }.exceptionOrNull()

        assertNotNull(error)
        assertFalse(File(sibling, "payload.txt").exists())
    }

    @Test
    fun `rejects absolute member path`() {
        val destination = temporaryFolder.newFolder("root")
        val outside = File(temporaryFolder.root, "absolute.txt")
        val archive = tar(entry(outside.absolutePath, '0', "escape".toByteArray()))

        val error = runCatching { TarGzExtractor.extract(archive, destination) }.exceptionOrNull()

        assertNotNull(error)
        assertFalse(outside.exists())
    }

    @Test
    fun `rejects symbolic and hard link pivots outside destination`() {
        val destination = temporaryFolder.newFolder("root")
        val symbolic = tar(entry("pivot", '2', byteArrayOf(), linkName = "../../outside"))
        val hard = tar(entry("hard", '1', byteArrayOf(), linkName = "../../outside"))

        assertNotNull(runCatching { TarGzExtractor.extract(symbolic, destination) }.exceptionOrNull())
        assertNotNull(runCatching { TarGzExtractor.extract(hard, destination) }.exceptionOrNull())
        assertFalse(File(temporaryFolder.root, "outside").exists())
    }

    @Test
    fun `allows absolute symlink that resolves inside the proot virtual root`() {
        // Ubuntu rootfs 自带 `var/run -> /run`。proot 把 `/run` 映射进 rootfs 内部，
        // 不是 Android 宿主机；这类标准链接必须判为合法，否则安装必败。
        // 直接断言判据本身：真实建链接在 Windows 上需要额外权限，会掩盖真正的回归点。
        val destination = temporaryFolder.newFolder("root").canonicalFile
        val parent = File(destination, "var").apply { mkdirs() }

        val target = TarGzExtractor.resolveLinkTarget(destination, parent, "/run")

        assertNotNull("绝对链接落在虚拟根内时必须允许", target)
        assertTrue("目标应保留 run 段", target!!.toString().trimEnd('/', '\\').endsWith("run"))
        assertFalse("`/run` 不该被判成逃逸", TarGzExtractor.absoluteLinkEscapesRoot("/run"))
        assertFalse("`/run/lock` 不该被判成逃逸", TarGzExtractor.absoluteLinkEscapesRoot("/run/lock"))
        assertFalse("`/etc/../usr` 仍在根内", TarGzExtractor.absoluteLinkEscapesRoot("/etc/../usr"))
    }

    @Test
    fun `rejects absolute symlink whose virtual path escapes the root`() {
        val destination = temporaryFolder.newFolder("root").canonicalFile
        val parent = File(destination, "etc").apply { mkdirs() }

        assertNull(
            "`/../outside` 会逃出虚拟根，必须拒绝",
            TarGzExtractor.resolveLinkTarget(destination, parent, "/../outside"),
        )
        assertNull(
            "`../../outside` 相对逃逸必须拒绝",
            TarGzExtractor.resolveLinkTarget(destination, parent, "../../outside"),
        )
        assertTrue("`/../outside` 应判为逃逸", TarGzExtractor.absoluteLinkEscapesRoot("/../outside"))
        assertTrue("`/a/../../b` 应判为逃逸", TarGzExtractor.absoluteLinkEscapesRoot("/a/../../b"))
    }

    @Test
    fun `rejects truncated member payload and truncated pax record`() {
        val destination = temporaryFolder.newFolder("root")
        val payload = tar(entry("short.txt", '0', "xy".toByteArray(), declaredSize = 10, pad = false), endBlocks = 0)
        val pax = tar(entry("PaxHeaders/0", 'x', "4 pa".toByteArray(), declaredSize = 20, pad = false), endBlocks = 0)

        assertNotNull(runCatching { TarGzExtractor.extract(payload, destination) }.exceptionOrNull())
        assertNotNull(runCatching { TarGzExtractor.extract(pax, destination) }.exceptionOrNull())
    }

    @Test
    fun `rejects invalid header checksum and missing end marker`() {
        val destination = temporaryFolder.newFolder("root")
        val badChecksum = tar(entry("file.txt", '0', "data".toByteArray(), checksumValid = false))
        val missingEnd = tar(entry("file.txt", '0', "data".toByteArray()), endBlocks = 0)

        assertNotNull(runCatching { TarGzExtractor.extract(badChecksum, destination) }.exceptionOrNull())
        assertNotNull(runCatching { TarGzExtractor.extract(missingEnd, destination) }.exceptionOrNull())
    }

    @Test
    fun `extracts utf8 path from byte length pax record`() {
        val destination = temporaryFolder.newFolder("root")
        val path = "资料/咖啡.txt"
        val archive = tar(
            entry("PaxHeaders/0", 'x', paxPath(path)),
            entry("placeholder", '0', "ok".toByteArray()),
        )

        TarGzExtractor.extract(archive, destination)

        assertEquals("ok", File(destination, path).readText())
    }

    private data class Entry(
        val name: String,
        val type: Char,
        val payload: ByteArray,
        val linkName: String = "",
        val declaredSize: Int = payload.size,
        val pad: Boolean = true,
        val checksumValid: Boolean = true,
    )

    private fun entry(
        name: String,
        type: Char,
        payload: ByteArray,
        linkName: String = "",
        declaredSize: Int = payload.size,
        pad: Boolean = true,
        checksumValid: Boolean = true,
    ) = Entry(name, type, payload, linkName, declaredSize, pad, checksumValid)

    private fun tar(vararg entries: Entry, endBlocks: Int = 2): File {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { output ->
            entries.forEach { entry ->
                output.write(header(entry))
                output.write(entry.payload)
                if (entry.pad) {
                    val padding = (512 - entry.declaredSize % 512) % 512
                    if (padding > 0) output.write(ByteArray(padding))
                }
            }
            repeat(endBlocks) { output.write(ByteArray(512)) }
        }
        return temporaryFolder.newFile("archive-${System.nanoTime()}.tar.gz").apply { writeBytes(bytes.toByteArray()) }
    }

    private fun header(entry: Entry): ByteArray {
        val header = ByteArray(512)
        fun put(offset: Int, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            System.arraycopy(bytes, 0, header, offset, minOf(bytes.size, 512 - offset))
        }
        put(0, entry.name)
        put(100, octal(0b111_101_101, 8))
        put(108, octal(0, 8))
        put(116, octal(0, 8))
        put(124, octal(entry.declaredSize, 12))
        put(136, octal(0, 12))
        header.fill(' '.code.toByte(), 148, 156)
        header[156] = entry.type.code.toByte()
        put(157, entry.linkName)
        put(257, "ustar")
        put(263, "00")
        val checksum = header.sumOf { it.toInt() and 0xff }
        put(148, String.format(Locale.ROOT, "%06o\u0000 ", checksum))
        if (!entry.checksumValid) header[0] = (header[0].toInt() xor 1).toByte()
        return header
    }

    private fun paxPath(path: String): ByteArray {
        val record = "path=$path\n".toByteArray(Charsets.UTF_8)
        var length = record.size + 3
        while (true) {
            val nextLength = record.size + length.toString().length + 1
            if (nextLength == length) break
            length = nextLength
        }
        return "$length ".toByteArray(Charsets.US_ASCII) + record
    }

    private fun octal(value: Int, width: Int): String = value.toString(8).padStart(width - 1, '0') + "\u0000"
}
