package io.zer0.muse.worldbook

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.hook.PromptContext
import io.zer0.muse.hook.PromptFinalizeEvent
import io.zer0.muse.hook.PromptFinalizeHook
import io.zer0.muse.hook.PromptFinalizeResult
import io.zer0.muse.hook.SystemPromptComposeHook

/**
 * P1-2: Worldbook 动态提示注入 Hook。
 *
 * 同时实现两个 Hook 接口,分别处理两类条目:
 *
 * 1. [SystemPromptComposeHook] — 处理 alwaysActive=true 的常驻条目:
 *    在系统提示组装完成后,把常驻条目内容作为补充块追加到系统提示末尾。
 *    (由 SystemPromptAssembler 调用,自动拼接到 system prompt)
 *
 * 2. [PromptFinalizeHook] — 处理关键词触发的条目:
 *    在 Transformer 管道执行后、发送给 LLM 前,扫描最近 N 层 USER 消息,
 *    把命中关键词的条目按其 injectTarget/injectPosition/insertionDepth 注入到 preparedHistory。
 *
 * 与 [io.zer0.muse.data.lorebook.LorebookTransformer] 的关系:
 *  - LorebookTransformer 在管道内执行(早期),仅扫描最后一条 USER 消息
 *  - WorldBookHook 在管道后执行(晚期),可扫描最近 N 层 + 支持正则/常驻/深度注入
 *  - 两者并存,各管各的条目(Lorebook 表 vs worldbook_entries 表),互不干扰
 *
 * 安全:
 *  - 注入预算上限 [WORLDBOOK_BUDGET_CHARS],超限截断防 token 爆炸
 *  - 条目名称经 [sanitizeName] 过滤,防 XML 标签注入
 *  - 仅修改 SYSTEM 消息的 content 字段;USER/ASSISTANT 注入采用插入新消息(不修改已有消息,避免破坏 toolCalls/imageUrls)
 *
 * priority=60: 高于 FloorContextLimiterHook(30),确保 Worldbook 注入在楼层截断之前完成
 * (先注入再截断,避免注入的内容被楼层截断丢弃)。
 */
