package io.zer0.muse.web.a2a

import io.zer0.common.Logger
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * A2A 协议处理器 —— 把 Muse 的对话能力映射到标准 A2A JSON-RPC 方法。
 *
 * 执行模型：请求-响应式。收到 `SendMessage` 后：
 *  1. 选定/新建会话（[A2aMessage.sessionId] 指定或新建）；
 *  2. 经 [ChatViewModel] 走标准发送链路（复用主对话生成 + 工具循环）；
 *  3. 等待该会话生成完成（监听 [ChatViewModel.state] 的 isStreaming 回落）；
 *  4. 取最后一条 assistant 消息作为 artifact 返回。
 *
 * 复用而非重造：不实现第二套生成逻辑，直接驱动 UI 层的 ChatViewModel，
 * 因此 A2A 调用与真机聊天共享同一套模型解析、工具、审批、记忆链路。
 *
 * 任务簿：本轮为同步语义（SendMessage 阻塞至完成），任务 id 仅用于 GetTask 回查
 * 最近结果；取消通过 [ChatViewModel] 的停止能力实现。
 */
class A2aHandler(
    private val chatViewModel: ChatViewModel,
    private val sessionRepo: SessionRepository,
    private val appVersion: String,
) {
    /** 最近完成的任务快照（id → 任务），供 GetTask 回查（有界，只留最近 N 条）。 */
    private val recentTasks = LinkedHashMap<String, A2aTask>()

    /** Agent Card：声明本 agent 的身份、能力与技能。 */
    fun agentCard(baseUrl: String): AgentCard = AgentCard(
        protocolVersion = A2A_PROTOCOL_VERSION,
        name = "Muse",
        description = "Muse — Android 端 AI 助手，支持对话、工具调用、记忆与多模态。",
        url = "$baseUrl/a2a",
        version = appVersion,
        capabilities = AgentCapabilities(streaming = false, pushNotifications = false),
        skills = listOf(
            AgentSkill(
                id = "chat",
                name = "对话",
                description = "文本对话，支持工具调用与多轮上下文。",
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
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    suspend fun sendMessage(params: MessageSendParams): A2aTask {
        val text = params.message.parts.mapNotNull { it.text }.joinToString("\n").trim()
        val taskId = params.message.messageId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
        if (text.isEmpty()) {
            return record(
                A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "empty message text"),
            )
        }
        return try {
            // 1) 选定会话：指定 id 则切换，否则新建
            val requested = params.message.sessionId?.takeIf { it.isNotBlank() }
            if (requested != null) {
                sessionRepo.getSessionById(requested)
                    ?: return record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "session not found: $requested"))
                chatViewModel.switchSession(requested)
                awaitCurrentSession(requested)
            } else {
                // 新建会话并等就绪（回调式 API → 挂起等待）
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    val ready: () -> Unit = { cont.resumeWith(Result.success(Unit)) }
                    chatViewModel.createNewSession(onReady = ready)
                }
            }
            val sessionId = chatViewModel.state.value.currentSessionId
                ?: return record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "no active session"))

            // 2) 发送（复用标准链路）
            chatViewModel.updateInput(text)
            chatViewModel.send()

            // 3) 等待生成开始再等结束（避免 send() 尚未启动时立即判定"未在生成"）
            val streamingBefore = chatViewModel.state.value.isStreaming
            if (!streamingBefore) {
                runCatching {
                    withTimeout(GENERATION_START_TIMEOUT_MS) {
                        chatViewModel.state.first { it.isStreaming }
                    }
                }
            }
            withTimeout(GENERATION_TIMEOUT_MS) {
                chatViewModel.state.first { !it.isStreaming }
            }

            // 4) 取最后一条有内容的 assistant 消息
            val reply = sessionRepo.observeMessages(sessionId).first()
                .lastOrNull { it.role == io.zer0.ai.core.MessageRole.ASSISTANT && it.content.isNotBlank() }
                ?.content
                ?: ""
            record(
                A2aTask(
                    id = taskId,
                    state = A2aTaskState.COMPLETED,
                    artifacts = listOf(A2aArtifact(name = "response", parts = listOf(A2aPart(kind = "text", text = reply)))),
                ),
            )
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "A2A SendMessage 超时: ${e.message}")
            record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = "generation timeout"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "A2A SendMessage 失败: ${e.message}", e)
            record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = e.message ?: "internal error"))
        }
    }

    /** 处理 `GetTask`：回查最近任务结果。 */
    fun getTask(taskId: String): A2aTask? = synchronized(recentTasks) { recentTasks[taskId] }

    /** 处理 `CancelTask`：停止当前生成。 */
    @Suppress("TooGenericExceptionCaught")
    fun cancelTask(taskId: String): A2aTask {
        return try {
            chatViewModel.stop()
            record(A2aTask(id = taskId, state = A2aTaskState.CANCELED))
        } catch (e: Exception) {
            Logger.w(TAG, "A2A CancelTask 失败: ${e.message}")
            record(A2aTask(id = taskId, state = A2aTaskState.FAILED, error = e.message ?: "cancel failed"))
        }
    }

    /** 等当前会话就绪（切换是异步的）。 */
    private suspend fun awaitCurrentSession(sessionId: String) {
        runCatching {
            withTimeout(5_000L) { chatViewModel.state.first { it.currentSessionId == sessionId } }
        }
    }

    /** 记录任务快照（有界）。 */
    private fun record(task: A2aTask): A2aTask {
        synchronized(recentTasks) {
            recentTasks[task.id] = task
            while (recentTasks.size > MAX_RECENT_TASKS) {
                val oldest = recentTasks.keys.firstOrNull() ?: break
                recentTasks.remove(oldest)
            }
        }
        return task
    }

    private companion object {
        const val TAG = "A2aHandler"
        const val GENERATION_START_TIMEOUT_MS = 15_000L
        const val GENERATION_TIMEOUT_MS = 300_000L
        const val MAX_RECENT_TASKS = 64
    }
}
