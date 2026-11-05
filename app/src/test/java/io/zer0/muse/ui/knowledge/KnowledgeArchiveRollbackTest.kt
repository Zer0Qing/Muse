package io.zer0.muse.ui.knowledge

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识包导入的回滚清理：**没删干净必须说得出、也测得出**。
 *
 * 原写法是 `resultOf { deps.kbDao.delete(kbId) }` 直接丢结果 ——
 * 删失败时列表里留下一个"刚建好、点开什么都没有"的条目，
 * 而界面提示只说「压缩包内没有可导入的文档」，用户两句对不上只能以为程序坏了。
 *
 * 现在 `rollbackKnowledgeBase` 返回是否删干净（调用方据此换文案）并留痕。
 * 这里只测这一层（不硬凑整条导入管线）：真假 DAO 一给，两条语义就都覆盖到了。
 */
class KnowledgeArchiveRollbackTest {

    private val seen = mutableListOf<Pair<String, String>>()

    @After
    fun tearDown() {
        Logger.sink = null
        seen.clear()
    }

    private fun capture() {
        seen.clear()
        Logger.sink = { _, tag, msg, _ -> seen += tag to msg }
    }

    private fun ourLogs() = seen.filter { it.first == "KbArchiveImport" }

    private class FakeKbDao(private val failOnDelete: Boolean) : KnowledgeBaseDao {
        var deletedIds: MutableList<String> = mutableListOf()
        var upserted: MutableList<KnowledgeBaseEntity> = mutableListOf()

        override fun observeAll(): Flow<List<KnowledgeBaseEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<KnowledgeBaseEntity> = emptyList()
        override suspend fun getById(id: String): KnowledgeBaseEntity? = null
        override suspend fun upsert(kb: KnowledgeBaseEntity) {
            upserted += kb
        }
        override suspend fun count(): Int = 0
        var deleteAllCalled = false
        override suspend fun deleteAll() {
            deleteAllCalled = true
        }

        override suspend fun delete(id: String) {
            if (failOnDelete) error("DB is locked")
            deletedIds += id
        }
    }

    @Test
    fun `删除成功时返回 true 且不产生日志噪声`() = runTest {
        capture()
        val dao = FakeKbDao(failOnDelete = false)

        val cleaned = rollbackKnowledgeBase(dao, "kb-123", "压缩包内无可导入内容")

        assertTrue("删成功要如实报 true（调用方据此显示普通提示）", cleaned)
        assertEquals("只该删这一条临时知识库", listOf("kb-123"), dao.deletedIds)
        assertTrue("回滚不该顺手写条目", dao.upserted.isEmpty())
        assertFalse("也不该走 deleteAll 那种大范围清理", dao.deleteAllCalled)
        assertTrue("清理成功不该留日志：${ourLogs()}", ourLogs().isEmpty())
    }

    @Test
    fun `删除失败时返回 false 并留下能定位的日志`() = runTest {
        capture()
        val dao = FakeKbDao(failOnDelete = true)

        val cleaned = rollbackKnowledgeBase(dao, "kb-456", "导入失败回滚")

        assertFalse("删不掉必须报 false，否则界面会承诺一个没发生的清理", cleaned)
        val log = ourLogs().singleOrNull()
        assertNotNull("回滚失败却一句日志都没有，就是这次要修的静默：$seen", log)
        assertTrue("要带 kbId：${log!!.second}", log.second.contains("kb-456"))
        assertTrue("要说明是哪一步的回滚：${log.second}", log.second.contains("导入失败回滚"))
        assertTrue("要带原因：${log.second}", log.second.contains("DB is locked"))
    }
}
