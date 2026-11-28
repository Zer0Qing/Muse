package io.zer0.muse.data.agentbot

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.R

/**
 * 把中枢事件翻译成对话正文并发布。
 *
 * ## 为什么单独一层
 *
 * 结果事件有两条消费路径：页面可见时由 ViewModel 的回合取走，页面不可见时由
 * 后台驱动器取走。两条路径必须产出**一模一样**的话——否则同一个任务，
 * 用户在前台看和收到通知看到的措辞会不一样。
 *
 * ## 什么能对外说
 *
 * 只有 `TASK_PUBLICATION_READY` —— 那是审核明确允许发布之后才存在的事实。
 * 执行结果（`TASK_RESULT_READY`）只是审核入口：它带着子 Agent 的自述，
 * 没被审核核对过，不能直接进公共消息。
 */
class AgentBotResultPublisher(
    private val context: Context,
    private val publication: PublicationService,
) {

    /**
     * 一条事件的处理结局。
     *
     * [PUBLISHED] 与 [HANDLED] 必须分开：只有真的产生了对外消息时才该补通知。
     * 把"处理完了但没发言"当成已发言，用户会收到一条空通知；
     * 反过来把"依据不足"当成失败重试，事件会永远赖在收件箱里。
     */
    enum class Outcome {
        /** 确实产生了对外消息（含复用已有的同一条消息）。 */
        PUBLISHED,

        /** 处理完了，这类事件本就不该有正文。 */
        HANDLED,

        /** 依据不足，明确不发布；重试也不会变，可以确认消费。 */
        REFUSED,

        /** 暂时处理不了，事件应留在队列里等下一轮。 */
        RETRY,
    }

    /**
     * 处理一条中枢事件，只回"要不要确认消费"。
     *
     * @return true 表示这条事件已经有归宿（发了、或明确不该发），调用方可以确认消费；
     *   false 表示暂时处理不了，事件留在队列里等下一轮。
     */
    suspend fun publish(event: BotEventEntity, fallbackBotId: String, fallbackSessionId: String): Boolean =
        dispatch(event, fallbackBotId, fallbackSessionId) != Outcome.RETRY

    /**
     * 处理一条中枢事件并返回结局。
     *
     * 后台驱动器要据此决定"要不要补通知"：只有 [Outcome.PUBLISHED] 才有正文。
     */
    suspend fun dispatch(event: BotEventEntity, fallbackBotId: String, fallbackSessionId: String): Outcome = when (event.kind) {
        // 唯一会产生对话正文的入口。
        BotEventKind.TASK_PUBLICATION_READY ->
            publishReviewed(event, fallbackBotId, fallbackSessionId)
        // 执行完成只是"有材料了"。审核还没说能不能发，这里一个字都不说。
        BotEventKind.TASK_RESULT_READY -> {
            Logger.d(LOG_TAG, "执行结果已交给审核，不直接对外: ${event.eventId}")
            Outcome.HANDLED
        }
        // 审核事实是内部判据：页面看板读任务状态与候选，不需要对外发言。
        BotEventKind.REVIEW_READY -> Outcome.HANDLED
        // 执行失败没有可审核的结果，只说"这次没做成"这一条固定状态。
        BotEventKind.TASK_FAILED ->
            publishFailureNotice(event, fallbackBotId, fallbackSessionId)
        else -> Outcome.RETRY
    }

    /**
     * 发布一条审核允许的事实。
     *
     * 事实本身只说明"生成时审核允许过"；消费前由 [PublicationService.publishReviewed]
     * 重新核对任务状态、作用域与轮次版本，对不上就不发。
     *
     * 幂等由 `publicationKey` 保证：事件未被确认前重投，返回的是已有的 messageId，
     * 不会再写第二条消息。
     */
    private suspend fun publishReviewed(event: BotEventEntity, fallbackBotId: String, fallbackSessionId: String): Outcome {
        val decision = ReviewDecision.decode(event.payloadRef)
        if (decision == null) {
            // 解析不出来就当没有可发布事实：猜一份内容发出去，会以审核的名义
            // 出现在用户面前，比"晚一点"糟得多。载荷是随事件一起落库的，
            // 坏了就是坏了，重试不会变好。
            Logger.e(LOG_TAG, "发布事实缺少可解析的审核结论: ${event.eventId}")
            return Outcome.REFUSED
        }
        if (!decision.publicationAllowed) {
            Logger.e(LOG_TAG, "审核未允许发布却出现了发布事实，拒绝: ${event.eventId}")
            return Outcome.REFUSED
        }
        if (decision.disposition != ReviewDisposition.ACCEPT) {
            // publicationAllowed 与 disposition 本应同时为"允许"；不一致说明事实被拼装错了。
            // 交给审核结论说话：不是 ACCEPT 就不发。
            Logger.e(LOG_TAG, "审核结论为 ${decision.disposition} 却标记允许发布，拒绝: ${event.eventId}")
            return Outcome.REFUSED
        }
        if (decision.publicationKey.isBlank()) {
            // 没有 key 就没有幂等，重投会重复发言；也无法确认发过没有。
            Logger.e(LOG_TAG, "发布事实缺少 publicationKey，拒绝: ${event.eventId}")
            return Outcome.REFUSED
        }
        val result = runCatching {
            publication.publishReviewed(
                decision = decision,
                scope = PublicationService.PublicationScope(
                    botId = event.botId.ifBlank { fallbackBotId },
                    originSessionId = event.originSessionId.ifBlank { fallbackSessionId },
                    taskId = event.taskId,
                    runId = event.runId,
                    taskRevision = decision.taskRevision,
                ),
            )
        }.onFailure {
            Logger.w(LOG_TAG, "发布失败，保留事件待重试: ${event.eventId} | ${it.message}")
        }.getOrNull()
        if (result == null) return Outcome.RETRY
        if (result.messageIds.isNotEmpty()) return Outcome.PUBLISHED
        if (result.refusedReason.isNotBlank()) {
            Logger.e(LOG_TAG, "发布被拒绝，不再重试: ${event.eventId} | ${result.refusedReason}")
            return Outcome.REFUSED
        }
        return Outcome.RETRY
    }

    /** 执行失败的状态提示。正文是固定文案，不含任何执行内容。 */
    private suspend fun publishFailureNotice(event: BotEventEntity, fallbackBotId: String, fallbackSessionId: String): Outcome {
        val result = runCatching {
            publication.publishIdempotent(
                publicationKey = PublicationService.failurePublicationKeyFor(event.eventId),
                scope = PublicationService.PublicationScope(
                    botId = event.botId.ifBlank { fallbackBotId },
                    originSessionId = event.originSessionId.ifBlank { fallbackSessionId },
                    taskId = event.taskId,
                    runId = event.runId,
                ),
                content = PublicationService.PublishedContent(text = textOf(event)),
            )
        }.onFailure {
            Logger.w(LOG_TAG, "失败提示发布失败，保留事件待重试: ${event.eventId} | ${it.message}")
        }.getOrNull()
        return if (result != null && result.messageIds.isNotEmpty()) Outcome.PUBLISHED else Outcome.RETRY
    }

    /**
     * 事件对应的用户可见正文。
     *
     * 只对"允许发布"与"执行失败"两类有话说。执行结果按空正文处理 ——
     * payloadRef 里是子 Agent 的自述，任何路径都不该把它当正文交出去。
     */
    fun textOf(event: BotEventEntity): String = when (event.kind) {
        BotEventKind.TASK_PUBLICATION_READY -> ReviewDecision.decode(event.payloadRef)
            ?.userVisibleSummary
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.agent_bot_result_empty)
        BotEventKind.TASK_RESULT_READY,
        BotEventKind.REVIEW_READY,
        -> context.getString(R.string.agent_bot_result_empty)
        BotEventKind.TASK_FAILED -> context.getString(R.string.agent_bot_result_failed)
        else -> {
            // 其余类型本就不该走到这里；保留旧算法只为诊断入口不崩。
            val summary = event.payloadRef.substringBefore(BotResultApplier.STAGED_MARKER).trim()
            summary.ifBlank { context.getString(R.string.agent_bot_result_empty) }
        }
    }

    private companion object {
        const val LOG_TAG = "AgentBotResultPublisher"
    }
}
