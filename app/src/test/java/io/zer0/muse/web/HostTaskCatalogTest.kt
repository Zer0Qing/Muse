package io.zer0.muse.web

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自动化只读命令：真 Room + 真 DAO，验「启用与停用的任务都在」「停用任务不给下次时间」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostTaskCatalogTest {

    private lateinit var database: MuseDb
    private lateinit var source: DaoHostTaskSource

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        source = DaoHostTaskSource(
            taskDao = database.scheduledTaskDao(),
            executionDao = database.scheduledTaskExecutionDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedTask(
        id: String,
        name: String,
        cron: String,
        enabled: Boolean,
        nextRunAt: Long,
        lastRunAt: Long = 0L,
    ) {
        database.scheduledTaskDao().upsert(
            ScheduledTaskEntity(
                id = id,
                name = name,
                cronExpr = cron,
                enabled = enabled,
                nextRunAt = nextRunAt,
                lastRunAt = lastRunAt,
                createdAt = 1L,
                updatedAt = 2L,
            ),
        )
    }

    private suspend fun seedExecution(id: String, taskId: String, executedAt: Long, status: String) {
        database.scheduledTaskExecutionDao().insert(
            ScheduledTaskExecutionEntity(id = id, taskId = taskId, executedAt = executedAt, status = status),
        )
    }

    @Test
    fun `tasks list keeps disabled tasks and drops their next run time`() = runTest {
        seedTask(
            id = "task-on",
            name = "晨间简报",
            cron = "0  9 * * *",
            enabled = true,
            nextRunAt = 5_000L,
            lastRunAt = 4_000L,
        )
        // 停用任务在库里还留着停用前的 next_run_at，回执必须把它按掉。
        seedTask(
            id = "task-off",
            name = "周报",
            cron = "0 20 * * 5",
            enabled = false,
            nextRunAt = 9_000L,
        )

        val event = HostTaskReads.listEvent(source.tasks(), requestId = "req-tasks")

        assertEquals(HostTaskReads.TYPE_LIST, event.type)
        assertEquals("req-tasks", event.requestId)
        assertNull(event.error)
        assertEquals(2, event.tasks.size)

        val enabledTask = event.tasks.first { it.id == "task-on" }
        assertEquals("晨间简报", enabledTask.name)
        // cron 原样下发，不做归一（连双空格一起留着）。
        assertEquals("0  9 * * *", enabledTask.cron)
        assertEquals(true, enabledTask.enabled)
        assertEquals(5_000L, enabledTask.nextRunAt)
        assertEquals(4_000L, enabledTask.lastRunAt)

        val disabledTask = event.tasks.first { it.id == "task-off" }
        assertEquals("周报", disabledTask.name)
        assertEquals("0 20 * * 5", disabledTask.cron)
        assertEquals(false, disabledTask.enabled)
        assertNull(disabledTask.nextRunAt)
        assertNull(disabledTask.lastRunAt)
        assertNull(disabledTask.lastResult)
    }

    @Test
    fun `tasks list reports the latest execution status as last result`() = runTest {
        seedTask(id = "task", name = "巡检", cron = "*/30 * * * *", enabled = true, nextRunAt = 7_000L, lastRunAt = 6_000L)
        seedExecution(id = "exec-old", taskId = "task", executedAt = 1_000L, status = "success")
        seedExecution(id = "exec-new", taskId = "task", executedAt = 3_000L, status = "failed")

        val task = source.tasks().single()

        assertEquals("failed", task.lastResult)
        assertNotNull(task.nextRunAt)
        assertEquals(6_000L, task.lastRunAt)
    }

    @Test
    fun `tasks list is an empty array when nothing is scheduled`() = runTest {
        val event = HostTaskReads.listEvent(source.tasks(), requestId = "req-empty")

        assertEquals(HostTaskReads.TYPE_LIST, event.type)
        assertNull(event.error)
        assertEquals(0, event.tasks.size)
    }
}
