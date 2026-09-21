package io.zer0.memory.fact

import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.0.92: LLM 记忆整合器 — "定期模型整合"的核心实现(参考 Hana 平台 Dream 机制)。
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
    suspend fun consolidate(
        store: FactStore,
        scope: String,
        spaceId: String,
        maxGroups: Int = DEFAULT_MAX_GROUPS,
    ): Int = withContext(Dispatchers.IO) {
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
            // v1.0.92: 先删重复成员、再更新 keeper(反序)。
            // FactStore.update 内部有"更新后自动去重合并"(v12):若先更新,新内容与簇内
            // 其它成员相似时会触发第二重合并/删除,与我们的删除叠加成"双删清空";
            // 反序后 update 时重复项已不在,不会再误合并。
            group.filter { it.id != keeper.id }.forEach { other ->
                resultOf { store.delete(other.id) }
                    .onError { msg, t -> Logger.w(TAG, "删除重复记忆失败: ${t?.message ?: msg}") }
            }
            val updated = resultOf { store.update(keeper.id, mergedText, scope) }
                .onError { msg, t -> Logger.w(TAG, "更新合并记忆失败: ${t?.message ?: msg}") }
                .getOrNull() == true
            if (updated) {
                mergedCount++
            } else {
                // 兜底:更新失败时把合并结果作为新事实写回,避免"删了旧条却丢了新内容"
                val restored = resultOf {
                    store.add(
                        FactStore.Fact(
                            fact = mergedText,
                            entityKey = keeper.entityKey,
                            importance = keeper.importance,
                        ),
                        scope = scope,
                        spaceId = spaceId,
                    )
                }.onError { msg, t -> Logger.w(TAG, "回写合并结果失败: ${t?.message ?: msg}") }
                    .isSuccess
                if (restored) mergedCount++
            }
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

    /** 调 LLM 把一组相似记忆合并成一条;失败、显式跳过或疑似未合并时返回 null。 */
    private suspend fun mergeGroupWithLlm(group: List<FactStore.Fact>): String? {
        val totalLength = group.sumOf { it.fact.length }
        val user = buildString {
            appendLine("以下是多条内容重复或高度相似的记忆,请把它们合并成一条:保留所有关键信息")
            appendLine("(人名、时间、地点、数字、事件),去重,语言自然简洁,不要遗漏任何事实细节。")
            appendLine("如果发现这些记忆其实内容不同、不应该合并,请只输出一个字符串 __SKIP__。")
            appendLine()
            group.forEachIndexed { idx, fact -> appendLine("${idx + 1}. ${fact.fact}") }
            appendLine()
            append("请直接输出合并后的一条记忆,不要任何前缀、编号或引号:")
        }
        val result = resultOf {
            withTimeoutOrNull(MERGE_TIMEOUT_MS) {
                llmClient.callText(
                    systemPrompt = SYSTEM_PROMPT,
                    userContent = user,
                    model = model,
                    temperature = 0.3f,
                    maxTokens = 300,
                    timeoutMs = MERGE_TIMEOUT_MS,
                ).trim()
            }
        }.onError { msg, t -> Logger.w(TAG, "LLM 合并记忆失败: ${t?.message ?: msg}") }
            .getOrNull() ?: return null

        // 启发式护栏: 显式跳过 / 异常输出 / 疑似未合并原样输出
        val text = result.trim().trim('"', '\'', '「', '」', '“', '”').trim()
        if (text.isBlank() || text == SKIP_MARKER) return null
        if (text.length > totalLength) return null
        if (text.startsWith("1.") || text.startsWith("1、")) return null
        if (text.contains("\n2.") || text.contains("\n2、")) return null
        return text.takeIf { it.length > 2 }
    }

    companion object {
        private const val TAG = "LlmFactConsolidator"

        /** 单次整合最多处理的相似簇数(限制 LLM 调用次数)。 */
        const val DEFAULT_MAX_GROUPS = 10

        /** 模型显式表示"不应合并"的标记。 */
        private const val SKIP_MARKER = "__SKIP__"

        /** 单簇 LLM 超时(毫秒)。 */
        private const val MERGE_TIMEOUT_MS = 30_000L

        private const val SYSTEM_PROMPT = "你是记忆整理助手。你的任务是把同一簇的重复记忆合并成一条:" +
            "保留所有关键信息,去重,语言自然简洁。宁可不合并,也不要编造或丢失关键信息。"
    }
}
