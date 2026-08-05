package io.zer0.muse.tools

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * v1.0.53 Phase 2: 工作流断点恢复日志(参考开源实现 lib/workflow/journal.ts)。
 *
 * 文件格式: `<journalDir>/<runId>.jsonl`,每行一个 JSON [Entry]。
 * 失败/中断后传入 resume 标识重跑,已完成的节点直接命中缓存秒回,
 * 首个未缓存节点起重新执行。
 *
 * ## 缓存粒度(v1.1 §5.4 分级缓存)
 *
 * [Entry.nodeKind] 决定 resume 时的缓存行为:
 *  - [NODE_KIND_TOOL_ONLY] / [NODE_KIND_LLM_DETERMINISTIC]:命中即返回缓存 result(确定性输出)
 *  - [NODE_KIND_LLM_GENERATIVE]:命中后 status 标记 done 但 result 置空,强制调用方重新执行生成新 result
 *    (温度>0 的 LLM 输出不可复现,resume 时应重新生成;但"节点已完成"的状态可缓存,避免误重跑前置节点)
 *  - [NODE_KIND_DELEGATION]:命中后返回缓存 result(工具结果确定性可缓存;LLM 总结部分由调用方决定是否重生成)
 *
 * ## 容错
 *  - 读取时遇到非法行(损坏)跳过并 log warn,不抛异常(整体降级为重跑)
 *  - 文件不存在时视为全新 run(resume 返回空缓存)
 *
 * @param journalDir 日志目录(通常 filesDir/workflow_journals),不存在时自动创建
 */
