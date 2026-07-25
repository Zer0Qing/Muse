package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.promptinjection.PromptInjectionEntity

/**
 * PromptInjection(模式注入)Transformer — Phase 8.5。
 *
 * 独立编写实现。
 *
 * 行为:
 *  - 从 context.extra("prompt_injections") 取预查询好的注入条目
 *    (ChatViewModel 已根据当前模式 mode 过滤)
 *    extra 契约: key="prompt_injections",类型 List<PromptInjectionEntity>,可为 null
 *    (泛型 erasure 下 unchecked cast,见下方 @Suppress 注释)
 *  - 防御性过滤 enabled=false 的条目(不依赖调用方已过滤)
 *  - 按 priority 降序排列
 *  - 根据 insertionPosition 决定注入位置:
 *    - [POSITION_BEFORE_SYSTEM]: 插在所有 SYSTEM 消息之前
 *    - [POSITION_AFTER_SYSTEM]: 插在所有 SYSTEM 消息之后(默认)
 *    - 未知值: 记 Logger.w 后按 after_system 处理
 *  - 单条注入为 SYSTEM 消息,内容前缀 "[ModeInjection: name]"
 *  - 单条 content 超过 [MAX_SINGLE_CONTENT] 字符截断;合计超过 [MAX_TOTAL_CONTENT] 字符
 *    跳过后续条目,防止注入撑爆 prompt 预算
 *  - name 中的换行 / "]" 会被替换为下划线,避免破坏 "[ModeInjection: ...]" 前缀格式
 *
 * 与 Lorebook 区别: 模式注入是开关式(用户切换模式立即生效),
 * Lorebook 是关键词触发式(扫描用户消息内容)。
 */
class PromptInjectionTransformer : Transformer {

    override val name: String = "PromptInjection"

    override suspend fun transform(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> {
        // extra 契约: key="prompt_injections",值为 List<PromptInjectionEntity>。
        // 泛型 erasure 下 as? List<*> 无法在运行期校验元素类型,延迟的 ClassCastException
        // 风险由调用方保证类型契约承担。
        val injections = (context.extra("prompt_injections") as? List<*>)?.filterIsInstance<PromptInjectionEntity>()
            ?: return messages
        // 防御性过滤:不依赖调用方已过滤 enabled
        val enabledInjections = injections.filter { it.enabled }
        if (enabledInjections.isEmpty()) return messages

        val beforeSystem = mutableListOf<UIMessage>()
        val afterSystem = mutableListOf<UIMessage>()
        var totalLen = 0
        enabledInjections.sortedByDescending { it.priority }.forEach { inj ->
            // 单条 content 截断,防撑爆 prompt 预算
            val rawContent = inj.content
            val truncatedContent = if (rawContent.length > MAX_SINGLE_CONTENT) {
                Logger.w(TAG, "注入条目 '${inj.name}' content 长度 ${rawContent.length} 超过 $MAX_SINGLE_CONTENT,已截断")
                rawContent.take(MAX_SINGLE_CONTENT)
            } else {
                rawContent
            }
            // 合计上限:超限跳过后续条目
            if (totalLen + truncatedContent.length > MAX_TOTAL_CONTENT) {
                Logger.w(TAG, "注入合计已超 $MAX_TOTAL_CONTENT 字符上限,跳过条目 '${inj.name}'")
                return@forEach
            }
            totalLen += truncatedContent.length

            // name 转义:换行 / "]" 替换为下划线,避免破坏 "[ModeInjection: name]" 前缀格式
            val safeName = inj.name.replace(NAME_ESCAPE_REGEX, "_").trim()
            val sysMsg = UIMessage(
                role = MessageRole.SYSTEM,
                content = "[ModeInjection: $safeName]\n$truncatedContent",
            )
            when (inj.insertionPosition) {
                POSITION_BEFORE_SYSTEM -> beforeSystem.add(sysMsg)
                POSITION_AFTER_SYSTEM -> afterSystem.add(sysMsg)
                else -> {
                    Logger.w(TAG, "未知 insertionPosition='${inj.insertionPosition}',条目 '${inj.name}' 按 $POSITION_AFTER_SYSTEM 处理")
                    afterSystem.add(sysMsg)
                }
            }
        }

        val lastSystemIdx = messages.indexOfLast { it.role == MessageRole.SYSTEM }
        val result = mutableListOf<UIMessage>()
        result.addAll(beforeSystem)
        // Phase 8.5 修复: 无 SYSTEM 消息时,after_system 条目插在最前(紧随 beforeSystem 之后),
        // 避免原逻辑 lastSystemIdx=-1 时 afterSystem 被静默丢弃
        if (afterSystem.isNotEmpty() && lastSystemIdx == -1) {
            result.addAll(afterSystem)
        }
        messages.forEachIndexed { idx, msg ->
            if (afterSystem.isNotEmpty() && idx == lastSystemIdx) {
                result.add(msg)
                result.addAll(afterSystem)
            } else {
                result.add(msg)
            }
        }
        return result
    }

    companion object {
        private const val TAG = "PromptInjectionTransformer"

        /** 插入位置常量:插在所有 SYSTEM 消息之前。 */
        const val POSITION_BEFORE_SYSTEM = "before_system"

        /** 插入位置常量:插在所有 SYSTEM 消息之后(默认)。 */
        const val POSITION_AFTER_SYSTEM = "after_system"

        /** 单条注入 content 字符上限,超限截断。 */
        private const val MAX_SINGLE_CONTENT = 4000

        /** 所有注入条目 content 合计字符上限,超限跳过后续条目。 */
        private const val MAX_TOTAL_CONTENT = 10000

        // name 中需转义的字符:换行符 / "]" (会破坏 "[ModeInjection: name]" 前缀格式)
        private val NAME_ESCAPE_REGEX = Regex("[\\r\\n\\]]")
    }
}
