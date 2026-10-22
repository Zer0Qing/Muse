package io.zer0.muse.data.todo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.tools.TodoTool
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话待办持久化的测试。
 *
 * 助手自审第 2、17 条：「跨会话的待办没有闭环机制…缺一个『未完成话题台账』」。
 * 此前待办只在 `TodoTool.sessionTodos` 内存里，切会话即清、重启即丢。
 *
 * 这里钉住三件事：写入能读回、跨会话互不干扰、hasOpenItems 随状态变化。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionTodoRepositoryTest {

    private lateinit var database: MuseDb
    private lateinit var repository: SessionTodoRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        repository = SessionTodoRepository(database.sessionTodoDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun list(vararg items: TodoTool.TodoItem) = TodoTool.TodoList(items.toList())

    private fun item(content: String, status: String) = TodoTool.TodoItem(content = content, status = status)

    @Test
    fun `saved list can be read back`() = runTest {
        repository.save("s1", list(item("写方案", "pending"), item("发邮件", "completed")))

        val loaded = repository.load("s1")

        assertEquals(2, loaded.todos.size)
        assertEquals("写方案", loaded.todos[0].content)
        assertEquals("completed", loaded.todos[1].status)
    }

    @Test
    fun `sessions do not see each other's todos`() = runTest {
        repository.save("s1", list(item("A 的事", "pending")))
        repository.save("s2", list(item("B 的事", "pending")))

        assertEquals("A 的事", repository.load("s1").todos.single().content)
        assertEquals("B 的事", repository.load("s2").todos.single().content)
    }

    @Test
    fun `loading an unknown session yields an empty list`() = runTest {
        assertTrue(repository.load("never-used").todos.isEmpty())
    }

    @Test
    fun `open items are reported across sessions`() = runTest {
        repository.save("s1", list(item("没做完", "pending")))
        repository.save("s2", list(item("做完了", "completed")))

        val open = repository.sessionsWithOpenItems()

        assertEquals(listOf("s1"), open.map { it.sessionId })
        assertEquals(1, repository.countSessionsWithOpenItems())
    }

    @Test
    fun `in progress counts as open`() = runTest {
        repository.save("s1", list(item("正在做", "in_progress")))

        assertEquals(1, repository.countSessionsWithOpenItems())
    }

    @Test
    fun `completing everything clears the open flag`() = runTest {
        repository.save("s1", list(item("做完了", "completed")))

        assertEquals(0, repository.countSessionsWithOpenItems())
    }

    @Test
    fun `saving replaces the whole list`() = runTest {
        repository.save("s1", list(item("旧的", "pending")))
        repository.save("s1", list(item("新的", "pending")))

        val loaded = repository.load("s1")

        assertEquals(1, loaded.todos.size)
        assertEquals("新的", loaded.todos.single().content)
    }

    @Test
    fun `saving keeps the original createdAt`() = runTest {
        repository.save("s1", list(item("a", "pending")))
        val first = database.sessionTodoDao().getBySession("s1")!!
        // 让第二次写入的时间明显晚于第一次
        Thread.sleep(5)
        repository.save("s1", list(item("b", "pending")))
        val second = database.sessionTodoDao().getBySession("s1")!!

        assertEquals("重建同一会话的待办不该刷新创建时间", first.createdAt, second.createdAt)
        assertTrue(second.updatedAt >= first.updatedAt)
    }

    @Test
    fun `unknown status is treated as open`() = runTest {
        repository.save("s1", list(item("状态拼错了", "done")))

        assertEquals("未知状态不能当成已完成", 1, repository.countSessionsWithOpenItems())
    }

    @Test
    fun `deleting a session removes its todos`() = runTest {
        repository.save("s1", list(item("a", "pending")))
        repository.delete("s1")

        assertNull(database.sessionTodoDao().getBySession("s1"))
        assertFalse(repository.load("s1").todos.isNotEmpty())
    }

    @Test
    fun `blank session id is ignored instead of writing a junk row`() = runTest {
        repository.save("", list(item("a", "pending")))

        assertEquals(0, database.sessionTodoDao().countSessionsWithOpenItems())
    }

    @Test
    fun `corrupted json does not crash and does not delete the row`() = runTest {
        database.sessionTodoDao().upsert(
            SessionTodoEntity(
                id = "x",
                sessionId = "s-bad",
                todosJson = "{not valid json",
                hasOpenItems = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val loaded = repository.load("s-bad")

        assertTrue("解析失败应回退空清单而不是崩溃", loaded.todos.isEmpty())
        assertTrue("原记录不能因为解析失败被删", database.sessionTodoDao().getBySession("s-bad") != null)
    }
}
