package io.zer0.muse.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * 导入草稿仓库（表情包重做 P3）。
 *
 * 这里锁的是**不留垃圾**这一条：草稿目录如果被遗弃，用户看不到、也删不掉，
 * 只会发现"内部存储不知为何多了几百 MB"。所以过期清理与取消删除都要有断言。
 */
class StickerDraftStoreTest {

    private fun newRoot(): File =
        kotlin.io.path.createTempDirectory("muse-drafts-" + UUID.randomUUID().toString().take(6)).toFile()

    private fun seed(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File {
        val dir = newRoot().also { it.mkdirs() }
        return File(dir, name).apply { writeBytes(bytes) }
    }

    @Test
    fun `a created draft keeps files metadata and survives a reload`() {
        val store = StickerDraftStore(newRoot())
        val a = seed("a.png")
        val b = seed("b.png")

        val draft = store.create("猫猫", listOf(DraftSeed("a.png", a, "小猫歪头"), DraftSeed("b.png", b)))

        assertNotNull("应当建成草稿", draft)
        assertEquals(2, draft!!.items.size)
        assertEquals("猫猫", draft.category)
        assertEquals("配文要跟着走", "小猫歪头", draft.items.first().caption)
        assertTrue("文件要在草稿目录里", draft.items.all { it.file.isFile })

        val reloaded = store.load(draft.id)
        assertEquals("重新加载条目数一致", 2, reloaded!!.items.size)
        assertEquals("重新加载配文一致", "小猫歪头", reloaded.items.first().caption)
    }

    @Test
    fun `empty seed list creates nothing`() {
        val store = StickerDraftStore(newRoot())

        assertNull(store.create("猫猫", emptyList()))
    }

    @Test
    fun `delete removes the whole draft directory`() {
        val store = StickerDraftStore(newRoot())
        val draft = store.create("猫猫", listOf(DraftSeed("a.png", seed("a.png"))))
        requireNotNull(draft)

        store.delete(draft.id)

        assertNull(store.load(draft.id))
        assertTrue("目录要真的没了", store.list().isEmpty())
    }

    @Test
    fun `stale drafts are purged while fresh ones stay`() {
        val root = newRoot()
        val store = StickerDraftStore(root)
        val fresh = store.create("猫猫", listOf(DraftSeed("a.png", seed("a.png"))))
        requireNotNull(fresh)
        // 造一个"上次没导完"的：改小它的创建时间
        val recordFile = File(File(root, fresh.id), "draft.json")
        val text = recordFile.readText()
        recordFile.writeText(text.replaceFirst(Regex("\"createdAtMillis\":\\d+"), "\"createdAtMillis\":1"))

        val removed = store.purgeStale(now = System.currentTimeMillis())

        assertEquals("遗弃的应被清掉", 1, removed)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `constructing the store cleans up directories nobody owns`() {
        // 这条断言的是"接线方式"：清理挂在构造上，不依赖某个启动调用点
        val root = newRoot()
        File(root, "orphan_dir").apply { mkdirs() }

        val store = StickerDraftStore(root)

        assertTrue("构造时就该清掉", store.list().isEmpty())
        assertTrue(!File(root, "orphan_dir").exists())
    }

    @Test
    fun `explicit purge still works for long-lived sessions`() {
        val root = newRoot()
        val store = StickerDraftStore(root)
        // 会话中途冒出来的孤儿目录：构造时那次清理管不到它，才需要显式 purge
        File(root, "later_orphan").apply { mkdirs() }

        val removed = store.purgeStale()

        assertEquals(1, removed)
        assertTrue(!File(root, "later_orphan").exists())
    }
}
