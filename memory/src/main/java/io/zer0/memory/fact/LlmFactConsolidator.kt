package io.zer0.memory.fact

import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.0.92: LLM 记忆整合器 — "定期模型整合"的核心实现(相似簇交给大模型合并)。
 *
 * 背景: 每日流水线此前只有规则去重([FactStore.dedupPass],字符相似度/实体键),
 * 语义重复但表述差异大的记忆(如"明天考四级" vs "明天上午9点考英语四级")
 * 会长期堆积。本类把 [FactStore.findSimilarGroups] 聚出的相似簇交给大模型合并成一条,
 * 保留全部关键信息;与手动"整理记忆"共用同一实现,由每日流水线自动触发。
 *
 * 安全策略(宁漏不错):
 *  - 单簇 LLM 失败/超时 → 跳过该簇,不影响其它簇与主流程;
 *  - 模型返回疑似"未合并原样输出"(残留编号 / __SKIP__) → 放弃该簇;
 *  - 合并结果长度超过簇内全部原文总长 → 视为异常输出,放弃该簇;
 *  - 置顶记忆不参与合并(用户主动固定);
 *  - 关键记忆的变更由 FactStore 的修订记录兜底,可回溯。
 */
class LlmFactConsolidator(
    private val llmClient: MemoryLlmClient,
    /** 目标模型,null 时由 [MemoryLlmClient] 实现侧用默认模型。 */
    private val model: Model? = null,
) {

    /**
     * 对指定作用域执行 LLM 合并整合。
     *
     * @return 实际合并的簇数(0 = 没有可合并的簇或全部失败)
     */
    suspend fun consolidate(store: FactStore, scope: String, spaceId: String, maxGroups: Int = DEFAULT_MAX_GROUPS): Int =
        withContext(Dispatchers.IO) {
            val groups = resultOf { store.findSimilarGroups(scope, spaceId, maxGroups) }
                .onError { msg, t -> Logger.w(TAG, "查找相似记忆簇失败: ${t?.message ?: msg}") }
                .getOrNull() ?: return@withContext 0

            var mergedCount = 0
            for (group in groups) {
                if (group.size < 2) continue
                // 置顶记忆保护: 含置顶条目的簇不参与自动合并
                if (group.any { it.pinnedAt != null }) continue
                val mergedText = mergeGroupWithLlm(group) ?: continue
                // 保留重要度最高的一条作为 keeper
                val keeper = group.maxByOrNull { it.importance } ?: group.first()
                // v2.6.2（记忆审计）: 改为 FactStore.mergeIntoKeeper 原子合并 —— keeper 更新与
                // 其余成员删除在同一 Room 事务内完成。旧流程“先逐条删、再 update keeper”不在同一
                // 事务:部分删除成功而 keeper 更新失败时原始事实已经丢失,“回写”只是新增一条,
                // 不会恢复被删事实的原字段/置顶/标签/链接/修订。
                // 另一注意事项:不调 update() 的内部自动去重,避免与簇内删除叠加成“双删清空”。
                val removableIds = group.filter { it.id != keeper.id }.map { it.id }
                val removed = resultOf {
                    store.mergeIntoKeeper(
                        keeperId = keeper.id,
                        mergedContent = mergedText,
                        removableIds = removableIds,
                        scope = scope,
                        spaceId = spaceId,
                    )
                }.onError { msg, t -> Logger.w(TAG, "原子合并记忆失败: ${t?.message ?: msg}") }
                    .getOrNull()
                if (removed != null && removed >= 0) mergedCount++
            }
            if (mergedCount > 0) {
                Logger.i(TAG, "LLM 记忆整合: 合并 $mergedCount 组重复记忆 (scope=$scope)")
            }
            mergedCount
        }

    /**
     * 遍历库内全部 scope+space 组合执行整合(每日流水线用)。
     *
     * @return 实际合并的簇数
     */
    suspend fun consolidateAll(store: FactStore, maxGroups: Int = DEFAULT_MAX_GROUPS): Int {
        val pairs = resultOf { store.listScopeSpacePairs() }
            .onError { msg, t -> Logger.w(TAG, "枚举记忆空间失败: ${t?.message ?: msg}") }
            .getOrNull() ?: return 0
        var total = 0
        for ((scope, spaceId) in pairs) {
            total += consolidate(store, scope, spaceId, maxGroups)
        }
        return total
    }

    /**
     * v19: 手动合并 —— 把用户选中的若干条记忆合成一条**草稿**,只返回文本、不落库。
     *
     * 与自动整合共用同一套 prompt 与护栏([mergeTexts]):保留全部关键信息、去重、
     * `__SKIP__` 表示不该合并、超长或疑似原样输出视为无效。差异只有两点:
     *  - 输出上限更宽松([MANUAL_MERGE_MAX_TOKENS]):手动场景可能一次选 5 条长记忆;
     *  - 置顶条目不再排除:用户明确选了它就是要合并,置顶保护只服务于自动路径。
     *
     * @param texts 参与合并的记忆正文(空白项忽略)
     * @return 合并草稿;少于 2 条、模型显式跳过、输出异常或超时时返回 null
     */
    suspend fun synthesize(texts: List<String>): String? {
        val cleaned = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.size < 2) return null
        return try {
            mergeTexts(cleaned, MANUAL_MERGE_MAX_TOKENS, MANUAL_MERGE_TIMEOUT_MS)
        } catch (e: TimeoutCancellationException) {
            // 客户端自身的超时也抛 TimeoutCancellationException(带自己的 coroutine),
            // 它不属于调用方的取消,按“本次没合成出来”降级;真正的取消继续向上抛。
            Logger.w(TAG, "手动合并生成超时: ${e.message}")
            null
        }
    }

    /** 调 LLM 把一组相似记忆合并成一条;失败、显式跳过或疑似未合并时返回 null。 */
    private suspend fun mergeGroupWithLlm(group: List<FactStore.Fact>): String? =
        mergeTexts(group.map { it.fact }, AUTO_MERGE_MAX_TOKENS, MERGE_TIMEOUT_MS)

    /**
     * 自动整合与手动合并的共用实现:同一套 prompt、同一套输出护栏。
     *
     * @param texts 参与合并的正文(按展示顺序编号进 prompt)
     * @param maxTokens 输出上限
     * @param timeoutMs 本次调用的超时
     */
    private suspend fun mergeTexts(texts: List<String>, maxTokens: Int, timeoutMs: Long): String? {
        val totalLength = texts.sumOf { it.length }
        val user = buildString {
            appendLine("以下是多条内容重复或高度相似的记忆,请把它们合并成一条:保留所有关键信息")
            appendLine("(人名、时间、地点、数字、事件),去重,语言自然简洁,不要遗漏任何事实细节。")
            appendLine("如果发现这些记忆其实内容不同、不应该合并,请只输出一个字符串 __SKIP__。")
            appendLine()
            texts.forEachIndexed { idx, text -> appendLine("${idx + 1}. $text") }
            appendLine()
            append("请直接输出合并后的一条记忆,不要任何前缀、编号或引号:")
        }
        val result = resultOf {
            withTimeoutOrNull(timeoutMs) {
                llmClient.callText(
                    systemPrompt = SYSTEM_PROMPT,
                    userContent = user,
                    model = model,
                    temperature = 0.3f,
                    maxTokens = maxTokens,
                    timeoutMs = timeoutMs,
                ).trim()
            }
        }.onError { msg, t -> Logger.w(TAG, "LLM 合并记忆失败: ${t?.message ?: msg}") }
            .getOrNull() ?: return null

        // 启发式护栏: 显式跳过 / 异常输出 / 疑似未合并原样输出
        val text = result.trim().trim('"', '\'', '「', '」', '“', '”').trim()
        val looksUnmerged = text.length > totalLength ||
            text.startsWith("1.") || text.startsWith("1、") ||
            text.contains("\n2.") || text.contains("\n2、")
        return when {
            text.isBlank() || text == SKIP_MARKER || looksUnmerged -> null
            else -> text.takeIf { it.length > 2 }
        }
    }

    companion object {
        private const val TAG = "LlmFactConsolidator"

        /** 单次整合最多处理的相似簇数(限制 LLM 调用次数)。 */
        const val DEFAULT_MAX_GROUPS = 10

        /** 模型显式表示"不应合并"的标记。 */
        private const val SKIP_MARKER = "__SKIP__"

        /** 单簇 LLM 超时(毫秒)。 */
        private const val MERGE_TIMEOUT_MS = 30_000L

        /** 自动整合单簇输出上限:簇内条数由聚簇算法限定,内容通常很短。 */
        private const val AUTO_MERGE_MAX_TOKENS = 300

        /**
         * 手动合并输出上限:用户可能一次选中多条长记忆,300 token 会在半句处截断,
         * 截断的结果看起来像“合并成功”但丢了事实,比失败更糟。
         */
        private const val MANUAL_MERGE_MAX_TOKENS = 1_200

        /** 手动合并超时:输入更长,给模型比自动路径更宽的时间。 */
        private const val MANUAL_MERGE_TIMEOUT_MS = 45_000L

        private const val SYSTEM_PROMPT = "你是记忆整理助手。你的任务是把同一簇的重复记忆合并成一条:" +
            "保留所有关键信息,去重,语言自然简洁。宁可不合并,也不要编造或丢失关键信息。"
    }
}
