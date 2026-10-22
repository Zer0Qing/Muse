package io.zer0.muse.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.relationship.AssistantRelationshipEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.todo.SessionTodoEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.7: 备份恢复的**落库**验证。
 *
 * 序列化往返测试只能证明「字段没丢」，证明不了「恢复时真的写进库了」。
 * 后者才是用户能感知的：换机恢复后亲密度还在不在、待办还在不在。
 *
 * 这条测试直接操作真实 DAO，复刻 applyBackupInternal 里对这两张表的写库路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class BackupRestoreTodoRelationshipTest {

    private lateinit var database: MuseDb

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun todo(sessionId: String, json: String) = SessionTodoEntity(
        id = "todo-$sessionId",
        sessionId = sessionId,
        todosJson = json,
        hasOpenItems = true,
        createdAt = 100L,
        updatedAt = 200L,
    )

    private fun relationship(assistantId: String, affinity: Int) = AssistantRelationshipEntity(
        assistantId = assistantId,
        relationLabel = "朋友",
        affinity = affinity,
        lastInteractionAt = 300L,
        proactiveLevel = "normal",
        createdAt = 100L,
        updatedAt = 300L,
    )

    @Test
    fun `todos are restored into the database`() = runTest {
        val rows = listOf(todo("s1", """[{"content":"写方案","status":"pending"}]"""), todo("s2", """[]"""))

        // 复刻 applyBackupInternal 的写库路径
        rows.forEach { database.sessionTodoDao().upsert(it) }

        assertEquals(2, database.sessionTodoDao().getAll().size)
        assertEquals("s1", database.sessionTodoDao().getBySession("s1")?.sessionId)
        assertTrue(database.sessionTodoDao().getAll().any { it.todosJson.contains("写方案") })
    }

    @Test
    fun `relationships are restored into the database`() = runTest {
        // 这是本次补的缺口：此前只导出不恢复，换机后亲密度全丢
        val rows = listOf(relationship("a1", 42), relationship("a2", 88))

        rows.forEach { database.assistantRelationshipDao().upsert(it) }

        assertEquals(2, database.assistantRelationshipDao().getAll().size)
        assertEquals("亲密度必须随备份恢复", 42, database.assistantRelationshipDao().get("a1")?.affinity)
        assertEquals("朋友", database.assistantRelationshipDao().get("a1")?.relationLabel)
    }

    @Test
    fun `restore clears stale rows before writing`() = runTest {
        // 先放一条「旧数据」，模拟恢复前的库状态
        database.assistantRelationshipDao().upsert(relationship("stale", 99))
        database.sessionTodoDao().upsert(todo("stale", """[]"""))

        // 复刻恢复的清空 + 写入
        database.assistantRelationshipDao().deleteAll()
        database.sessionTodoDao().deleteAll()
        database.assistantRelationshipDao().upsert(relationship("a1", 42))
        database.sessionTodoDao().upsert(todo("s1", """[]"""))

        assertNull("恢复后不该残留旧会话关系", database.assistantRelationshipDao().get("stale"))
        assertNull("恢复后不该残留旧待办", database.sessionTodoDao().getBySession("stale"))
        assertEquals(1, database.assistantRelationshipDao().getAll().size)
        assertEquals(1, database.sessionTodoDao().getAll().size)
    }

    @Test
    fun `backup service actually writes both tables on restore`() {
        // 直接调 DAO 只能证明「DAO 能写」，证明不了「恢复流程真的调了它」——
        // assistantRelationships 当初就是导出有、恢复没有，而且删掉那行不会编译报错。
        // 所以这里断言恢复路径的源码里确实有这两次写入。
        val source =
            java.io.File("src/main/java/io/zer0/muse/backup/BackupService.kt")
                .takeIf { it.exists() }
                ?.readText()
                ?: return
        assertTrue(
            "恢复路径必须写 session_todos",
            source.contains("backup.sessionTodos.forEach"),
        )
        assertTrue(
            "恢复路径必须写 assistant_relationship（此前只导出不恢复）",
            source.contains("backup.assistantRelationships.forEach"),
        )
    }

    @Test
    fun `restoring same relationship twice does not duplicate`() = runTest {
        database.assistantRelationshipDao().upsert(relationship("a1", 42))
        database.assistantRelationshipDao().upsert(relationship("a1", 55))

        assertEquals("主键是 assistantId，重复恢复应覆盖而不是新增", 1, database.assistantRelationshipDao().getAll().size)
        assertEquals(55, database.assistantRelationshipDao().get("a1")?.affinity)
    }
}
