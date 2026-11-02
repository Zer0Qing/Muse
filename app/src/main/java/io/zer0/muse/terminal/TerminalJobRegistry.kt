package io.zer0.muse.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台终端 job 的台账（对比清单 P1-07）。
 *
 * 现有 `terminal_exec` 是**同步等待**（≤60 秒）：命令一长就只能超时终止，
 * 而且模型在等的过程里什么都做不了。长任务需要的是另一套合同：
 * **提交 → 拿 job_id → 轮询状态/输出 → 需要时终止**。
 *
 * 四条约束：
 *  1. **输出有上限**：只保留尾部 N 个字符在内存里，完整输出走文件（否则一个 `yes` 就能吃光内存）；
 *  2. **并发有上限**：超过就拒绝并说明，而不是让设备被拖死；
 *  3. **保留数量有上限**：老 job 按完成时间淘汰，避免台账无限增长；
 *  4. **终止是协作式的**：kill 之后状态是 KILLED，退出码不可信 —— 不能报成"正常结束"。
 */
class TerminalJobRegistry(
    private val maxConcurrent: Int = 4,
    private val maxRetained: Int = 16,
    private val tailChars: Int = 8_000,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + io.zer0.muse.util.GlobalCoroutineExceptionHandler,
    ),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    enum class State { RUNNING, FINISHED, FAILED, KILLED }

    /** 一个 job 对外可见的快照。 */
    data class Snapshot(
        val id: String,
        val label: String,
        val command: String,
        val state: State,
        val exitCode: Int?,
        val startedAt: Long,
        val endedAt: Long?,
        val tail: String,
    )

    /** 提交被拒绝的原因（文案由调用方翻译）。 */
    enum class Rejection { TOO_MANY_RUNNING, EMPTY_COMMAND }

    sealed interface SubmitResult {
        data class Accepted(val job: Snapshot) : SubmitResult
        data class Rejected(val reason: Rejection, val running: Int) : SubmitResult
    }

    /**
     * 由调用方提供的启动方式：返回一个可等待/可终止的句柄。
     *
     * 抽成接口是为了能在没有真实进程的环境里验证状态机（见测试）。
     */
    fun interface Launcher {
        fun launch(command: String, onOutput: (String) -> Unit): Handle
    }

    interface Handle {
        /** 阻塞直到结束，返回退出码；被终止时返回 null。 */
        fun waitFor(): Int?

        fun kill()
    }

    private class Job(
        val id: String,
        val label: String,
        val command: String,
        val startedAt: Long,
        val handle: Handle,
        val output: StringBuilder,
    ) {
        var state: State = State.RUNNING
        var exitCode: Int? = null
        var endedAt: Long? = null
        val killed = AtomicBoolean(false)
    }

    private val jobs = LinkedHashMap<String, Job>()
    private var counter = 0L

    @Synchronized
    fun submit(label: String, command: String, launcher: Launcher): SubmitResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return SubmitResult.Rejected(Rejection.EMPTY_COMMAND, runningCount())
        val running = runningCount()
        if (running >= maxConcurrent) return SubmitResult.Rejected(Rejection.TOO_MANY_RUNNING, running)
        counter += 1
        val id = "job-$counter"
        val handle = launcher.launch(trimmed) { chunk -> append(id, chunk) }
        val job = Job(
            id = id,
            label = label,
            command = trimmed,
            startedAt = now(),
            handle = handle,
            output = StringBuilder(),
        )
        jobs[id] = job
        evictIfNeeded()
        scope.launch {
            val exit = runCatching { handle.waitFor() }.getOrNull()
            finish(id, exit)
        }
        return SubmitResult.Accepted(snapshot(job))
    }

    @Synchronized
    fun get(id: String): Snapshot? = jobs[id]?.let { snapshot(it) }

    @Synchronized
    fun list(): List<Snapshot> = jobs.values.map { snapshot(it) }

    /** 终止一个仍在运行的 job。返回 false 表示它已经结束或不存在。 */
    @Synchronized
    fun kill(id: String): Boolean {
        val job = jobs[id] ?: return false
        if (job.state != State.RUNNING) return false
        job.killed.set(true)
        runCatching { job.handle.kill() }
        job.state = State.KILLED
        job.endedAt = now()
        // 退出码在终止后不可信：明确留空，避免上层把"被杀"读成"正常结束"
        job.exitCode = null
        return true
    }

    /** 仅测试用：清空台账。 */
    @Synchronized
    internal fun resetForTests() {
        jobs.clear()
        counter = 0
    }

    private fun finish(id: String, exit: Int?) {
        synchronized(this) {
            val job = jobs[id] ?: return
            if (job.state != State.RUNNING) return
            job.exitCode = exit
            job.endedAt = now()
            job.state = when {
                job.killed.get() -> State.KILLED
                exit == null -> State.FAILED
                else -> State.FINISHED
            }
        }
    }

    private fun append(id: String, chunk: String) {
        synchronized(this) {
            val job = jobs[id] ?: return
            job.output.append(chunk)
            // 只留尾部：`yes` 这种命令能在几秒内产生几百 MB
            if (job.output.length > tailChars) {
                job.output.delete(0, job.output.length - tailChars)
            }
        }
    }

    private fun runningCount(): Int = jobs.values.count { it.state == State.RUNNING }

    private fun snapshot(job: Job): Snapshot = Snapshot(
        id = job.id,
        label = job.label,
        command = job.command,
        state = job.state,
        exitCode = job.exitCode,
        startedAt = job.startedAt,
        endedAt = job.endedAt,
        tail = job.output.toString(),
    )

    /** 已结束的 job 超出保留上限时，按加入顺序淘汰最老的已结束项。 */
    private fun evictIfNeeded() {
        if (jobs.size <= maxRetained) return
        val removable = jobs.values.filter { it.state != State.RUNNING }.take(jobs.size - maxRetained)
        removable.forEach { jobs.remove(it.id) }
    }
}
