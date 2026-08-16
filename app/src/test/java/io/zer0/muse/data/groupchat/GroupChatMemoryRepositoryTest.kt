package io.zer0.muse.data.groupchat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/** 测试用最小 Room 库:仅包含群聊记忆表。 */
@Database(
    entities = [GroupChatMemoryEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class TestMuseDb : RoomDatabase() {
    abstract fun groupChatMemoryDao(): GroupChatMemoryDao
}

/**
 * v1.0.72: 群聊记忆仓库测试。
 *
 * 覆盖 v1.0.72 新增能力(记忆中心"群聊"Tab 的数据层):
 *  - getAll: 全部群聊记忆查询(记忆中心展示)
 *  - deleteById: 单条删除(记忆中心单条删除)
 *  - deleteAll: 一键清空(记忆中心清空全部)
 *  - 原有 saveSummary / getByAssistant 回归(回复摘要写入 + prompt 注入读取)
 *
 * 用内存 Room 库,不依赖真实 DB 文件,CI 可稳定运行。
 *
 * C-31 评估:[TestMuseDb] 仅在本文件定义,全仓库其他 Room 测试复用生产 MuseDb/FactDb/MemoryDb
 * (in-memory),无同职责跨文件复制。重复度低,不值得建 testFixtures 基建,保持文件内内部类。
 * 见深度审计报告 C-31 修正说明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupChatMemoryRepositoryTest {

    private lateinit var db: androidx.room.RoomDatabase
    private lateinit var dao: GroupChatMemoryDao
    private lateinit var repo: GroupChatMemoryRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestMuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = (db as TestMuseDb).groupChatMemoryDao()
        repo = GroupChatMemoryRepository(dao)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun saveThenGetByAssistantReturnsRecent() = runBlocking {
        repo.saveSummary("chat1", "assistantA", "在群聊「测试群」中,助手A 回复:你好呀")
        repo.saveSummary("chat1", "assistantB", "在群聊「测试群」中,助手B 回复:我在")

        val forA = repo.getByAssistant("assistantA", limit = 10)
        assertEquals(1, forA.size)
        assertTrue(forA[0].summary.contains("助手A"))

        val forB = repo.getByAssistant("assistantB", limit = 10)
        assertEquals(1, forB.size)
    }

    @Test
    fun getAllReturnsAllMemoriesAcrossChatsAndAssistants() = runBlocking {
        repo.saveSummary("chat1", "assistantA", "摘要1")
        repo.saveSummary("chat1", "assistantB", "摘要2")
        repo.saveSummary("chat2", "assistantA", "摘要3")

        val all = repo.getAll()
        assertEquals(3, all.size)
        // 按时间降序:最新在前
        assertTrue(all[0].createdAt >= all[1].createdAt)
        assertTrue(all[1].createdAt >= all[2].createdAt)
    }

    @Test
    fun deleteByIdRemovesSingleMemory() = runBlocking {
        val m1 = repo.saveSummary("chat1", "assistantA", "要删除的摘要")
        repo.saveSummary("chat1", "assistantA", "保留的摘要")

        repo.deleteById(m1.id)

        val all = repo.getAll()
        assertEquals(1, all.size)
        assertEquals("保留的摘要", all[0].summary)
    }

    @Test
    fun deleteAllClearsEverything() = runBlocking {
        repo.saveSummary("chat1", "assistantA", "摘要1")
        repo.saveSummary("chat2", "assistantB", "摘要2")

        repo.deleteAll()

        assertTrue(repo.getAll().isEmpty())
    }

    @Test
    fun deleteByGroupChatClearsOnlyThatChat() = runBlocking {
        repo.saveSummary("chat1", "assistantA", "群1的")
        repo.saveSummary("chat2", "assistantB", "群2的")

        repo.deleteByGroupChat("chat1")

        val all = repo.getAll()
        assertEquals(1, all.size)
        assertEquals("群2的", all[0].summary)
    }
}
