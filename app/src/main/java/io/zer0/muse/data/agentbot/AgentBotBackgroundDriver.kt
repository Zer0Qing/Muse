package io.zer0.muse.data.agentbot

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 中枢的后台收件箱消费者。
 *
 * ## 为什么需要它
 *
 * 结果留在收件箱等页面来取是错的：用户派完工就会离开 Agent 页，
 * 而"交代一件事，它后台做完再告诉你"里的「告诉你」不该以"你还得回来看看"为前提。
 *
 * ## 它做什么、不做什么
 *
 * 只负责**已经审核通过的结果**：把 `TASK_PUBLICATION_READY` 发布成对话正文，
 * `TASK_FAILED` 补一条失败状态，并在应用不在前台时补一条通知。
 *
 * 执行结果（`TASK_RESULT_READY`）与审核事实（`REVIEW_READY`）只在这里确认消费，
 * 不对外发言 —— 正文必须先经过审核。
 *
 * 不推进任何需要模型的事（新输入、进度、审核）：那些要有前台回合才能跑，
 * 属于页面里的 [io.zer0.muse.ui.agentbot.AgentBotViewModel.runTurnOnce]。
 * 这里对它们返回"没处理"，事件原样留在队列里。
 */
class AgentBotBackgroundDriver(
    private val context: Context,
    private val repository: AgentBotRepository,
    private val inbox: BotInboxProcessor,
    private val results: AgentBotResultPublisher,
    private val settings: SettingsRepository,
) {

    private var job: Job? = null

    /** 启动后台消费循环。幂等：重复调用不会起第二个循环。 */
    fun start(scope: CoroutineScope, intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }
                    .onFailure { Logger.w(LOG_TAG, "后台消费失败: ${it.message}") }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun tick() {
        val bindings = repository.enabledBindings()
        if (bindings.isEmpty()) return
        for (binding in bindings) {
            var landed: String? = null
            inbox.runTurn(binding.botId) { event ->
                when (event.kind) {
                    // 审核允许之后才存在的事实：正文来自审核结论。
                    BotEventKind.TASK_PUBLICATION_READY,
                    BotEventKind.TASK_FAILED,
                    -> when (
                        results.dispatch(
                            event = event,
                            fallbackBotId = binding.botId,
                            fallbackSessionId = binding.canonicalAgentSessionId,
                        )
                    ) {
                        // 只有真的产生了一条消息才补通知。
                        // "被拒绝"也是有结论的处理结果，但用户不该收到一条
                        // 声称完成、实际没发的通知。
                        AgentBotResultPublisher.Outcome.PUBLISHED -> {
                            landed = results.textOf(event)
                            true
                        }
                        AgentBotResultPublisher.Outcome.HANDLED,
                        AgentBotResultPublisher.Outcome.REFUSED,
                        -> true
                        AgentBotResultPublisher.Outcome.RETRY -> false
                    }
                    // 执行结果等着审核、审核事实是内部判据：都不对外发言。
                    // 但必须在这里确认消费，否则它们会一直排在收件箱里占配额。
                    BotEventKind.TASK_RESULT_READY,
                    BotEventKind.REVIEW_READY,
                    -> true
                    // 其余事件交给前台回合：它们要么需要模型调用，要么只是进度心跳。
                    else -> false
                }
            }
            landed?.let { text -> notifyIfBackground(binding, text) }
        }
    }

    /**
     * 应用不在前台时补一条通知。
     *
     * 走用户的「回复完成」通知策略：设成"从不"的用户在这里就不会被打扰。
     * 前台时不发——用户正看着对话，通知只会多一条要划掉的东西。
     */
    private suspend fun notifyIfBackground(binding: BotBindingEntity, text: String) {
        val policy = runCatching { settings.notificationPolicyFlow.first() }
            .onFailure { Logger.w(LOG_TAG, "读取通知策略失败，按 when_unfocused 处理: ${it.message}") }
            .getOrDefault("when_unfocused")
        MuseNotificationManager(context).notifyChatCompletedWithPolicy(
            policy = policy,
            sessionTitle = context.getString(io.zer0.muse.R.string.agent_bot_notification_title),
            preview = text,
            target = binding.canonicalAgentSessionId.takeIf { it.isNotBlank() }
                ?.let { MuseNotificationTarget.Session(it) }
                ?: MuseNotificationTarget.Chat,
        )
    }

    private companion object {
        const val LOG_TAG = "AgentBotBackgroundDriver"

        /** 轮询间隔。与调度器同量级：结果晚几秒出现用户感知不到，空转开销要低。 */
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