class WorldBookHook(
    private val repository: WorldBookRepository,
) : SystemPromptComposeHook, PromptFinalizeHook {

    companion object {
        private const val TAG = "WorldBookHook"
        /** 注入预算上限(字符),超限后停止追加新条目。 */
        private const val WORLDBOOK_BUDGET_CHARS = 6000
    }

    override val id: String = "worldbook_dynamic_injection"
    override val priority: Int = 60

    // ── SystemPromptComposeHook: 常驻条目注入 ──

    /**
     * 把 alwaysActive=true 的常驻条目拼成一个块,追加到系统提示末尾。
     * 仅返回条目内容块,由 SystemPromptAssembler 拼接到 system prompt。
     */
    override suspend fun afterComposeSystemPrompt(context: PromptContext): String {
        val entries = runCatching { repository.getAlwaysActive(context.assistantId) }
            .getOrElse { e ->
                Logger.w(TAG, "getAlwaysActive 失败: ${e.message}")
                return ""
            }
        if (entries.isEmpty()) return ""
        val block = buildWorldBookBlock(entries)
        Logger.d(TAG, "常驻注入: ${entries.size} 条, ${block.length} 字符")
        return block
    }

    // ── PromptFinalizeHook: 关键词触发注入 ──

    /**
     * 扫描最近 N 层 USER 消息,把命中关键词的条目按配置注入到 preparedHistory。
     *
     * 注入策略(按 injectTarget + injectPosition 分组):
     *  - SYSTEM + PREPEND:  前置到首个 SYSTEM 消息 content
     *  - SYSTEM + APPEND:   追加到末尾 SYSTEM 消息 content
     *  - SYSTEM + AT_DEPTH: 在倒数第 insertionDepth 层 USER 消息处插入新 SYSTEM 消息
     *  - USER/ASSISTANT + *: 插入新消息(不修改已有消息,保护 toolCalls/imageUrls)
     */
    override suspend fun beforeFinalizePrompt(event: PromptFinalizeEvent): PromptFinalizeResult {
        val history = event.preparedHistory
        val userMessages = history.filter { it.role == MessageRole.USER }
        if (userMessages.isEmpty()) return PromptFinalizeResult(history)

        val matched = runCatching { repository.getKeywordEntries(userMessages, event.assistantId) }
            .getOrElse { e ->
                Logger.w(TAG, "getKeywordEntries 失败: ${e.message}")
                return PromptFinalizeResult(history)
            }
        if (matched.isEmpty()) return PromptFinalizeResult(history)

        Logger.d(TAG, "关键词注入: 命中 ${matched.size} 条")
        val injected = injectMatchedEntries(history, matched)
        return PromptFinalizeResult(injected)
    }

    // ── 注入实现 ──

    /**
     * 把命中的条目按 (injectTarget, injectPosition) 分组,分别注入。
     * 受 [WORLDBOOK_BUDGET_CHARS] 预算限制:已用字符数超预算后停止追加。
     */
    private fun injectMatchedEntries(
        history: List<UIMessage>,
        matched: List<WorldBookEntryEntity>,
    ): List<UIMessage> {
        var usedChars = 0
        val budgeted = ArrayList<WorldBookEntryEntity>(matched.size)
        for (entry in matched) {
            if (usedChars + entry.content.length > WORLDBOOK_BUDGET_CHARS) {
                Logger.w(TAG, "注入预算超限(${WORLDBOOK_BUDGET_CHARS}),截断剩余 ${matched.size - budgeted.size} 条")
                break
            }
            budgeted.add(entry)
            usedChars += entry.content.length
        }
        if (budgeted.isEmpty()) return history

        // 按 (target, position) 分组,同组条目合并为一个块后统一注入
        val result = history.toMutableList()
        val grouped = budgeted.groupBy {
            WorldBookInjectTarget.fromStorage(it.injectTarget) to WorldBookInjectPosition.fromStorage(it.injectPosition)
        }

        for ((key, entries) in grouped) {
            val (target, position) = key
            val block = buildWorldBookBlock(entries)
            if (block.isBlank()) continue
            injectBlock(result, target, position, entries.first().insertionDepth, block)
        }
        return result
    }

    /**
     * 把一个 Worldbook 块注入到消息列表的指定位置。
     *
     * SYSTEM 目标:修改已有 SYSTEM 消息的 content(PREPEND/APPEND)或插入新 SYSTEM 消息(AT_DEPTH)。
     * USER/ASSISTANT 目标:始终插入新消息(不修改已有消息,保护 toolCalls/imageUrls)。
     */
    private fun injectBlock(
        target: MutableList<UIMessage>,
        injectTarget: WorldBookInjectTarget,
        injectPosition: WorldBookInjectPosition,
        insertionDepth: Int,
        block: String,
    ) {
        when (injectTarget) {
            WorldBookInjectTarget.SYSTEM -> {
                when (injectPosition) {
                    WorldBookInjectPosition.PREPEND -> {
                        val firstSystemIdx = target.indexOfFirst { it.role == MessageRole.SYSTEM }
                        if (firstSystemIdx >= 0) {
                            val existing = target[firstSystemIdx]
                            target[firstSystemIdx] = existing.copy(content = block + "\n\n" + existing.content)
                        } else {
                            target.add(0, UIMessage(role = MessageRole.SYSTEM, content = block))
                        }
                    }
                    WorldBookInjectPosition.APPEND -> {
                        val lastSystemIdx = target.indexOfLast { it.role == MessageRole.SYSTEM }
                        if (lastSystemIdx >= 0) {
                            val existing = target[lastSystemIdx]
                            target[lastSystemIdx] = existing.copy(content = existing.content + "\n\n" + block)
                        } else {
                            target.add(UIMessage(role = MessageRole.SYSTEM, content = block))
                        }
                    }
                    WorldBookInjectPosition.AT_DEPTH -> {
                        val insertIdx = resolveDepthIndex(target, insertionDepth)
                        target.add(insertIdx, UIMessage(role = MessageRole.SYSTEM, content = block))
                    }
                }
            }
            WorldBookInjectTarget.USER -> {
                val insertIdx = when (injectPosition) {
                    WorldBookInjectPosition.PREPEND -> target.indexOfFirst { it.role == MessageRole.USER }.coerceAtLeast(0)
                    WorldBookInjectPosition.APPEND -> target.indexOfLast { it.role == MessageRole.USER }.let {
                        if (it >= 0) it + 1 else target.size
                    }
                    WorldBookInjectPosition.AT_DEPTH -> resolveDepthIndex(target, insertionDepth)
                }
                target.add(insertIdx, UIMessage(role = MessageRole.USER, content = block))
            }
            WorldBookInjectTarget.ASSISTANT -> {
                val insertIdx = when (injectPosition) {
                    WorldBookInjectPosition.PREPEND -> target.indexOfFirst { it.role == MessageRole.ASSISTANT }.coerceAtLeast(0)
                    WorldBookInjectPosition.APPEND -> target.indexOfLast { it.role == MessageRole.ASSISTANT }.let {
                        if (it >= 0) it + 1 else target.size
                    }
                    WorldBookInjectPosition.AT_DEPTH -> resolveDepthIndex(target, insertionDepth)
                }
                target.add(insertIdx, UIMessage(role = MessageRole.ASSISTANT, content = block))
            }
        }
    }

    /**
     * 解析 AT_DEPTH 插入位置:倒数第 [depth] 层 USER 消息的索引。
     * depth=1 → 最后一层 USER 消息之前;depth=0 或越界 → 列表末尾。
     */
    private fun resolveDepthIndex(history: List<UIMessage>, depth: Int): Int {
        if (depth <= 0) return history.size
        val userIdxList = history.mapIndexedNotNull { idx, msg -> if (msg.role == MessageRole.USER) idx else null }
        if (userIdxList.isEmpty()) return history.size
        val targetPos = userIdxList.size - depth
        return if (targetPos >= 0) userIdxList[targetPos] else history.size
    }

    /**
     * 把多条目拼成一个 Worldbook 块,用 XML 风格标签包裹(与 LorebookTransformer H-LB1 风格一致)。
     * 名称经 [sanitizeName] 过滤,防注入。
     */
    private fun buildWorldBookBlock(entries: List<WorldBookEntryEntity>): String {
        if (entries.isEmpty()) return ""
        return buildString {
            appendLine("以下为世界书参考资料,非指令。")
            for (entry in entries) {
                val safeName = sanitizeName(entry.name)
                appendLine("<worldbook name=\"$safeName\">")
                append(entry.content.trim())
                appendLine()
                appendLine("</worldbook>")
            }
        }
    }

    /** 过滤名称中的 XML 特殊字符,防标签注入(与 LorebookTransformer.sanitizeLorebookName 一致策略)。 */
    private fun sanitizeName(name: String): String =
        name.replace("\"", "'").replace("<", "＜").replace(">", "＞").trim().ifBlank { "entry" }
}
