package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v13 (T4-1): 事实修订历史测试。
 *  - 关键记忆(importance≥1)update 记录修订
 *  - 合并记录修订(被合并方原文可追溯)
 *  - 普通事实(importance=0)不记录
 *  - 回滚恢复旧值
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactRevisionTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao
    private lateinit var revisionDao: FactRevisionDao
    private lateinit var store: FactStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.factDao()
        revisionDao = db.factRevisionDao()
        store = FactStore(dao, db, revisionDao = revisionDao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `update of important fact records revision`() = runTest {
        val id = store.add(FactStore.Fact(fact = "下周三交论文初稿", importance = 1, entityKey = "学业"))
        store.update(id, "下周五交论文初稿")

        val revisions = store.getRevisions(id)
        assertEquals("重要事实更新应记录修订", 1, revisions.size)
        assertEquals("旧值应保留", "下周三交论文初稿", revisions[0].oldContent)
        assertEquals("新值应记录", "下周五交论文初稿", revisions[0].newContent)
        assertTrue("reason 应为 update", revisions[0].reason.contains("update"))
    }

    @Test
    fun `merge records revision with merged wording`() = runTest {
        val id1 = store.add(FactStore.Fact(fact = "张三喜欢摄影", entityKey = "张三"))
        store.add(FactStore.Fact(fact = "张三老师喜欢摄影", entityKey = "张三"))

        val revisions = store.getRevisions(id1)
        assertTrue("合并应记录修订", revisions.isNotEmpty())
        assertTrue("reason 应为 merge", revisions[0].reason.contains("merge"))
    }

    @Test
    fun `ordinary fact update does not record revision`() = runTest {
        // 无重要关键词、无实体键的普通事实
        val id = store.add(FactStore.Fact(fact = "用户养了一只猫"))
        store.update(id, "用户养了一只橘猫")

        assertEquals("普通事实不记录修订", 0, store.getRevisions(id).size)
    }

    @Test
    fun `revert restores old content`() = runTest {
        val id = store.add(FactStore.Fact(fact = "下周三交论文初稿", importance = 1, entityKey = "学业"))
        store.update(id, "下周五交论文初稿")
        val revisions = store.getRevisions(id)
        assertTrue("应有修订", revisions.isNotEmpty())

        val reverted = store.revertToRevision(id, revisions[0].id)
        assertTrue("回滚应成功", reverted)
        assertEquals("内容恢复旧值", "下周三交论文初稿", store.getById(id)!!.fact)
    }
}
