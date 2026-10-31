package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * 媒体 token 账本与引用转换。
 *
 * 覆盖三件事：允许目录内的文件才能拿到 token、同一文件重复下发复用同一 token、
 * LRU 越限后旧 token 失效；再加上裸 base64 的落盘与识别不出内容时的剔除。
 */
class HostMediaStoreTest {

    private val workDir = Files.createTempDirectory("host-media-store").toFile()
    private val imageDir = File(workDir, "muse_images").apply { mkdirs() }
    private val voiceDir = File(workDir, "voice_messages").apply { mkdirs() }
    private val outsideDir = File(workDir, "shared_prefs").apply { mkdirs() }

    private fun store(maxEntries: Int = HostMediaStore.DEFAULT_MAX_ENTRIES) =
        HostMediaStore(imageDir, voiceDir, maxEntries = maxEntries)

    /** 写一个带媒体文件头的图片/音频文件,返回文件本身。 */
    private fun mediaFile(dir: File, name: String, bytes: ByteArray = PNG_BYTES): File =
        File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun `file reference inside allowed dir yields a token that resolves back to the file`() {
        val subject = store()
        val file = mediaFile(imageDir, "msg_0_aaa.bin")

        val token = subject.tokenForFile("file://${file.absolutePath}")
        assertNotNull(token)
        assertEquals(TOKEN_LENGTH, token!!.length)
        assertEquals(file.canonicalFile, subject.resolve(token))
    }

    @Test
    fun `voice directory is served too`() {
        val subject = store()
        val file = mediaFile(voiceDir, "2f7c.wav")

        val token = subject.tokenForFile(file.absolutePath)
        assertNotNull(token)
        assertEquals(file.canonicalFile, subject.resolve(token!!))
    }

    @Test
    fun `repeated issuance for the same file reuses one token`() {
        val subject = store()
        val file = mediaFile(imageDir, "same.bin")

        val first = subject.tokenForFile("file://${file.absolutePath}")
        val second = subject.tokenForFile("file://${file.absolutePath}")
        // 规范化后指向同一文件的另一种写法(带 ../)也必须命中同一条账。
        val third = subject.tokenForFile("file://${imageDir.absolutePath}/../muse_images/same.bin")

        assertNotNull(first)
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun `references outside the allowed media directories are rejected`() {
        val secret = mediaFile(outsideDir, "prefs.xml", "<prefs/>".toByteArray())
        val subject = store()

        assertNull("绝对路径直指媒体目录外", subject.tokenForFile("file://${secret.absolutePath}"))
        assertNull("点点逃逸出媒体目录", subject.tokenForFile("file://${imageDir.absolutePath}/../shared_prefs/prefs.xml"))
        assertNull("空白引用", subject.tokenForFile("   "))
        assertNull("尚未落盘的文件", subject.tokenForFile("file://${File(imageDir, "not-written.png").absolutePath}"))
    }

    @Test
    fun `token stops resolving once the file is cleaned up`() {
        val subject = store()
        val file = mediaFile(imageDir, "gone.bin")
        val token = subject.tokenForFile(file.absolutePath)
        assertNotNull(token)
        assertTrue(file.delete())

        assertNull("文件被清理后不该再解析出结果", subject.resolve(token!!))
    }

    @Test
    fun `lru cap turns the least used token into a miss`() {
        val subject = store(maxEntries = 2)
        val first = subject.tokenForFile(mediaFile(imageDir, "one.bin").absolutePath)
        val third = subject.tokenForFile(mediaFile(imageDir, "two.bin").absolutePath)
        assertNotNull(first)
        assertNotNull(third)
        subject.tokenForFile(mediaFile(imageDir, "three.bin").absolutePath)

        assertNull("越限后最久未用的 token 应失效", subject.resolve(first!!))
        assertNotNull(subject.resolve(third!!))
    }

    @Test
    fun `resolving a token keeps it ahead of the eviction line`() {
        val subject = store(maxEntries = 2)
        val first = subject.tokenForFile(mediaFile(imageDir, "one.bin").absolutePath)
        val second = subject.tokenForFile(mediaFile(imageDir, "two.bin").absolutePath)
        assertNotNull(first)
        assertNotNull(second)
        // 读一次把 first 提到最近使用,再塞第三条时被淘汰的是 second。
        subject.resolve(first!!)
        val third = subject.tokenForFile(mediaFile(imageDir, "three.bin").absolutePath)

        assertNotNull(subject.resolve(first))
        assertNull(subject.resolve(second!!))
        assertNotNull(subject.resolve(third!!))
    }

    @Test
    fun `seeded mapping that points outside the media dirs resolves to nothing`() {
        val secret = mediaFile(outsideDir, "prefs.xml", "<prefs/>".toByteArray())
        val subject = store()
        subject.seedMapping(SEED_TOKEN, secret.absolutePath)

        assertNull(subject.resolve(SEED_TOKEN))
    }

    @Test
    fun `bare base64 image is materialized into the media dir and reuses one token`() {
        val subject = store()
        val payload = Base64.getEncoder().encodeToString(PNG_BYTES)

        val token = subject.tokenForInlineData(payload, null, MESSAGE_ID)
        assertNotNull(token)
        assertEquals(token, subject.tokenForInlineData(payload, null, MESSAGE_ID))

        val file = subject.resolve(token!!)
        assertNotNull(file)
        assertTrue("落盘文件必须在图片目录内", file!!.parentFile == imageDir.canonicalFile)
        assertTrue("落盘内容应与解码后的字节一致", PNG_BYTES.contentEquals(file.readBytes()))
        // 文件名以消息 id 开头:删消息时 MessageImageStore 的 {messageId}_ 前缀扫描会连它一起清掉。
        assertTrue("内联落盘文件应以归属消息 id 为前缀: ${file.name}", file.name.startsWith("${MESSAGE_ID}_"))
    }

    @Test
    fun `data uri declares the type when the payload itself is not recognizable`() {
        val subject = store()
        val payload = Base64.getEncoder().encodeToString("raw rgb blob, no magic".toByteArray() + ByteArray(16))

        val token = subject.tokenForInlineData(payload, "image/png", MESSAGE_ID)
        assertNotNull(token)
        val file = subject.resolve(token!!)
        assertNotNull(file)
        assertEquals("image/png", HostMediaContentType.of(file!!))
    }

    @Test
    fun `payload that is not a recognizable medium is dropped`() {
        val subject = store()
        val payload = Base64.getEncoder().encodeToString("just some plain text, definitely not an image".toByteArray())

        assertNull(subject.tokenForInlineData(payload, null, MESSAGE_ID))
        assertNull(subject.tokenForInlineData("too short", null, MESSAGE_ID))
    }

    @Test
    fun `links swap every usable form for a media route and keep plain http urls`() {
        val subject = store()
        val links = HostMediaLinks(subject)
        val file = mediaFile(imageDir, "card.bin")
        val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES)

        val mapped = links.links(
            listOf(
                "file://${file.absolutePath}",
                dataUri,
                "https://cdn.example.com/a.png",
                "not a media reference at all",
                "",
            ),
            MESSAGE_ID,
        )

        assertEquals(3, mapped.size)
        assertTrue(mapped[0].startsWith(HostMediaLinks.MEDIA_PATH_PREFIX))
        assertTrue(mapped[1].startsWith(HostMediaLinks.MEDIA_PATH_PREFIX))
        assertEquals("https://cdn.example.com/a.png", mapped[2])
        assertEquals(file.canonicalFile, subject.resolve(mapped[0].removePrefix(HostMediaLinks.MEDIA_PATH_PREFIX)))
    }

