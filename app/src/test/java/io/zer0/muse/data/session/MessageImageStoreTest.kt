package io.zer0.muse.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * R-DB-04: MessageImageStore base64 落盘与 LRU 缓存测试。
 */
class MessageImageStoreTest {

    @Test
    fun `long base64 persists to file and reads back`() {
        val dir = Files.createTempDirectory("muse-image-store").toFile()
        val store = MessageImageStore(dir)
        val encoded = Base64.getEncoder().encodeToString(ByteArray(1024) { 1 })

        val persistable = store.toPersistable("m1", listOf(encoded))
        assertEquals(1, persistable.size)
        assertTrue(persistable[0].startsWith("file://"))
        assertEquals(listOf(encoded), store.toBase64List(persistable))
    }

    @Test
    fun `short base64 stays inline`() {
        val dir = Files.createTempDirectory("muse-image-short").toFile()
        val store = MessageImageStore(dir)
        val persistable = store.toPersistable("m2", listOf("abc"))
        assertEquals(listOf("abc"), persistable)
        assertEquals(listOf("abc"), store.toBase64List(persistable))
    }

    @Test
    fun `lru cache serves after file deleted`() {
        val dir = Files.createTempDirectory("muse-image-cache").toFile()
        val store = MessageImageStore(dir)
        val encoded = Base64.getEncoder().encodeToString(ByteArray(1024) { 2 })
        val persistable = store.toPersistable("m3", listOf(encoded))

        assertEquals(listOf(encoded), store.toBase64List(persistable))
        val path = persistable[0].removePrefix("file://")
        assertTrue(File(path).delete())
        assertEquals(listOf(encoded), store.toBase64List(persistable))
    }

    @Test
    fun `missing file returns empty string`() {
        val dir = Files.createTempDirectory("muse-image-missing").toFile()
        val store = MessageImageStore(dir)
        assertEquals(listOf(""), store.toBase64List(listOf("file:///nonexistent/image.bin")))
    }
}
