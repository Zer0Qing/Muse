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
        val pendingResult = goAsync()
        @Suppress("TooGenericExceptionCaught")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = runCatching {
                    KoinJavaComponent.get<io.zer0.muse.data.SettingsRepository>(
                        io.zer0.muse.data.SettingsRepository::class.java,
                    )
                }.getOrNull()
                // AUD-20261011-TASKER-ABUSE: 三道防线 —— 开关默认关闭 + 长度上限 + 限频。
                // 本 Receiver 是 exported 且无 permission 的入口,任意应用都能发;
                // 不设防即可循环触发计费 LLM(已验证无法间接触发工具执行,故缺口是成本滥用)。
                val decision = TaskerTriggerGuard.evaluate(
                    enabled = settings?.taskerTriggerEnabledFlow?.first() ?: false,
                    promptLength = prompt.length,
                    nowMs = System.currentTimeMillis(),
                    lastAcceptedAtMs = lastAcceptedAt,
                )
                if (decision != TaskerTriggerGuard.Decision.ACCEPTED) {
                    Logger.w(TAG, "Tasker 触发被拒绝: $decision")
                    return@launch
                }
                lastAcceptedAt = System.currentTimeMillis()
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
        // v2.6.0 (AUD-CHANNEL-F10): 改读用户全局通知策略 —— 原实现硬编码
        // policy = "always",用户即使选了“从不通知”,每次 Tasker 触发仍会弹通知。
        resultOf {
            val settings = runCatching {
                KoinJavaComponent.get<io.zer0.muse.data.SettingsRepository>(
                    io.zer0.muse.data.SettingsRepository::class.java,
                )
            }.getOrNull()
            val policy = settings?.notificationPolicyFlow?.first() ?: "when_unfocused"
            MuseNotificationManager(app).notifyChatCompletedWithPolicy(
                policy = policy,
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

        /**
         * AUD-20261011-TASKER-ABUSE: 上次放行时刻(进程内限频基准)。
         *
         * Receiver 每次 onReceive 都可能是新实例(manifest 静态注册),
         * 因此限频状态放在 companion;进程重启后重置属可接受口径
         * (配合默认关闭的持久化开关,不构成绕过)。
         */
        @Volatile
        private var lastAcceptedAt: Long? = null
    }
}
