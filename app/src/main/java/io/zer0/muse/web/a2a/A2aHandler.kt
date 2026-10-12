package io.zer0.muse.web.a2a

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A2A 协议处理器 —— 把 Muse 的对话能力映射到标准 A2A JSON-RPC 方法。
 *
 * 执行模型：无 UI、无工具的请求-响应文本补全。A2A 每个 context 映射到 handler-owned Agent 会话，
 * 使用 ChatService.completeText + 专用消息历史；不会切换或读取用户当前聊天状态，也不会执行设备工具。
 * taskId/contextId 由外部协议管理，CancelTask 只取消本 handler 登记的请求协程。
 *
 * 有意的能力边界：A2A 目前仅提供文本对话与多轮上下文；若需工具/设备操作，用户必须在本机 UI 中触发，
 * 不能通过远程 A2A 绕过交互审批。
 */
class A2aHandler(
    private val chatService: ChatService,
    private val sessionRepo: SessionRepository,
    private val assistantRepository: AssistantRepository,
    private val appVersion: String,
) {
    /** 任务簿有界且可校验归属；用于 GetTask/CancelTask 精确按 A2A task id 操作。 */
    private val taskRegistry = A2aTaskRegistry()
    private val headlessGenerator = A2aHeadlessGenerator(chatService, sessionRepo, assistantRepository)

    /** 串行化 A2A SendMessage，避免共享生成资源发生请求间串台。 */
    private val sendMutex = kotlinx.coroutines.sync.Mutex()

    /** 当前 A2A task → 对应生成 session；CancelTask 只停止该 session，不触碰用户其他生成。 */
    private val taskSessions = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 当前 A2A task 的 coroutine job；CancelTask 只取消自己的请求。 */
    private val taskJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** 外部 context id → handler-owned session;有界 LRU，淘汰时删除隐藏 Agent 会话。 */
    private val contexts = A2aContextRegistry()

    /** Agent Card：声明本 agent 的身份、能力与技能。 */
    fun agentCard(baseUrl: String): AgentCard = AgentCard(
        protocolVersion = A2A_PROTOCOL_VERSION,
        name = "Muse",
        description = "Muse — Android 端 AI 助手，提供隔离的文本对话补全（A2A 不执行本地工具或设备操作）。",
        url = "$baseUrl/a2a",
        version = appVersion,
        capabilities = AgentCapabilities(streaming = false, pushNotifications = false),
        skills = listOf(
            AgentSkill(
                id = "chat",
                name = "对话",
                description = "隔离文本对话与多轮上下文；A2A 不执行本地工具或设备操作。",
                tags = listOf("chat", "text"),
            ),
        ),
    )

    /**
     * 处理 `SendMessage`：发送文本、等待生成完成、返回任务结果。
     *
     * @param params 含 message.parts 的文本
     * @return 完成态任务；失败/超时返回 failed 任务（不抛异常，交由 JSON-RPC 层包装）
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
    suspend fun sendMessage(params: MessageSendParams): A2aTask = sendMutex.withLock {
        val text = params.message.parts.mapNotNull { it.text }.joinToString("\n").trim()
        val taskId = params.taskId?.takeIf { it.isNotBlank() }
            ?: params.message.messageId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
        if (text.isEmpty()) {
            return@withLock record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "empty message text"))
        }
        if (text.length > MAX_MESSAGE_CHARS) {
            return@withLock record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "message too long"))
        }
        return@withLock try {
            // A2A session keys are handler-owned and map to Agent-tab sessions. Arbitrary user
            // session IDs are rejected; no switchSession/updateInput/send can touch the UI.
            val requestedContextId = params.message.contextId?.takeIf { it.isNotBlank() }
            val legacySessionKey = params.message.sessionId?.takeIf { it.isNotBlank() }
            val isNewContext = requestedContextId == null && legacySessionKey == null
            val externalContextId = requestedContextId ?: legacySessionKey ?: "ctx-${java.util.UUID.randomUUID()}"
            val sessionId = contexts.get(externalContextId) ?: run {
                // contextId is an A2A-owned namespace and may be first seen here; the legacy
                // Muse sessionId extension is accepted only if this handler created/mapped it.
                if (!isNewContext && requestedContextId == null) {
                    return@withLock record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "unknown A2A session id"))
                }
                val created = withContext(Dispatchers.IO) { sessionRepo.createAgentSession("default") }
                val evicted = contexts.put(externalContextId, created)
                if (evicted != null) {
                    // Only sessions created by this handler enter the registry, so eviction cannot
                    // delete a user-owned session. Delete out of band to bound hidden DB growth.
                    withContext(Dispatchers.IO) { runCatching { sessionRepo.deleteSession(evicted) } }
                }
                created
            }
            val session = sessionRepo.getSessionById(sessionId)
                ?: return@withLock record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "A2A session expired"))
            if (!session.isAgentSession) {
                contexts.remove(externalContextId, sessionId)
                return@withLock record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "session is not A2A-owned"))
            }

            taskSessions[taskId] = sessionId
            currentCoroutineContext()[Job]?.let { taskJobs[taskId] = it }
            record(A2aTask(id = taskId, contextId = externalContextId, state = A2aTaskState.WORKING))
            val reply = withTimeout(GENERATION_TIMEOUT_MS) {
                // A2A deliberately uses the Tasker-style headless path: no UI state, no tools,
                // no approvals, no foreground-session switching. Device actions remain in-app only.
                headlessGenerator.complete(sessionId, text).ifBlank { "(empty response)" }
            }
            check(!io.zer0.common.ProcessWriteGate.restoring) { "backup restore in progress" }
            sessionRepo.appendMessage(sessionId, UIMessage(role = MessageRole.USER, content = text))
            sessionRepo.appendMessage(sessionId, UIMessage(role = MessageRole.ASSISTANT, content = reply))
            record(
                A2aTask(
                    id = taskId,
                    contextId = externalContextId,
                    state = A2aTaskState.COMPLETED,
                    artifacts = listOf(A2aArtifact(name = "response", parts = listOf(A2aPart(kind = "text", text = reply)))),
                ),
            )
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "A2A SendMessage 超时: ${e.message}")
            record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "generation timeout"))
        } catch (e: CancellationException) {
            if (taskRegistry.isCancelable(taskId)) {
                record(A2aTask(id = taskId, state = A2aTaskState.CANCELED))
            }
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "A2A SendMessage 失败: ${e.message}", e)
            record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = e.message ?: "internal error"))
        }
    }

    /** 处理 `GetTask`：回查最近任务结果。 */
    fun getTask(taskId: String): A2aTask? = taskRegistry.get(taskId)

    /** 只取消本 handler 登记且仍在运行的任务；未知/终态 taskId 返回 null，不触碰 UI 生成。 */
    @Suppress("ReturnCount")
    fun cancelTask(taskId: String): A2aTask? {
        val current = taskRegistry.get(taskId) ?: return null
        if (current.state != A2aTaskState.WORKING || !taskSessions.containsKey(taskId)) return null
        val job = taskJobs[taskId] ?: return null
        // Headless A2A generation is owned by this request coroutine; cancel it, never a UI/global generation.
        job.cancel(CancellationException("A2A CancelTask"))
        return record(current.copy(state = A2aTaskState.CANCELED, error = null))
    }

    /** 记录任务快照（有界）。 */
    private fun record(task: A2aTask): A2aTask {
        val recorded = taskRegistry.record(task)
        if (task.state != A2aTaskState.WORKING) {
            // 完成任务不再可取消；会话映射随有界任务簿一并保留,仅供结果审计/状态使用。
            taskSessions.remove(task.id)
            taskJobs.remove(task.id)
        }
        return recorded
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 16_000
        private const val TAG = "A2aHandler"
        const val GENERATION_TIMEOUT_MS = 300_000L
    }
}
