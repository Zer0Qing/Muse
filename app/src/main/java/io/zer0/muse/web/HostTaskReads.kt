package io.zer0.muse.web

import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskExecutionDao
import kotlinx.coroutines.flow.first

/**
 * 自动化只读命令(`tasks.list`)的载荷投影。
 *
 * 定时任务只有一个列表,网页端与 App 定时任务页看的是同一批行、同一个排序
 * (见 [ScheduledTaskDao.observeAll]),不另开查询口径。
 */
internal object HostTaskReads {

    const val TYPE_LIST = "tasks.list"

    /** `tasks.list` 回执。没有任务时下发空数组。 */
    fun listEvent(tasks: List<HostScheduledTask>, requestId: String?): HostEvent = HostEvent(
        type = TYPE_LIST,
        requestId = requestId,
        tasks = tasks,
    )

    /**
     * 一条任务的下次执行时间。
     *
     * 停用任务不给出下次时间:库里 `next_run_at` 是停用前留下的旧值,原样下发会让
     * 网页端把「已关掉的任务」显示成「几点几分会跑」。
     */
    fun nextRunAt(enabled: Boolean, nextRunAt: Long): Long? = nextRunAt.takeIf { enabled && it > 0 }

    /** 上次执行时间;0 表示从未执行。 */
    fun lastRunAt(lastRunAt: Long): Long? = lastRunAt.takeIf { it > 0 }
}

/**
 * 自动化只读命令的数据源。
 *
 * 抽成接口是为了让网关在没有定时任务存储的场景下也能照常回执(空列表)。
 */
internal interface HostTaskSource {

    /** 定时任务一览。 */
    suspend fun tasks(): List<HostScheduledTask>

    /** 未装配时的空实现:没有任务。 */
    object Empty : HostTaskSource {
        override suspend fun tasks(): List<HostScheduledTask> = emptyList()
    }
}

/**
 * 直接读定时任务两张表的数据源。
 *
 * 任务行取 [ScheduledTaskDao.observeAll] 的一次快照(与 App 定时任务页同一个查询、同一排序);
 * 最近一次执行状态取 [ScheduledTaskExecutionDao.latestStatusByTask],一次拿全表而不是逐条查。
 */
internal class DaoHostTaskSource(
    private val taskDao: ScheduledTaskDao,
    private val executionDao: ScheduledTaskExecutionDao,
) : HostTaskSource {

    override suspend fun tasks(): List<HostScheduledTask> {
        val lastResults = executionDao.latestStatusByTask().associate { it.taskId to it.status }
        return taskDao.observeAll().first().map { task ->
            HostScheduledTask(
                id = task.id,
                name = task.name,
                cron = task.cronExpr,
                enabled = task.enabled,
                nextRunAt = HostTaskReads.nextRunAt(task.enabled, task.nextRunAt),
                lastRunAt = HostTaskReads.lastRunAt(task.lastRunAt),
                lastResult = lastResults[task.id],
            )
        }
    }
}
