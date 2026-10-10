package io.zer0.muse.intent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.java.KoinJavaComponent

/**
 * v2.x: Tasker 联动接收器。
 *
 * 参考项目能力对标新增：允许 Android 自动化平台（Tasker / MacroDroid 等）通过广播
 * 触发 Muse 跑一轮对话，结果以通知返回。填补"外部自动化编排 Muse"的缺口。
 *
 * 用法（Tasker 的「发送意图」动作）：
 *  - 动作：`io.zer0.muse.action.TASKER_TRIGGER`
 *  - 包名：`io.zer0.muse`
 *  - 额外参数：`prompt` = 要发送给 AI 的文本（必填）
 *
 * 执行模型：后台单轮（不经 UI），复用 [ChatService] 与主链路同一套模型解析。
 * 结果写入会话（新建专用会话）+ 发通知，与定时任务的 AI 动作同源。
 *
 * 安全：仅响应本包显式动作（不导出任意 Intent 接收）；提示词走一次 completion，
 * 不授予工具执行权限（避免外部广播间接触发设备操作）。如需工具能力，请用应用内定时任务。
 */
class TaskerTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TASKER_TRIGGER) return
        val prompt = intent.getStringExtra(EXTRA_PROMPT)?.trim().orEmpty()
        if (prompt.isEmpty()) {
            Logger.w(TAG, "Tasker 触发缺少 prompt，忽略")
            return
        }
        Logger.i(TAG, "Tasker 触发: prompt 长度=${prompt.length}")
        val pendingResult = goAsync()
        @Suppress("TooGenericExceptionCaught")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runTrigger(context.applicationContext, prompt)
            } catch (e: Exception) {
                Logger.e(TAG, "Tasker 触发执行失败: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun runTrigger(app: Context, prompt: String) {
        val chatService = KoinJavaComponent.get<ChatService>(ChatService::class.java)
        val sessionRepo = KoinJavaComponent.get<SessionRepository>(SessionRepository::class.java)
        val assistantRepo = KoinJavaComponent.get<AssistantRepository>(AssistantRepository::class.java)

        val assistantId = "default"
        val assistant = resultOf { assistantRepo.getById(assistantId) }.getOrNull()
        val messages = listOf(UIMessage(role = MessageRole.USER, content = prompt))
        val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) {
            chatService.completeText(
                messages = messages,
                temperature = assistant?.temperature,
                maxTokens = assistant?.maxTokens,
            )
        } ?: run {
            Logger.w(TAG, "Tasker 触发 AI 超时")
            return
        }
        val reply = io.zer0.muse.transformer.stripThinkTags(completion.text).ifBlank { "(空回复)" }

        // 落库到专用会话（首次创建，后续复用同名会话），与定时任务同源
        val sessionId = resultOf {
            val existing =
                sessionRepo.observeSessions().first().firstOrNull { it.title == TASKER_SESSION_TITLE }
            if (existing != null) {
                existing.id
            } else {
                val id = sessionRepo.createSession(assistantId)
                sessionRepo.renameSession(id, TASKER_SESSION_TITLE)
                id
            }
        }.getOrNull()
        if (sessionId != null) {
            resultOf {
                sessionRepo.appendMessage(sessionId, UIMessage(role = MessageRole.USER, content = prompt))
                sessionRepo.appendMessage(sessionId, UIMessage(role = MessageRole.ASSISTANT, content = reply))
            }.onError { msg, _ -> Logger.w(TAG, "Tasker 会话落库失败: $msg") }
        }

        // 通知回传结果
        resultOf {
            MuseNotificationManager(app).notifyChatCompletedWithPolicy(
                policy = "always",
                sessionTitle = "Tasker 触发",
                preview = reply,
                target = if (sessionId != null) MuseNotificationTarget.Session(sessionId) else MuseNotificationTarget.Home,
            )
        }.onError { msg, _ -> Logger.w(TAG, "Tasker 通知失败: $msg") }
    }

    companion object {
        private const val TAG = "TaskerTriggerReceiver"

        /** Tasker 应发送的显式动作。 */
        const val ACTION_TASKER_TRIGGER = "io.zer0.muse.action.TASKER_TRIGGER"

        /** Tasker 传入的提示词 extra key。 */
        const val EXTRA_PROMPT = "prompt"

        /** Tasker 触发的专用会话标题（同名复用）。 */
        const val TASKER_SESSION_TITLE = "Tasker 触发"

        private const val LLM_TIMEOUT_MS = 120_000L
    }
}
