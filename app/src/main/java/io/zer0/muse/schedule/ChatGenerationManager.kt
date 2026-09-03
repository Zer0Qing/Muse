package io.zer0.muse.schedule

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * v1.43: 应用级聊天生成调度器。
 *
 * 解决切页 / 后台时 AI 生成被 ViewModel 生命周期中断的问题:
 * - 生成任务运行在 [appScope] 而非 [androidx.lifecycle.viewModelScope],不依赖页面生命周期
 * - 维护 [activeGeneration]/[activeGenerations] 只读保活状态,供前台服务与新 ViewModel 绑定
 *
 * v1.x(唯一 Job 账本收口):
 * - 本类**不再持有/取消任何 Job**。会话级生成 Job 的唯一 owner 是
 *   [io.zer0.muse.session.ConversationSessionManager] 持有的
 *   `SessionRuntime.generationJob`。
 * - [launchGeneration] 只做应用级调度:从 owner 读上一代 Job 做串行化(新 block 等旧
 *   finally 结束),启动协程后立即通过 owner 的 `setGenerationJob` 登记,替换/取消/完成
 *   清理全部由 owner 完成。
 * - 活跃状态([_activeGenerations])仅用于前台服务保活与 [isStreaming] 只读查询,
 *   按 sessionId 记录"是否有未结束的生成",以 ActiveGeneration 对象身份做清理守护,
 *   不是 Job 的第二份账本。
 * - [stop] 只移除保活状态,取消动作委托给 owner `cancelGeneration`。
 */
