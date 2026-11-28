package io.zer0.memory.ai

import io.zer0.common.ProcessWriteGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * AutoSave 幂等指纹缓存：有界、原子持久化、只存摘要哈希、按结构化键隔离。
 */
class AutoSaveFingerprintCacheTest {

    private val tempDir: File = Files.createTempDirectory("autosave-fp").toFile()

    @After
    fun cleanUp() {
        ProcessWriteGate.end()
        tempDir.deleteRecursively()
    }

    private fun key(
        sessionId: String = "session-1",
        assistantId: String = "default",
        scope: String = "main",
        spaceId: String = "default",
    ) = AutoSaveFingerprintCache.Key(assistantId, scope, spaceId, sessionId)

    private fun hash(seed: String): String = java.security.MessageDigest.getInstance("MD5")
        .digest(seed.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Test
    fun `records survive a restart`() {
        val file = File(tempDir, "fingerprints.json")
        val fp = hash("history-A")
        AutoSaveFingerprintCache(file).markProcessed(key(), fp)

        val reloaded = AutoSaveFingerprintCache(file)
        assertTrue(reloaded.isProcessed(key(), fp))
        assertFalse(reloaded.isProcessed(key(), hash("history-B")))
    }

    @Test
    fun `persisted file holds only digests, never the history`() {
        val file = File(tempDir, "fingerprints.json")
        val fp = hash("用户说他下周三有考试")
        AutoSaveFingerprintCache(file).markProcessed(key(), fp)

        val text = file.readText()
        assertTrue("应记录摘要", text.contains(fp))
        assertFalse("不得落对话原文", text.contains("考试"))
        assertFalse(text.contains("下周三"))
    }

    @Test
    fun `cache is bounded`() {
        val cache = AutoSaveFingerprintCache(file = null, maxEntries = 4)
        repeat(10) { i ->
            cache.markProcessed(key(sessionId = "s$i"), hash("h$i"))
        }
        assertEquals(4, cache.size())
    }

    @Test
    fun `same session under different owners is tracked separately`() {
        val cache = AutoSaveFingerprintCache(file = null)
        val fp = hash("history-A")
        cache.markProcessed(key(assistantId = "default"), fp)

        assertTrue(cache.isProcessed(key(assistantId = "default"), fp))
        assertFalse(
            "换拥有者/空间就是另一个库，不能算已处理",
            cache.isProcessed(key(assistantId = "writer", scope = "writer", spaceId = "work"), fp),
        )
    }

    @Test
    fun `assistant invalidation drops only that assistant and persists`() {
        val file = File(tempDir, "fingerprints.json")
        val cache = AutoSaveFingerprintCache(file)
        val fp = hash("history-A")
        cache.markProcessed(key(assistantId = "writer", scope = "writer"), fp)
        cache.markProcessed(key(assistantId = "default"), fp)

        assertEquals(1, cache.invalidateAssistant("writer"))
        assertFalse(cache.isProcessed(key(assistantId = "writer", scope = "writer"), fp))
        assertTrue(cache.isProcessed(key(assistantId = "default"), fp))
        assertEquals(1, AutoSaveFingerprintCache(file).size())
        assertEquals(0, cache.invalidateAssistant(" "))
    }

    @Test
    fun `restore window invalidates entries`() {
        val file = File(tempDir, "fingerprints.json")
        val cache = AutoSaveFingerprintCache(file)
        val fp = hash("history-A")
        cache.markProcessed(key(), fp)
        assertFalse(cache.invalidateIfRestored())

        // 恢复窗口完整开合：库已被替换，旧指纹不再代表当前库的内容。
        assertTrue(ProcessWriteGate.begin())
        ProcessWriteGate.end()

        assertTrue(cache.invalidateIfRestored())
        assertFalse(cache.isProcessed(key(), fp))
        assertEquals("磁盘上也要清空", 0, AutoSaveFingerprintCache(file).size())
        assertFalse("同一代不重复作废", cache.invalidateIfRestored())
    }

    @Test
    fun `invalidate clears explicit reset`() {
        val cache = AutoSaveFingerprintCache(file = null)
        val fp = hash("history-A")
        cache.markProcessed(key(), fp)
        cache.invalidate()
        assertFalse(cache.isProcessed(key(), fp))
    }

    @Test
    fun `session invalidation drops only that session and persists`() {
        val file = File(tempDir, "fingerprints.json")
        val cache = AutoSaveFingerprintCache(file)
        val doomedFp = hash("history-doomed")
        val keptFp = hash("history-kept")
        cache.markProcessed(key(sessionId = "s-doomed"), doomedFp)
        // 同一个会话在另一个库里也算另一条记录，但 sessionId 相同，应一起作废。
        cache.markProcessed(key(sessionId = "s-doomed", assistantId = "writer", scope = "writer"), doomedFp)
        cache.markProcessed(key(sessionId = "s-kept"), keptFp)

        assertEquals(2, cache.invalidateSession("s-doomed"))

        assertFalse("被删会话不得再命中已处理", cache.isProcessed(key(sessionId = "s-doomed"), doomedFp))
        assertTrue("别的会话不能被顺手清掉", cache.isProcessed(key(sessionId = "s-kept"), keptFp))
        assertEquals("磁盘上也要同步", 1, AutoSaveFingerprintCache(file).size())
        assertEquals("没命中时不重写磁盘也不报错", 0, cache.invalidateSession("s-doomed"))
        assertEquals("空 id 不做任何事", 0, cache.invalidateSession("   "))
    }

    @Test
    fun `corrupted file degrades to empty cache`() {
        val file = File(tempDir, "fingerprints.json")
        file.writeText("{ not json")
        val cache = AutoSaveFingerprintCache(file)
        assertEquals(0, cache.size())
        // 之后仍能正常写入并覆盖坏文件
        val fp = hash("history-A")
        cache.markProcessed(key(), fp)
        assertTrue(AutoSaveFingerprintCache(file).isProcessed(key(), fp))
    }
}
