package io.zer0.muse.web.a2a

/**
 * AUD-20261011-A2A-PROTOCOL: A2A 任务簿(有界、归属可判定)。
 *
 * 抽成独立类以便单测,并解决三处缺陷:
 *  - GetTask 需要按 task id 精确回查(不再误用 JSON-RPC 信封 id);
 *  - CancelTask 必须先判定"这个任务是不是本 handler 的、且还在跑",
 *    否则会无视 taskId 直接停掉用户前台正在进行的生成;
 *  - 任务簿必须有界,避免长跑服务无界增长。
 */
internal class A2aTaskRegistry(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private val tasks = LinkedHashMap<String, A2aTask>()

    val size: Int
        get() = synchronized(tasks) { tasks.size }

    /** 记录任务快照(同一 id 重复记录为更新,不重复占位)。 */
    fun record(task: A2aTask): A2aTask {
        synchronized(tasks) {
            tasks.remove(task.id)
            tasks[task.id] = task
            while (tasks.size > maxEntries) {
                val oldest = tasks.keys.firstOrNull() ?: break
                tasks.remove(oldest)
            }
        }
        return task
    }

    fun get(taskId: String): A2aTask? = synchronized(tasks) { tasks[taskId] }

    /** 是否为本次 A2A 会话登记过的任务(用于取消归属校验)。 */
    fun isOwned(taskId: String): Boolean = synchronized(tasks) { tasks.containsKey(taskId) }

    /** 是否可被取消:必须是本 handler 的、且仍处于 working 的任务。 */
    fun isCancelable(taskId: String): Boolean = get(taskId)?.state == A2aTaskState.WORKING

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
