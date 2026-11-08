package io.zer0.muse.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区内容嗅探与媒体回执的契约（工作区「只能看见文件名」修复的第一、二层）。
 *
 * 钉住两类容易回退的行为：
 *  1. **常见魔数必须判对**，尤其「文本长得像二进制」和「二进制没有魔数」这两个方向；
 *  2. 回执文案里承诺的东西（随附原图 / 没有 OCR 文字 / PDF 走 parse_pdf）与实际判定一致，
 *     不能出现「说了有图其实没给」。
 *
 * 全部是纯函数，不碰 Android API，也不起协程。
 */
class WorkspaceFileSnifferTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `images are detected by magic number`() {
        assertEquals(WorkspaceFileKind.IMAGE, WorkspaceFileSniffer.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0) + ByteArray(60), 64L).kind)
        assertEquals(
            WorkspaceFileKind.IMAGE,
            WorkspaceFileSniffer.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(56), 64L).kind,
        )
        assertEquals(WorkspaceFileKind.IMAGE, WorkspaceFileSniffer.sniff("GIF89a".toByteArray() + ByteArray(58), 64L).kind)
        val webp = "RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WEBP".toByteArray() + ByteArray(52)
        assertEquals(WorkspaceFileKind.IMAGE, WorkspaceFileSniffer.sniff(webp, 64L).kind)
    }

    @Test
    fun `audio and video containers are separated by ftyp brand`() {
        // ISO-BMFF：前 4 字节是 box 长度，"ftyp" 在偏移 4，brand 在偏移 8。
        val m4a = ByteArray(4) + "ftypM4A ".toByteArray() + ByteArray(56)
        assertEquals(WorkspaceFileKind.AUDIO, WorkspaceFileSniffer.sniff(m4a, 68L).kind)
        val mp4 = ByteArray(4) + "ftypisom".toByteArray() + ByteArray(56)
        assertEquals(WorkspaceFileKind.VIDEO, WorkspaceFileSniffer.sniff(mp4, 68L).kind)
        val heic = ByteArray(4) + "ftypheic".toByteArray() + ByteArray(56)
        assertEquals(WorkspaceFileKind.IMAGE, WorkspaceFileSniffer.sniff(heic, 68L).kind)

        assertEquals(WorkspaceFileKind.AUDIO, WorkspaceFileSniffer.sniff("ID3\u0003\u0000".toByteArray() + ByteArray(58), 64L).kind)
        val wav = "RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WAVE".toByteArray() + ByteArray(52)
        assertNotEquals(WorkspaceFileKind.IMAGE, WorkspaceFileSniffer.sniff(wav, 64L).kind)
        assertEquals(WorkspaceFileKind.AUDIO, WorkspaceFileSniffer.sniff(wav, 64L).kind)
        assertEquals(WorkspaceFileKind.AUDIO, WorkspaceFileSniffer.sniff("fLaC".toByteArray() + ByteArray(60), 64L).kind)
    }

    @Test
    fun `documents archives and executables are not read as text`() {
        assertEquals(WorkspaceFileKind.DOCUMENT, WorkspaceFileSniffer.sniff("%PDF-1.7 ".toByteArray() + ByteArray(57), 64L).kind)
        assertEquals(WorkspaceFileKind.ARCHIVE, WorkspaceFileSniffer.sniff(bytes(0x50, 0x4B, 0x03, 0x04) + ByteArray(60), 64L).kind)
        assertEquals(WorkspaceFileKind.EXECUTABLE, WorkspaceFileSniffer.sniff(bytes(0x7F, 0x45, 0x4C, 0x46) + ByteArray(60), 64L).kind)

        val tar = ByteArray(257) + "ustar".toByteArray() + ByteArray(50)
        assertEquals(WorkspaceFileKind.ARCHIVE, WorkspaceFileSniffer.sniff(tar, tar.size.toLong()).kind)
    }

    @Test
    fun `plain text stays text even when the head is cut mid multibyte char`() {
        val text = ("你好，世界。这是一段中文正文。\n".repeat(400)).toByteArray(Charsets.UTF_8)
        // 模拟按字节切头 8KB 时把最后一个汉字腰斩的情形。
        val cut = text.copyOf(8 * 1024 - 1)
        assertEquals(WorkspaceFileKind.TEXT, WorkspaceFileSniffer.sniff(cut, text.size.toLong()).kind)

        val ascii = "line one\nline two\n".toByteArray(Charsets.UTF_8)
        assertEquals(WorkspaceFileKind.TEXT, WorkspaceFileSniffer.sniff(ascii, ascii.size.toLong()).kind)

        // 回归：以「汉字 + 换行」结尾的正文。以前固定砍掉尾部 3 字节会把最后一个汉字腰斩，
        // 正常中文被误判成二进制，workspace_edit 直接拒改用户的 .txt。
        val tail = "第一行\n第二行\n第三行\n".toByteArray(Charsets.UTF_8)
        assertEquals(WorkspaceFileKind.TEXT, WorkspaceFileSniffer.sniff(tail, tail.size.toLong()).kind)

        // 头部在 8191 字节处截断（不是 3 的倍数，必定切在多字节字符中间）。
        val long = "中文内容示例".repeat(3000).toByteArray(Charsets.UTF_8)
        assertEquals(WorkspaceFileKind.TEXT, WorkspaceFileSniffer.sniff(long.copyOf(8_191), long.size.toLong()).kind)
    }

    @Test
    fun `empty file is text and nul bytes are binary`() {
        assertEquals(WorkspaceFileKind.TEXT, WorkspaceFileSniffer.sniff(ByteArray(0), 0L).kind)
        val withNul = "abc".toByteArray() + byteArrayOf(0) + "def".toByteArray()
        assertEquals(WorkspaceFileKind.BINARY, WorkspaceFileSniffer.sniff(withNul, withNul.size.toLong()).kind)

        // 没有魔数、但明显不是文本的一堆字节：宁可拒绝读，也不喂乱码给模型。
        val junk = ByteArray(512) { ((it * 37 + 130) % 256).toByte() }
        assertNotEquals(WorkspaceFileKind.TEXT, WorkspaceFileSniffer.sniff(junk, 512L).kind)
    }

    @Test
    fun `utf16 text is reported as text with an encoding warning`() {
        // 用真实的 UTF-16LE 正文：FF FE 后紧跟 NUL 会是 UTF-32LE 的 BOM，
        // 拿空内容测 UTF-16 只会测到优先级而不是规则。
        val utf16 = bytes(0xFF, 0xFE) + "会议纪要".toByteArray(Charsets.UTF_16LE)
        val sniffed = WorkspaceFileSniffer.sniff(utf16, utf16.size.toLong())
        assertEquals(WorkspaceFileKind.TEXT, sniffed.kind)
        assertTrue("UTF-16 要给出可行动的说明：${sniffed.note}", sniffed.note.orEmpty().contains("UTF-16"))

        val utf32 = bytes(0xFF, 0xFE, 0x00, 0x00) + "hi".toByteArray(Charsets.UTF_32LE)
        assertTrue(
            WorkspaceFileSniffer.sniff(utf32, utf32.size.toLong()).note.orEmpty().contains("UTF-32"),
        )
    }

    @Test
    fun `extension guessing matches the sniffed kinds`() {
        assertEquals(WorkspaceFileKind.IMAGE, WorkspaceFileSniffer.guessKind("photo.JPG"))
        assertEquals(WorkspaceFileKind.AUDIO, WorkspaceFileSniffer.guessKind("voice-note.m4a"))
        assertEquals(WorkspaceFileKind.DOCUMENT, WorkspaceFileSniffer.guessKind("spec.pdf"))
        assertEquals(WorkspaceFileKind.ARCHIVE, WorkspaceFileSniffer.guessKind("bundle.zip"))
        // 没有扩展名时先当文本读，读出来的 NotText 再负责纠正（UI 与工具都是这条兜底路径）。
        assertEquals(WorkspaceFileKind.TEXT, WorkspaceFileSniffer.guessKind("shot_1699999999"))
    }

    @Test
    fun `image report promises an attachment only when one exists`() {
        val attached = WorkspaceMediaReport.image(
            ImageReportInput(
                display = "attachments/a.png", mimeType = "image/png", dimensions = 1024 to 768,
                sizeBytes = 240 * 1024, attached = true, ocrText = "识别到：会议纪要",
            ),
        )
        assertTrue(attached.contains("image |"))
        assertTrue(attached.contains("1024×768"))
        assertTrue(attached.contains("原图已读取"))
        assertTrue(attached.contains("识别到：会议纪要"))
        // 工具结果会被落库，而带图那条用户消息只进本回合历史。写「已随附」的话，
        // 重开会话后历史里没有那张图，模型会按这句话去找一张不存在的东西。
        assertFalse("已落库的文本不能承诺附件", attached.contains("已作为一条图片消息随附"))

        val skipped = WorkspaceMediaReport.image(
            ImageReportInput(
                display = "b.jpg", mimeType = "image/jpeg", dimensions = null,
                sizeBytes = 9 * 1024 * 1024, attached = false,
                attachSkipReason = "原图 9.0MB 超过随附上限 4.0MB",
            ),
        )
        assertTrue(skipped.contains("原图未读取：原图 9.0MB 超过随附上限 4.0MB"))
        assertTrue("尺寸未知时不能编一个出来", !skipped.contains("×"))
        assertTrue(skipped.contains("没有识别到文字"))

        // 没读入内容时不能说「没识别到」，那是把未尝试说成已尝试。
        val notTried = WorkspaceMediaReport.image(
            ImageReportInput(
                display = "c.png", mimeType = "image/png", dimensions = null,
                sizeBytes = 30L * 1024 * 1024, attached = false,
                attachSkipReason = "文件超过 12.0MB，不为了读内容把整张图拉进内存",
                ocrAttempted = false,
            ),
        )
        assertTrue(notTried.contains("未尝试"))
        assertFalse(notTried.contains("没有识别到文字"))
    }

    @Test
    fun `audio report distinguishes missing engine from empty transcript`() {
        val ok = WorkspaceMediaReport.audio("a.m4a", "audio/mp4", 1024, "今天三点的会议改到四点")
        assertTrue(ok.contains("转写内容："))

        val noEngine = WorkspaceMediaReport.audio("a.m4a", "audio/mp4", 1024, null, "当前语音识别引擎不支持整段音频转写")
        assertTrue(noEngine.contains("没有转写内容：当前语音识别引擎不支持整段音频转写"))
        assertTrue(noEngine.contains("这段音频的内容你看不到"))
    }

    @Test
    fun `refusal points to the right next step per kind`() {
        val pdf = WorkspaceMediaReport.refusal("x.pdf", WorkspaceFileKind.DOCUMENT, "application/pdf", 1024)
        assertTrue("PDF 要指回 parse_pdf：$pdf", pdf.contains("parse_pdf"))

        val zip = WorkspaceMediaReport.refusal("p.zip", WorkspaceFileKind.ARCHIVE, "application/zip", 4096)
        assertTrue(zip.contains("压缩包不能直接读"))

        val unknown = WorkspaceMediaReport.refusal("blob", WorkspaceFileKind.BINARY, "", 12, note = "头部含 NUL")
        assertTrue(unknown.contains("（头部含 NUL）"))
    }

    @Test
    fun `byte formatting keeps a stable unit boundary`() {
        assertEquals("512 B", WorkspaceMediaReport.formatBytes(512))
        assertEquals("1.0KB", WorkspaceMediaReport.formatBytes(1024))
        assertEquals("4.0MB", WorkspaceMediaReport.formatBytes(4L * 1024 * 1024))
    }
}
