package io.zer0.muse.hook

import io.zer0.ai.core.MessageRole
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * P1-4: 楼层式上下文限制 Hook。
 *
 * 以 USER 消息为"楼层",保留最近 N 层(完整 USER + ASSISTANT + TOOL 链),
 * 避免截断到对话中间导致上下文不完整。
 *
 * 与基于 token 数的压缩(ConversationCompressor)的区别:
 *  - 压缩: 把旧消息总结为摘要,减少 token 占用
 *  - 楼层限制: 直接截断超出楼层限制的旧消息,保证每层完整
 *
 * 执行顺序: 楼层限制在压缩之后执行,作为最终截断兜底。
 *
 * 配置:
 *  - floorLimiterEnabled: 开关(默认 false)
 *  - floorLimit: 楼层数(8/16/32,默认 16)
 */
class FloorContextLimiterHook(
    private val settings: SettingsRepository,
) : PromptFinalizeHook {

    companion object {
        private const val TAG = "FloorContextLimiter"
    }

    override val id: String = "floor_context_limiter"
    override val priority: Int = 30  // 低优先级,在其他 PromptFinalizeHook 之后执行

    override suspend fun beforeFinalizePrompt(event: PromptFinalizeEvent): PromptFinalizeResult {
        val enabled = runCatching { settings.floorLimiterEnabledFlow.first() }.getOrDefault(false)
        if (!enabled) return PromptFinalizeResult(event.preparedHistory)

        val floorLimit = runCatching { settings.floorLimitFlow.first() }.getOrDefault(16)
        if (floorLimit <= 0) return PromptFinalizeResult(event.preparedHistory)

        val history = event.preparedHistory
        // 分离 SYSTEM 和非 SYSTEM 消息
        val systemMsgs = history.filter { it.role == MessageRole.SYSTEM }
        val nonSystemMsgs = history.filter { it.role != MessageRole.SYSTEM }

        // 统计 USER 楼层
        val userCount = nonSystemMsgs.count { it.role == MessageRole.USER }
        if (userCount <= floorLimit) return PromptFinalizeResult(event.preparedHistory)

        // 从末尾倒数 floorLimit 个 USER,保留从该位置到末尾的所有消息
        var keepFromIndex = 0
        var countedUsers = 0
        for (i in nonSystemMsgs.indices.reversed()) {
            if (nonSystemMsgs[i].role != MessageRole.USER) continue
            countedUsers++
            if (countedUsers == floorLimit) {
                keepFromIndex = i
                break
            }
        }

        val trimmed = systemMsgs + nonSystemMsgs.drop(keepFromIndex)
        val removedCount = history.size - trimmed.size
        if (removedCount > 0) {
            Logger.i(TAG, "楼层限制: 保留最近 $floorLimit 层 USER 消息,截断 $removedCount 条旧消息")
        }
        return PromptFinalizeResult(trimmed)
    }
}
