package io.zer0.muse.data.agentbot

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.R

/**
 * 把中枢的结果事件翻译成对话正文并发布。
 *
 * ## 为什么单独一层
 *
 * 结果事件有两条消费路径：页面可见时由 ViewModel 的回合取走，页面不可见时由
 * 后台驱动器取走。两条路径必须产出**一模一样**的话——否则同一个任务，
 * 用户在前台看和收到通知看到的措辞会不一样。
 *
 * 这里只做三件事：取摘要、剥掉内部标记、按类型回落。发布失败不抛异常，
 * 返回 false 让调用方把事件留在队列里重试。
 */
class AgentBotResultPublisher(
    private val context: Context,
    private val publication: PublicationService,
) {

    /**
     * 发布一条结果事件。
     *
     * @return true 表示已落进对话（调用方可以消费这条事件）。
     */
    suspend fun publish(event: BotEventEntity, fallbackBotId: String, fallbackSessionId: String): Boolean {
        val text = textOf(event)
        val result = runCatching {
            publication.publish(
                botId = event.botId.ifBlank { fallbackBotId },
                originSessionId = event.originSessionId.ifBlank { fallbackSessionId },
                causeEventIds = event.eventId,
                // 后台结果不属于任何一次用户输入，两个 revision 传同一个值：
                // 不参与"新输入到达就放弃旧批次"的判定。
                requestRevision = 0L,
                taskRevision = 1,
                items = listOf(PublicationService.Item(PublicationKind.TEXT, text)),
            )
        }.onFailure {
            Logger.w(LOG_TAG, "结果发布失败，保留事件待重试: ${event.eventId} | ${it.message}")
        }.getOrNull()
        return result != null && result.committed > 0
    }

    /** 事件正文：摘要优先，空了按成败回落。 */
    fun textOf(event: BotEventEntity): String {
        // payloadRef 末尾可能挂着暂存标记（"[staged=xxx]"），那是给 BotResultApplier 用的，
        // 不能出现在用户看到的正文里。
        val summary = event.payloadRef.substringBefore(BotResultApplier.STAGED_MARKER).trim()
        return when {
            summary.isNotBlank() -> summary
            event.kind == BotEventKind.TASK_FAILED -> context.getString(R.string.agent_bot_result_failed)
            else -> context.getString(R.string.agent_bot_result_empty)
        }
    }

    private companion object {
        const val LOG_TAG = "AgentBotResultPublisher"
    }
}
