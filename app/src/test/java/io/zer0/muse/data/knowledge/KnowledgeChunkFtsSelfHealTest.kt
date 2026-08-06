package io.zer0.muse.data.knowledge

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-TEST-23: knowledge_chunks_fts 自愈回归测试。
 *
 * 模拟 R-DB-02 的两类前置故障:
 *  - 表不存在(no such table)
 *  - 主表被删但影子表残留(vtable constructor failed 的常见残留)
 *
 * 通过真实 DAO 接口的默认自愈包装 + fake raw 实现验证:首次失败触发
 * [KnowledgeChunkFtsSelfHealer.repair],随后重试成功。
 * 注:app 模块 Robolectric 的 SQLite 不支持 FTS4 vtable,因此不依赖真实建表。
 */
class KnowledgeChunkFtsSelfHealTest {

    private lateinit var dao: FakeFtsDao

    @Before
    fun setUp() {
        dao = FakeFtsDao()
        KnowledgeChunkFtsSelfHealer.install { dao.repair() }
    }

    @After
    fun tearDown() {
        KnowledgeChunkFtsSelfHealer.install {}
    }

    @Test
    fun insert_repairsMissingTableAndRetries() = runTest {
        dao.failInsert = true

        dao.insert("chunk-1", "doc-1", "猫咪品种大全")

        assertEquals("表缺失后应只触发一次自愈", 1, dao.repairCount)
        assertTrue("自愈后重试应成功", dao.inserted)
    }

    @Test
    fun deleteByDoc_clearsShadowTablesAndRetries() = runTest {
        dao.shadowPresent = true
        dao.failDelete = true

        dao.deleteByDoc("doc-1")

        assertEquals("影子表残留应触发一次自愈", 1, dao.repairCount)
        assertTrue("自愈后重试应成功", dao.deleted)
        assertFalse("自愈应清除影子表残留", dao.shadowPresent)
    }

    private class FakeFtsDao : KnowledgeChunkFtsDao {
        var failInsert: Boolean = false
        var failDelete: Boolean = false
        var shadowPresent: Boolean = false
        var repairCount: Int = 0
        var inserted: Boolean = false
        var deleted: Boolean = false

        override suspend fun insertRaw(chunkId: String, docId: String, content: String) {
            if (failInsert) {
                failInsert = false
                throw RuntimeException("no such table: knowledge_chunks_fts")
            }
            inserted = true
        }

        override suspend fun deleteByDocRaw(docId: String) {
            if (failDelete) {
                failDelete = false
                throw RuntimeException("no such table: knowledge_chunks_fts")
            }
            deleted = true
        }

        override suspend fun deleteByChunkIdsRaw(chunkIds: List<String>) {
            if (failDelete) {
                failDelete = false
                throw RuntimeException("no such table: knowledge_chunks_fts")
            }
        }

        override suspend fun deleteAllRaw() {
            if (failDelete) {
                failDelete = false
                throw RuntimeException("no such table: knowledge_chunks_fts")
            }
        }

        override suspend fun searchBm25(query: String, limit: Int): List<KnowledgeChunkFtsHit> = emptyList()

        fun repair() {
            repairCount++
            shadowPresent = false
        }
    }
}