class WorkflowJournal(
    private val journalDir: File,
) {
    companion object {
        private const val TAG = "WorkflowJournal"

        // nodeKind 取值常量(对齐 §5.4 节点类型分级)
        /** 纯工具调用节点(无 LLM,确定性输出)。 */
        const val NODE_KIND_TOOL_ONLY = "tool_only"
        /** LLM 总结节点(温度=0,如条件判断 YES/NO,输出空间极小视为确定性)。 */
        const val NODE_KIND_LLM_DETERMINISTIC = "llm_deterministic"
        /** LLM 生成节点(温度>0,如 aggregateResults 的 LLM_REVIEW,输出不可复现)。 */
        const val NODE_KIND_LLM_GENERATIVE = "llm_generative"
        /** delegate_agent / subagent_run 节点(工具结果可缓存,LLM 总结按上级行规则)。 */
        const val NODE_KIND_DELEGATION = "delegation"

        /** 默认日志保留时长:7 天。 */
        const val DEFAULT_MAX_AGE_MS = 7L * 24 * 3600 * 1000
    }

    init {
        if (!journalDir.exists()) journalDir.mkdirs()
    }

    /**
     * 单条日志记录。
     *
     * @property nodeSeq 节点序号(从 0 递增,对应 workflow 节点执行顺序)
     * @property key 节点输入+身份的确定性哈希(SHA-256 前 16 位 hex),用于 resume 时判断输入是否变化
     * @property result 节点输出结果(llm_generative 在 resume 时会被置空)
     * @property status done|failed
     * @property nodeKind 节点类型(决定缓存策略,见类注释)
     * @property ts 记录时间戳
     */
    @Serializable
    data class Entry(
        val nodeSeq: Int,
        val key: String,
        val result: String,
        val status: String,
        val nodeKind: String,
        val ts: Long,
    )

    /**
     * resume 结果。
     *
     * @property resumeFromSeq 首个需要重新执行的节点序号(之前全部命中缓存)
     * @property cached 命中缓存的条目(nodeSeq → Entry;llm_generative 条目的 result 已置空)
     */
    data class ResumeResult(
        val resumeFromSeq: Int,
        val cached: Map<Int, Entry>,
    )

    /**
     * 记录一个节点的完成结果(增量追加到 JSONL 文件)。
     *
     * @param runId 工作流运行 id(对应文件名)
     * @param nodeSeq 节点序号
     * @param key 节点输入哈希(用 [computeKey] 生成)
     * @param result 节点输出结果
     * @param status done|failed
     * @param nodeKind 节点类型([NODE_KIND_*] 常量)
     */
    suspend fun record(
        runId: String,
        nodeSeq: Int,
        key: String,
        result: String,
        status: String,
        nodeKind: String,
    ) = withContext(Dispatchers.IO) {
        resultOf {
            val file = pathOf(runId)
            file.parentFile?.mkdirs()
            val entry = Entry(
                nodeSeq = nodeSeq,
                key = key,
                result = result,
                status = status,
                nodeKind = nodeKind,
                ts = System.currentTimeMillis(),
            )
            file.appendText(AppJson.encodeToString(Entry.serializer(), entry) + "\n")
        }.onError { msg, t ->
            Logger.w(TAG, "record 失败 runId=$runId seq=$nodeSeq: $msg", t)
        }
    }

    /**
     * 加载全部条目,返回 Map<nodeSeq, Entry>。
     *
     * 文件不存在或全部损坏时返回空 Map。
     */
    suspend fun load(runId: String): Map<Int, Entry> = withContext(Dispatchers.IO) {
        val file = pathOf(runId)
        if (!file.exists()) return@withContext emptyMap()

        val map = mutableMapOf<Int, Entry>()
        file.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val parsed = resultOf {
                    AppJson.decodeFromString(Entry.serializer(), line)
                }.getOrNull()
                if (parsed != null) {
                    // 同一 nodeSeq 后写覆盖先写(支持失败后重试覆盖 failed 记录)
                    map[parsed.nodeSeq] = parsed
                } else {
                    Logger.w(TAG, "跳过损坏的 JSONL 行: ${line.take(80)}")
                }
            }
        }
        map
    }

    /**
     * 从指定 runId 恢复:返回命中缓存条目 + 首个需重新执行的 nodeSeq。
     *
     * 判定逻辑:
     *  1. 按 nodeSeq 升序遍历已记录条目
     *  2. 遇到 status!=done 的条目 → 该 seq 起重新执行(之前的 done 条目命中缓存)
     *  3. 遇到 key 不匹配的条目(节点输入变化)→ 该 seq 起重新执行
     *  4. 全部命中 → resumeFromSeq = maxSeq+1(无新节点需执行,调用方自行判断是否结束)
     *
     * 缓存条目处理(按 nodeKind):
     *  - [NODE_KIND_LLM_GENERATIVE]:result 置空(强制调用方重新生成),status 保留 done
     *  - 其他:result 原样返回
     *
     * @param expectedKeys 调用方本次期望的 (nodeSeq → key) 映射,用于校验缓存是否仍匹配当前输入。
     *                     为空时不校验 key(仅按 status 判断),适合节点定义未变的简单 resume。
     */
    suspend fun resume(
        runId: String,
        expectedKeys: Map<Int, String> = emptyMap(),
    ): ResumeResult = withContext(Dispatchers.IO) {
        val loaded = load(runId)
        if (loaded.isEmpty()) return@withContext ResumeResult(resumeFromSeq = 0, cached = emptyMap())

        val cached = mutableMapOf<Int, Entry>()
        var resumeFromSeq = loaded.keys.maxOrNull()!! + 1  // 默认:全部命中,无需重跑

        for (seq in loaded.keys.sorted()) {
            val entry = loaded[seq]!!
            // 条件1:status 非 done → 该 seq 起重跑
            if (entry.status != "done") {
                resumeFromSeq = seq
                break
            }
            // 条件2:key 与期望不匹配(节点输入变化)→ 该 seq 起重跑
            val expectedKey = expectedKeys[seq]
            if (expectedKey != null && entry.key != expectedKey) {
                resumeFromSeq = seq
                break
            }
            // 命中缓存:llm_generative 置空 result,其他原样保留
            val cachedEntry = if (entry.nodeKind == NODE_KIND_LLM_GENERATIVE) {
                entry.copy(result = "")
            } else {
                entry
            }
            cached[seq] = cachedEntry
        }
        ResumeResult(resumeFromSeq = resumeFromSeq, cached = cached)
    }

    /**
     * 删除过期日志(保留最近 maxAgeMs 内的)。
     *
     * 按文件最后修改时间判断;删除失败仅 log warn 不抛异常。
     */
    suspend fun cleanup(maxAgeMs: Long = DEFAULT_MAX_AGE_MS) = withContext(Dispatchers.IO) {
        if (!journalDir.exists()) return@withContext
        val cutoff = System.currentTimeMillis() - maxAgeMs
        journalDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                if (!file.delete()) {
                    Logger.w(TAG, "cleanup 删除失败: ${file.name}")
                }
            }
        }
    }

    /** 诊断:获取 runId 对应的日志文件路径(可能不存在)。 */
    fun pathOf(runId: String): File = File(journalDir, "$runId.jsonl")

    /**
     * 计算节点输入+身份的确定性哈希(SHA-256 前 16 位 hex)。
     *
     * 用于 resume 时判断节点输入是否变化:输入变化则缓存失效,该节点重新执行。
     *
     * @param prompt 节点输入(任务描述 + 依赖结果摘要)
     * @param nodeIdentity 节点身份(节点 id + assistantId + taskTemplate)
     */
    fun computeKey(prompt: String, nodeIdentity: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = "$prompt|$nodeIdentity"
        val hash = md.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }
}