    @Test
    fun `voice link is null for blank and for an unusable reference`() {
        val links = HostMediaLinks(store())

        assertNull(links.voiceLink(null, MESSAGE_ID))
        assertNull(links.voiceLink("", MESSAGE_ID))
        assertNull(links.voiceLink("file://${File(outsideDir, "nope.mp3").absolutePath}", MESSAGE_ID))
        assertNotNull(links.voiceLink(mediaFile(voiceDir, "say.m4a", M4A_BYTES).absolutePath, MESSAGE_ID))
    }

    @Test
    fun `content type comes from the file header first and the extension second`() {
        assertEquals("image/png", HostMediaContentType.of(mediaFile(imageDir, "a.png", PNG_BYTES)))
        assertEquals("image/jpeg", HostMediaContentType.of(mediaFile(imageDir, "a.jpg", JPEG_BYTES)))
        assertEquals("image/gif", HostMediaContentType.of(mediaFile(imageDir, "a.gif", GIF_BYTES)))
        assertEquals("image/webp", HostMediaContentType.of(mediaFile(imageDir, "a.webp", WEBP_BYTES)))
        // .bin 落盘的图片没有扩展名可用,只能靠文件头。
        assertEquals("image/jpeg", HostMediaContentType.of(mediaFile(imageDir, "legacy.bin", JPEG_BYTES)))
        assertEquals("audio/mp4", HostMediaContentType.of(mediaFile(voiceDir, "a.m4a", M4A_BYTES)))
        assertEquals("audio/aac", HostMediaContentType.of(mediaFile(voiceDir, "a.aac", AAC_BYTES)))
        assertEquals("audio/mpeg", HostMediaContentType.of(mediaFile(voiceDir, "a.mp3", MP3_BYTES)))
        // 认不出文件头时退到扩展名,不至于把 mp3 当二进制附件发出去。
        assertEquals("audio/mpeg", HostMediaContentType.of(mediaFile(voiceDir, "fallback.mp3", "junk".toByteArray())))
        assertEquals(
            HostMediaContentType.OCTET_STREAM,
            HostMediaContentType.of(mediaFile(voiceDir, "unknown.xyz", "junk".toByteArray())),
        )
    }

    private companion object {
        const val TOKEN_LENGTH = 32
        const val SEED_TOKEN = "seededtoken00000000000000000000"
        const val MESSAGE_ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24)
        val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(20)
        val GIF_BYTES = "GIF89a".toByteArray() + ByteArray(20)
        val WEBP_BYTES = "RIFF".toByteArray() + byteArrayOf(0x10, 0, 0, 0) + "WEBP".toByteArray() + ByteArray(8)
        val M4A_BYTES = byteArrayOf(0, 0, 0, 0x20.toByte()) + "ftyp".toByteArray() + "M4A ".toByteArray() + ByteArray(16)
        val AAC_BYTES = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(), 0x80.toByte()) + ByteArray(20)
        val MP3_BYTES = "ID3".toByteArray() + byteArrayOf(0x03, 0x00) + ByteArray(24)
    }
}