class ChatGenerationManager(
    private val appScope: CoroutineScope,
    private val sessionManager: io.zer0.muse.session.ConversationSessionManager,
    private val appContext: Context? = null,
) {

    /** 当前活跃的生成会话。null 表示没有正在生成。 */
    data class ActiveGeneration(
        val sessionId: String,
        val assistantId: String,
        val sessionTitle: String,
        /** 稳定的代际身份；显示字段 copy() 更新时不能改变它。 */
        val generationId: Long = 0L,
        val isStreaming: Boolean = true,
        val lastUpdatedAt: Long = System.currentTimeMillis(),
    )

    private val _activeGeneration = MutableStateFlow<ActiveGeneration?>(null)
    val activeGeneration: StateFlow<ActiveGeneration?> = _activeGeneration.asStateFlow()

    /** 所有活跃生成任务的只读快照, key 为 sessionId。 */
    private val _activeGenerations = MutableStateFlow<Map<String, ActiveGeneration>>(emptyMap())
    val activeGenerations: StateFlow<Map<String, ActiveGeneration>> = _activeGenerations.asStateFlow()

    private val lock = Any()
    private val nextGenerationId = AtomicLong(0L)

    /**
     * 在应用级协程中启动生成任务,并登记到唯一 owner 的会话级账本。
     *
     * 调度顺序:
     * 1. 从 owner 读同一 session 上一代 Job(串行化依据),并取消它;
     * 2. 同步写入活跃保活状态;
     * 3. 启动块(先 `join` 上一代 finally,避免两代流同时写同一会话);
     * 4. 通过 `sessionManager.setGenerationJob` 登记新 Job(owner 会取消旧的并注册新的);
     * 5. 返回 Job(兼容旧调用方,可忽略)。
     *
     * @param sessionId 会话 id(单聊为会话 id,群聊为 "group:$chatId")
     */
    fun launchGeneration(
        sessionId: String,
        assistantId: String,
        sessionTitle: String,
        block: suspend () -> Unit,
    ): Job {
        synchronized(lock) {
            // 从唯一 owner 读上一代 Job。若上一代已正常完成,owner 已清空 → null(无需串行化)。
            val previousJob = sessionManager.runtime(sessionId)?.generationJob
            previousJob?.cancel()

            val generation = ActiveGeneration(
                sessionId = sessionId,
                assistantId = assistantId,
                sessionTitle = sessionTitle,
                generationId = nextGenerationId.incrementAndGet(),
                isStreaming = true,
                lastUpdatedAt = System.currentTimeMillis(),
            )
            // 先同步写入活跃状态。ON_STOP 可能紧跟在发送动作后发生;前台保活服务据此判定。
            _activeGenerations.value = _activeGenerations.value + (sessionId to generation)
            _activeGeneration.value = generation

            val job = appScope.launch(start = CoroutineStart.LAZY) {
                previousJob?.join()
                val heartbeatJob = launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        touch(sessionId)
                    }
                }
                try {
                    block()
                } finally {
                    heartbeatJob.cancel()
                    // touch()/updateSessionTitle() 会用 copy() 更新显示字段，不能用对象身份判断。
                    // 只比较不可变的 generationId，才能保证旧代 finally 不会清掉新代，
                    // 同时保证正常完成后活跃 map 一定能被移除。
                    synchronized(lock) {
                        if (_activeGenerations.value[sessionId]?.generationId == generation.generationId) {
                            _activeGenerations.value = _activeGenerations.value - sessionId
                            val next = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
                            _activeGeneration.value = next ?: generation.copy(
                                isStreaming = false,
                                lastUpdatedAt = System.currentTimeMillis(),
                            )
                        }
                    }
                    Logger.i("ChatGenMgr", "generation finished: $sessionId")
                }
            }

            // 唯一 owner 登记:取消上一代(幂等)、登记新 Job、完成时清理 + idle 调度。
            sessionManager.setGenerationJob(sessionId, job)
            job.start()

            // 生成一登记就进入前台服务,避免 ON_STOP 与任务启动之间的竞态。
            appContext?.let { context ->
                runCatching { ChatGenerationService.start(context) }
                    .onFailure { Logger.w("ChatGenMgr", "生成开始时启动前台服务失败", it) }
            }
            return job
        }
    }

    /**
     * 用户停止指定会话的生成。取消动作委托给会话级唯一 owner。
     *
     * @param sessionId 要停止的会话 id;不传则取消全部(兼容旧调用方)
     */
    fun stop(sessionId: String? = null) {
        synchronized(lock) {
            if (sessionId != null) {
                _activeGenerations.value = _activeGenerations.value - sessionId
                if (_activeGeneration.value?.sessionId == sessionId) {
                    _activeGeneration.value = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
                }
                sessionManager.cancelGeneration(sessionId)
                Logger.i("ChatGenMgr", "generation stopped: $sessionId")
            } else {
                val ids = _activeGenerations.value.keys.toList()
                _activeGenerations.value = emptyMap()
                _activeGeneration.value = null
                ids.forEach { sessionManager.cancelGeneration(it) }
                Logger.i("ChatGenMgr", "all generations stopped")
            }
        }
    }

    /** 刷新指定会话的心跳;为 null 时兼容旧调用,刷新最近任务。 */
    fun touch(sessionId: String? = null) {
        synchronized(lock) {
            val targetId = sessionId ?: _activeGeneration.value?.sessionId ?: return
            val current = _activeGenerations.value[targetId] ?: return
            val touched = current.copy(lastUpdatedAt = System.currentTimeMillis())
            _activeGenerations.value = _activeGenerations.value + (targetId to touched)
            _activeGeneration.value = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
        }
    }

    /** 更新最近生成的通知标题(群聊异步获取群聊名后调用)。 */
    fun updateSessionTitle(title: String) {
        val sessionId = synchronized(lock) { _activeGeneration.value?.sessionId }
        if (sessionId != null) updateSessionTitle(sessionId, title)
    }

    /** 更新指定会话的通知标题。 */
    fun updateSessionTitle(sessionId: String, title: String) {
        synchronized(lock) {
            val current = _activeGenerations.value[sessionId] ?: return
            val updated = current.copy(sessionTitle = title, lastUpdatedAt = System.currentTimeMillis())
            _activeGenerations.value = _activeGenerations.value + (sessionId to updated)
            _activeGeneration.value = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
        }
    }

    /** 只读状态:指定 sessionId 是否有未结束的生成。 */
    fun isStreaming(sessionId: String): Boolean {
        synchronized(lock) {
            return _activeGenerations.value.containsKey(sessionId)
        }
    }

    private companion object {
        /** 任务没有文本输出时仍定期证明协程存活,避免长工具调用被误判为卡死。 */
        const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
