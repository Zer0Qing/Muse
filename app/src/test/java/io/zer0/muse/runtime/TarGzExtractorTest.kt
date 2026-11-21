package io.zer0.muse.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * TarGzExtractor 的离线验证。
 *
 * 为什么要有这组测试：解包器是在真机上第一次跑就报「magic 为空、首块全零」的，
 * 而在设备上每轮试错要四十秒。这里用构造出来的 tar 在 JVM 上毫秒级复现，
 * 把「块对齐 / 扩展头 / 长路径」这些最容易错位的地方逐个锁死。
 */
class TarGzExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `解出普通文件与目录`() {
        val archive = tarGz(
            entry(name = "./", type = '5'),
            entry(name = "./hello.txt", type = '0', content = "hi muse"),
        )
        val out = tmp.newFolder("out1")
        TarGzExtractor.extract(archive, out)

        val file = File(out, "hello.txt")
        assertTrue("文件应被解出", file.isFile)
        assertEquals("hi muse", file.readText())
    }

    @Test
    fun `保留符号链接语义`() {
        // Windows 上没有开发者权限时建不了符号链接，这属于测试环境限制而非实现问题
        val probe = tmp.newFolder("probe")
        val canSymlink = runCatching {
            java.nio.file.Files.createSymbolicLink(File(probe, "l").toPath(), File("x").toPath())
        }.isSuccess
        org.junit.Assume.assumeTrue("当前环境不支持符号链接，跳过", canSymlink)

        val archive = tarGz(
            entry(name = "./bin", type = '5'),
            entry(name = "./bin/sh", type = '2', linkName = "dash"),
        )
        val out = tmp.newFolder("out2")
        TarGzExtractor.extract(archive, out)

        val link = File(out, "bin/sh")
        assertTrue("符号链接应存在", java.nio.file.Files.isSymbolicLink(link.toPath()))
        assertEquals("dash", java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
    }

    @Test
    fun `pax 扩展头后的成员仍能正确解出`() {
        // pax 头的数据段同样补齐到 512；漏掉填充会让后续块整体错位
        val archive = tarGz(
            entry(name = "./", type = '5'),
            paxEntry(path = "./long-" + "x".repeat(120) + ".txt"),
            entry(name = "./long-" + "x".repeat(120) + ".txt", type = '0', content = "pax-ok"),
        )
        val out = tmp.newFolder("out3")
        TarGzExtractor.extract(archive, out)

        val extracted = out.walkTopDown().filter { it.isFile }.toList()
        assertEquals("应当解出一个文件，实际=${extracted.map { it.name }}", 1, extracted.size)
        assertEquals("pax-ok", extracted.first().readText())
    }

    @Test
    fun `内容长度跨越多个块时也能对齐`() {
        val payload = "0123456789".repeat(200) // 2000 字节，非 512 整数倍
        val archive = tarGz(
            entry(name = "./a.txt", type = '0', content = payload),
            entry(name = "./b.txt", type = '0', content = "after-a"),
        )
        val out = tmp.newFolder("out4")
        TarGzExtractor.extract(archive, out)

        assertEquals(payload, File(out, "a.txt").readText())
        assertEquals("after-a", File(out, "b.txt").readText())
    }

    @Test
    fun `链接建立失败时必须让解包失败而不是静默跳过`() {
        // 2026-11-20(P1-7) 真机事故:systemd 单元的符号链接没建出来,
        // 解包却当成功返回,自检在很远的地方才报「motd-news.timer 缺失」。
        // 这里用「硬链接源不存在」复现同类失败:必须抛异常并点名是哪条。
        val archive = tarGz(
            entry(name = "./orphan.link", type = '1', linkName = "never-existed.txt"),
        )
        val out = tmp.newFolder("out5")
        val error =
            runCatching { TarGzExtractor.extract(archive, out) }.exceptionOrNull()
        assertTrue("解包应失败，实际=$error", error is IllegalStateException)
        assertTrue(
            "异常信息应点名失败的链接，实际=${error?.message}",
            error?.message?.contains("orphan.link") == true,
        )
    }
    // ── 构造 tar 的小工具 ────────────────────────────────────────────────

    private class Entry(
        val name: String,
        val type: Char,
        val content: String = "",
        val linkName: String = "",
    )

    private fun entry(name: String, type: Char, content: String = "", linkName: String = "") =
        Entry(name, type, content, linkName)

    /** 构造一条 pax 扩展头（type 'x'），记录长路径。 */
    private fun paxEntry(path: String): Entry {
        val record = "path=$path\n"
        val len = record.length + 3 // 形如 "NNN "，长度含自身
        val body = "${len.toString().padStart(3, ' ')} $record".let {
            // 长度可能因补位而变，重新计算一次
            val actual = it.length
            "${actual.toString().padStart(3, ' ')} $record"
        }
        return Entry("PaxHeaders/0", 'x', body)
    }

    private fun tarGz(vararg entries: Entry): File {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            entries.forEach { e ->
                val data = e.content.toByteArray()
                gz.write(header(e.name, e.type, data.size.toLong(), e.linkName))
                if (data.isNotEmpty()) gz.write(data)
                val pad = (512 - data.size % 512) % 512
                if (pad > 0) gz.write(ByteArray(pad))
            }
            gz.write(ByteArray(1024)) // 归档结束：两个空块
        }
        val file = tmp.newFile("sample-${System.nanoTime()}.tar.gz")
        file.writeBytes(baos.toByteArray())
        return file
    }

    private fun header(name: String, type: Char, size: Long, linkName: String): ByteArray {
        val h = ByteArray(512)
        fun put(offset: Int, value: String) {
            val bytes = value.toByteArray()
            System.arraycopy(bytes, 0, h, offset, minOf(bytes.size, 512 - offset))
        }
        put(0, name)
        put(100, octal(0b111_101_101, 8))   // mode 0755
        put(108, octal(0, 8))               // uid
        put(116, octal(0, 8))               // gid
        put(124, octal(size, 12))
        put(136, octal(0, 12))              // mtime
        put(148, "        ")                // 校验和先填空格
        h[156] = type.code.toByte()
        put(157, linkName)
        put(257, "ustar")                   // POSIX magic
        put(263, "00")
        // 校验和：所有字节相加
        val sum = h.sumOf { (it.toInt() and 0xff) }
        put(148, octal(sum.toLong(), 8))
        h[154] = 0                        // 校验和字段以 NUL 结尾
        return h
    }

    private fun octal(value: Long, width: Int): String =
        value.toString(8).padStart(width - 1, '0') + "\u0000"
}
