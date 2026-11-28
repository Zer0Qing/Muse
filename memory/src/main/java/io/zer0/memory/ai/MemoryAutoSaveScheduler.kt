package io.zer0.memory.ai

import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.observe.FailureClassifier
import io.zer0.memory.pii.PiiGuard
import io.zer0.memory.prompt.PromptExampleGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * v1.0.52 P2-3: AI 驱动记忆自动管理。
 *
 * 职责:
 *  - 定时分析对话历史,提取结构化记忆(实体/关系/更新/合并/画像)
 *  - 将分析结果落库(创建/更新/合并事实 + 建立知识图谱边)
 *  - 批量自动分类未分类记忆
 *
 * 与 [io.zer0.memory.deep.DeepMemoryProcessor] 的区别:
 *  - DeepMemoryProcessor: 从摘要 diff 提取原子事实(daily pipeline,离线)
 *  - MemoryAutoSaveScheduler: 从完整对话提取结构化记忆(实时 autoSave,在线)
 *  两者互补:DeepMemory 做离线深度提取,AutoSave 做在线实时提取。
 *
 * 触发时机(由 ChatViewModel 调用):
 *  - 会话结束/切换时(notifySessionEndForCurrent),调用侧有 30s 同会话去重窗口
 *  - C-05: 修正注释 — 实际并非"每 N 轮"触发,AUTO_SAVE_TURN_INTERVAL 已不再作为触发条件
 *    (保留常量仅为兼容引用);分析失败时本调度器自动延迟补跑一次(绕过调用侧去重窗口)。
 *
 * 并发控制:用 [Semaphore] 限制同时进行的分析任务(默认 1,避免 LLM 限流)。
 */
class MemoryAutoSaveScheduler(
    private val factDbProvider: FactDbProvider,
    private val llmClient: MemoryLlmClient,
    private val scope: CoroutineScope,
    /**
     * v2.6.2（用户反馈）: 记忆星座补边构建器。
     *
     * 会话结束抽取事实后，用确定性算法补齐事实间的边（实体键/同会话/同分类/语义近邻），
     * 让星座连成网络。为 null 时跳过（测试降级）。
     */
    private val edgeBuilder: MemoryEdgeBuilder? = null,
    /**
     * v2.6.2: 语义近邻向量提供者（由 app 层注入 EmbeddingService）；
     * null 表示跳过语义层（本地无 embedding 时）。
     */
    private val semanticProvider: MemoryEdgeBuilder.SemanticProvider? = null,
    /**
     * v2.6.2: LLM 深化开关提供者（读用户配置）；返回 true 时对弱边用大模型判定关系类型。
     */
    private val edgeEnhanceEnabled: (() -> Boolean)? = null,
    /**
     * v12 (T2-3): 已处理内容指纹的持久化文件。null 时退化为纯内存缓存（测试/无 filesDir 环境）。
     * 文件里只有摘要哈希，没有对话原文。
     */
    private val fingerprintFile: java.io.File? = null,
    /**
     * 备份/恢复写闸。null 时不做闸检查(测试/旧装配)。
     * 模型调用可能跟着一次完整的恢复窗口才返回，所以写前还要用入口取的栅栏再判一次。
     */
    private val writeGate: io.zer0.memory.runtime.MemoryWriteGate? = null,
    /** 会话结束/记忆编译时若AI评估存在主动牵挂，通过该回调写入牵挂账本。 */
    private val onProactiveThoughtsExtracted: (
        suspend (assistantId: String, sessionId: String, thoughts: List<ParsedProactiveThought>) -> Unit
    )? = null,
) {

    companion object {
        /** LLM 最大 token(memory extract 需要输出结构化 JSON,给足空间)。 */
        const val EXTRACT_MAX_TOKENS = 4096

        /** 自动分类批量大小的上限。 */
        const val CATEGORIZE_BATCH_SIZE = 10

        /** 自动分类 LLM 最大 token。 */
        const val CATEGORIZE_MAX_TOKENS = 2048

        /** 对话历史最大条数(避免超长输入)。 */
        const val MAX_HISTORY_MESSAGES = 30

        /** 对话历史单条最大字符数(截断超长消息)。 */
        const val MAX_MESSAGE_CHARS = 500

        /** 已有事实预览最大条数(给 LLM 做去重参考)。 */
        const val MAX_EXISTING_FACTS_PREVIEW = 20

        /** 并发控制:同时只允许一个 autoSave 分析(避免 LLM 限流)。 */
        const val MAX_CONCURRENT_ANALYSIS = 1

        /** 触发 autoSave 的对话轮数间隔。 */
        const val AUTO_SAVE_TURN_INTERVAL = 10

        /** v20(M1): 事实至少这么长才允许做证据子串匹配(短事实必然误配)。 */
        const val EVIDENCE_MIN_FACT_CHARS = 6

        /** v2.6.2: 会话结束补边时视为“新事实”的最近条数。 */
        private const val EDGE_NEW_FACTS_LIMIT = 20

        /** 日志标签。 */
        private const val TAG_LOG = "MemoryAutoSaveScheduler"

        /** 主助手固定 id；拥有者 id 为空时兜底用它，不能用 scope 值冒充。 */
        private const val DEFAULT_ASSISTANT_ID = "default"

        /** 进程级写闸的占位 scope(闸本身不按 scope 细分)。 */
        private val GATE_SCOPE = io.zer0.memory.runtime.MemoryScopeKey("default", "main", "default")
    }

    // 模型常把可选字段显式写成 null。coerceInputValues 会把 null 恢复为
    // Kotlin 属性默认值，避免 mainProblem.title=null 让整份分析结果解析失败。
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }
    private val analysisSemaphore = Semaphore(MAX_CONCURRENT_ANALYSIS)

    /**
     * v12 (T2-3): 已处理内容指纹缓存（有界 LRU + 原子持久化，只存摘要哈希）。
     *
     * 幂等键 = 拥有者 + 作用域 + 空间 + sessionId，同历史变更指纹策略见 [fingerprintHistory]。
     * 同一会话同一段历史只调一次 LLM 提取，防 AutoSave 与 DeepMemory 对同一内容
     * 重复提取/双写；失败不记指纹（保留补跑机会）。
     *
     * 这不是 DeepMemory 的水位：DeepMemory 的「处理到哪」是 Room summary 的 revision/snapshot，
     * 输入是摘要 diff。两边口径不同，各自独立 —— 这里处理过最近 30 条，不代表更早的消息
     * 不需要深度提取；真重复由 FactStore 写入侧去重消除。
     */
    private val fingerprints = AutoSaveFingerprintCache(fingerprintFile)

    /** 一次 autoSave 的终态。用密封类型而不是 null 兼代表多种失败，避免“闸拦截”被当成“解析失败”。 */
    private sealed interface RunOutcome {
        /** 已解析并落库。 */
        data class Applied(val stats: AnalysisResult) : RunOutcome

        /** 模型返回不可用（空响应/content_filter/JSON 解析失败）——可预期，不记指纹。 */
        data object Empty : RunOutcome

        /** 被备份恢复写闸拦下 —— 结果基于旧库，不落库、不记指纹。 */
        data object Gated : RunOutcome
    }

    /**
     * 写前闸判定。
     *
     * 闸目前是进程级的，不看 scope 细分；这里给一个固定的粗粒度键，
     * 避免为了过接口编造一个有意义的 scope（那会让人误以为闸已按 scope 隔离）。
     */
    private fun gateBlocks(fence: io.zer0.memory.runtime.MemoryWriteFence? = null): Boolean =
        writeGate?.blocksWrite(GATE_SCOPE, fence) ?: false

    /** 主动作废指纹缓存（记忆重置等场景）。 */
    fun invalidateFingerprintCache() {
        fingerprints.invalidate()
    }

    /**
     * 作废某个会话的指纹（会话/助手删除时）。
     *
     * 调用方不必先知道有没有记录：没命中就不落盘、不报错。
     *
     * @return 被移除的条目数
     */
    fun invalidateFingerprintCacheForSession(sessionId: String): Int = fingerprints.invalidateSession(sessionId)

    /** 作废某个助手的指纹（助手删除时）。 */
    fun invalidateFingerprintCacheForAssistant(assistantId: String): Int = fingerprints.invalidateAssistant(assistantId)

    /**
     * v12 (T2-3): 计算历史消息指纹 — 逐条哈希完整历史的 (id + content)，不截断。
     * id 相同且内容相同 → 同一段历史，幂等跳过。
     *
     * 指纹覆盖的是**完整历史**；发给模型时才由 [buildHistoryText] 只取最后
     * [MAX_HISTORY_MESSAGES] 条、每条再截到 [MAX_MESSAGE_CHARS]。两者口径不同：
     * 窗口只是成本约束，不能当作幂等边界 —— 更早的消息被编辑也要让下一次 auto-save 重跑，
     * 否则旧记忆会被永久当成已处理。
     */
    internal fun fingerprintHistory(history: List<UIMessage>): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        history.forEach { message ->
            // Incremental updates avoid allocating one giant joined string for long sessions.
            digest.update(message.role.name.toByteArray())
            digest.update(0.toByte())
            digest.update(message.id.toString().toByteArray())
            digest.update(0.toByte())
            digest.update(message.content.toByteArray())
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 分析结果统计(供调用方/日志观察)。
     */
    data class AnalysisResult(
        val extracted: Int = 0,
        val updated: Int = 0,
        val merged: Int = 0,
        val links: Int = 0,
        val profileUpdated: Boolean = false,
    )

    /**
     * 调度自动保存(非阻塞,在 [scope] 中执行)。
     *
     * @param sessionId 会话 id
     * @param history 对话历史(最近 N 条)
     * @param assistantId 助手 id(决定使用哪个 FactDb,"default" 为主助手)
     * @param spaceId 记忆空间 id
     * @param scope 记忆作用域(通常与 assistantId 一致,主助手为 "main")
     * @param model 目标模型,null 由实现侧默认
     * @param locale 语言
     */
    fun scheduleAutoSave(
        sessionId: String,
        history: List<UIMessage>,
        assistantId: String = "default",
        spaceId: String = "default",
        scope: String = "main",
        model: Model? = null,
        locale: String = "zh-CN",
    ) {
        if (history.isEmpty()) return
        // v12 (T2-3): 幂等指纹 — 同一会话同一段历史已成功处理过则跳过,
        // 防止同内容重复调 LLM 提取(与 DeepMemory 双写同一内容的场景)。
        // 会话 id 可能在导入/多助手场景复用：幂等键必须包含拥有者/作用域/空间三个隔离维度。
        val key = AutoSaveFingerprintCache.Key(
            assistantId = assistantId.ifBlank { DEFAULT_ASSISTANT_ID },
            scope = scope.ifBlank { "main" },
            spaceId = spaceId.ifBlank { "default" },
            sessionId = sessionId,
        )
        val fp = fingerprintHistory(history)
        // 快路径只为了不白排一个协程;权威判定在 semaphore 内(缓存读写都在那里串行)。
        if (fingerprints.also { it.invalidateIfRestored() }.isProcessed(key, fp)) {
            Logger.d(TAG_LOG, "autoSave 幂等跳过(session=${sessionId.take(8)}…, 内容未变化)")
            return
        }
        this.scope.launch {
            analysisSemaphore.withPermit {
                runAttempt(key, fp, history, model, locale, retry = false)
            }
        }
    }

    /**
     * 一次提取尝试。调用方必须持有 [analysisSemaphore] 许可。
     *
     * 缓存读取、闸判定、业务落库后的指纹写入全在这段临界区里，
     * 两个入口同时通过快路径时也只有这里能决定谁真的跑。
     */
    private suspend fun runAttempt(
        key: AutoSaveFingerprintCache.Key,
        fingerprint: String,
        history: List<UIMessage>,
        model: Model?,
        locale: String,
        retry: Boolean,
    ) {
        val tag = if (retry) "autoSave 补跑" else "autoSave"
        val shortId = key.sessionId.take(8)
        if (fingerprints.also { it.invalidateIfRestored() }.isProcessed(key, fingerprint)) {
            Logger.d(TAG_LOG, "$tag 幂等跳过(session=$shortId…, 内容未变化)")
            return
        }
        try {
            when (val outcome = runAutoSave(key, history, model, locale)) {
                is RunOutcome.Applied -> {
                    // 只有解析并落库成功才记录指纹；LLM 空响应/JSON 解析失败必须补跑。
                    fingerprints.markProcessed(key, fingerprint)
                    val stats = outcome.stats
                    Logger.i(
                        TAG_LOG,
                        "$tag 完成(session=$shortId…): extracted=${stats.extracted}, " +
                            "updated=${stats.updated}, merged=${stats.merged}, links=${stats.links}",
                    )
                }
                RunOutcome.Empty -> Logger.w(
                    TAG_LOG,
                    "$tag 未解析出分析(可预期失败，不记指纹): session=$shortId…",
                )
                RunOutcome.Gated -> Logger.d(
                    TAG_LOG,
                    "$tag 被备份恢复写闸拦截(不落库、不记指纹): session=$shortId…",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Logger.w(TAG_LOG, "$tag 失败(session=$shortId…): ${e.message}", e)
            if (!retry && !FailureClassifier.isTerminalQuotaOrAuth(e)) {
                this@MemoryAutoSaveScheduler.scope.launch {
                    delay(30_000)
                    if (!this@MemoryAutoSaveScheduler.scope.isActive) return@launch
                    analysisSemaphore.withPermit {
                        runAttempt(key, fingerprint, history, model, locale, retry = true)
                    }
                }
            }
        } catch (e: IllegalStateException) {
            Logger.w(TAG_LOG, "$tag 失败(session=$shortId…): ${e.message}", e)
            if (!retry && !FailureClassifier.isTerminalQuotaOrAuth(e)) {
                this@MemoryAutoSaveScheduler.scope.launch {
                    delay(30_000)
                    if (!this@MemoryAutoSaveScheduler.scope.isActive) return@launch
                    analysisSemaphore.withPermit {
                        runAttempt(key, fingerprint, history, model, locale, retry = true)
                    }
                }
            }
        }
    }

    /**
     * 执行自动保存(内部 suspend 实现)。
     *
     * 步骤:
     *  1. 获取对应 assistantId 的 FactStore + MemoryLinkDao
     *  2. 构建已有事实预览(供 LLM 去重参考)
     *  3. 调 LLM 提取 ParsedAnalysis
     *  4. 过闸后 applyAnalysis 落库
     */
    private suspend fun runAutoSave(
        key: AutoSaveFingerprintCache.Key,
        history: List<UIMessage>,
        model: Model?,
        locale: String,
    ): RunOutcome = withContext(Dispatchers.IO) {
        val sessionId = key.sessionId
        val assistantId = key.assistantId
        val spaceId = key.spaceId
        val scope = key.scope
        // 入口闸:恢复窗口内不发起模型调用。
        if (gateBlocks()) return@withContext RunOutcome.Gated
        // 栅栏在模型调用前取:提取跑得慢,恢复窗口可能在等待期间完整开合。
        val fence = writeGate?.fence()

        val factStore = factDbProvider.getFactStore(assistantId)
        val linkDao = factDbProvider.getFactDb(assistantId).memoryLinkDao()

        // 1. 构建已有事实预览(取最近 MAX_EXISTING_FACTS_PREVIEW 条)
        val existingFacts = factStore.getByScopeAndSpace(scope, spaceId)
        val existingPreview = if (existingFacts.isEmpty()) {
            null
        } else {
            existingFacts.take(MAX_EXISTING_FACTS_PREVIEW).joinToString("\n") { f ->
                "- ${f.fact.take(60)}"
            }
        }

        // 2. 调 LLM 提取
        val analysis = extractEntities(history, existingPreview, model, locale)
            ?: return@withContext RunOutcome.Empty

        // 3. 模型返回后、真正落库前再过一次闸:恢复窗口内写进去的就是旧库的产物。
        if (gateBlocks(fence)) {
            Logger.d(TAG_LOG, "autoSave: 恢复窗口内跳过落库(session=${sessionId.take(8)}…)")
            return@withContext RunOutcome.Gated
        }

        // 4. 落库(证据已由 extractEntities 回填进 analysis,applyAnalysis 不需要原始消息)
        val result = applyAnalysis(analysis, factStore, linkDao, sessionId, spaceId, scope, assistantId)
        if (analysis.proactiveThoughts.isNotEmpty()) {
            runCatching {
                onProactiveThoughtsExtracted?.invoke(assistantId, sessionId, analysis.proactiveThoughts)
            }.onFailure { Logger.w("MemoryAutoSaveScheduler", "记忆总结写主动牵挂失败: ${it.message}") }
        }
        // v2.6.2（用户反馈）: 落库后补边 —— 让本次新增/更新的事实与存量连成网络。
        // 增量策略：取“最近创建的一批事实”作为新事实（补边本就是增量的，不必精确追踪）。
        // 失败不影响主流程。
        edgeBuilder?.let { builder ->
            // 补边也是写库;恢复窗口内不补(否则给旧库的边又落到新库)。
            if (gateBlocks(fence)) return@let
            runCatching {
                val all = factStore.getByScopeAndSpace(scope, spaceId)
                val recent = all.sortedByDescending { it.createdAt }.take(EDGE_NEW_FACTS_LIMIT)
                if (recent.isNotEmpty()) {
                    builder.buildEdges(
                        newFacts = recent,
                        existingFacts = all,
                        scope = scope,
                        spaceId = spaceId,
                        semanticProvider = semanticProvider,
                        // v2.6.2: LLM 深化开关（用户配置）
                        llmEnhance = edgeEnhanceEnabled?.invoke() ?: false,
                        model = model,
                    )
                }
            }.onFailure { Logger.w("MemoryAutoSaveScheduler", "会话结束补边失败(不影响记忆): ${it.message}") }
        }
        RunOutcome.Applied(result)
    }

    /**
     * AI 提取结构化实体(调用 LLM + JSON 解析)。
     *
     * @param history 对话历史
     * @param existingFactsPreview 已有事实预览(可选,去重参考)
     * @param model 目标模型
     * @param locale 语言
     * @return 解析结果(null 表示解析失败)
     */
    suspend fun extractEntities(
        history: List<UIMessage>,
        existingFactsPreview: String?,
        model: Model?,
        locale: String = "zh-CN",
    ): ParsedAnalysis? = withContext(Dispatchers.IO) {
        if (history.isEmpty()) return@withContext null

        val systemPrompt = MemoryExtractPrompt.buildSystemPrompt(locale, existingFactsPreview)
        // S-05: 输入侧可逆掩码 — 历史文本中的 PII 以占位符外发,不离开设备
        val maskedInput = PiiGuard.mask(buildHistoryText(history, locale))
        val userContent = maskedInput.masked

        val raw = try {
            llmClient.callText(
                systemPrompt = systemPrompt,
                userContent = userContent,
                model = model,
                temperature = 0.3f,
                maxTokens = EXTRACT_MAX_TOKENS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("MemoryAutoSaveScheduler", "extractEntities LLM 调用失败: ${e.message}", e)
            return@withContext null
        }

        // S-05: 还原占位符后解析(落库时 FactStore.add 再做硬脱敏)
        val parsed = parseAnalysisResult(PiiGuard.unmask(raw, maskedInput.map))
            ?: return@withContext null
        // v2.x: 示例泄漏防护 — 照抄提示词示例且对话未提及的条目直接剔除,不落库
        val sanitized = PromptExampleGuard.sanitize(parsed, userContent)
        // v20(M1): 给保留下来的条目回填"用户原话证据"(见 UserEvidenceMatcher 的从严判定)。
        // 放在 sanitize 之后:被剔除的示例条目不需要证据;放在解析之后:出处来自真实消息而非模型输出。
        sanitized?.extractedEntities?.forEach { entity ->
            UserEvidenceMatcher.find(history, entity.content, EVIDENCE_MIN_FACT_CHARS)?.let { (quote, messageId) ->
                entity.evidenceQuote = quote
                entity.evidenceMessageId = messageId
            }
        }
        sanitized
    }

    /**
     * 解析 LLM 输出为 [ParsedAnalysis]。
     *
     * 容错处理:
     *  - 去除 <think>...</think> 块
     *  - 去除 ```json ... ``` 围栏
     *  - 提取 JSON 对象(若不以 { 开头,扫描括号深度)
     *
     * internal 便于单元测试。
     */
    internal fun parseAnalysisResult(raw: String): ParsedAnalysis? {
        if (raw.isBlank()) return null

        var s = raw
        // 1. 去 <think>...</think>
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        // 2. 去 ```json ... ``` 围栏
        val fenceMatch = Regex("""```(?:json)?\s*\n([\s\S]*?)\n```""").find(s)
        if (fenceMatch != null) {
            s = fenceMatch.groupValues[1]
        } else {
            s = s.trim()
        }

        // 3. 提取 JSON 对象
        if (!s.startsWith("{")) {
            val candidate = findJsonObjectCandidate(s) ?: run {
                Logger.w("MemoryAutoSaveScheduler", "未找到 JSON 对象 (rawLen=${raw.length})")
                return null
            }
            s = candidate
        }

        // 4. 解析
        return runCatching {
            json.decodeFromString(ParsedAnalysis.serializer(), s)
        }.getOrElse {
            Logger.w("MemoryAutoSaveScheduler", "JSON 解析失败 (rawLen=${raw.length}): ${it.message}")
            null
        }
    }

    /**
     * 将分析结果落库(创建/更新/合并事实 + 建立知识图谱边)。
     *
     * @return 落库统计
     */
    suspend fun applyAnalysis(
        analysis: ParsedAnalysis,
        factStore: FactStore,
        linkDao: MemoryLinkDao,
        sessionId: String,
        spaceId: String,
        scope: String,
        assistantId: String = factStore.assistantId,
    ): AnalysisResult = withContext(Dispatchers.IO) {
        var extracted = 0
        var updated = 0
        var merged = 0
        var links = 0

        // 1. 提取新实体 → 写入 facts(自动去重合并)
        if (analysis.extractedEntities.isNotEmpty()) {
            val facts = analysis.userFactsOnly()
                .filter { it.content.isNotBlank() }
                .map { entity ->
                    FactStore.Fact(
                        fact = entity.content,
                        tags = entity.tags,
                        sessionId = sessionId,
                        importance = floatImportanceToInt(entity.importance),
                        category = entity.folderPath?.takeIf { it.isNotBlank() } ?: "general",
                        confidence = entity.credibility.coerceIn(0f, 1f),
                        source = "inferred",
                        // v12: 提取阶段给出的实体归一化键,写入时按实体键精确去重
                        entityKey = entity.entityKey,
                        // v20(M1): 出处由 extractEntities 对用户原话的匹配回填(见 ParsedEntity)
                        evidenceQuote = entity.evidenceQuote,
                        evidenceMessageId = entity.evidenceMessageId,
                    )
                }
            extracted = if (facts.isEmpty()) 0 else factStore.addBatch(facts, scope, spaceId)
        }

        // 2. 更新已有事实(按 matchTitle 模糊匹配)
        if (analysis.updatedEntities.isNotEmpty()) {
            val existing = factStore.getByScopeAndSpace(scope, spaceId)
            for (update in analysis.updatedEntities) {
                if (update.matchTitle.isBlank() || update.newContent.isBlank()) continue
                val target = findFactByTitle(existing, update.matchTitle) ?: continue
                // v2.6.2（记忆审计）: 正文/重要度/分类/标签在同一事务内原子写入，
                // 避免三步分写导致后写覆盖或半更新。
                factStore.applyFieldUpdates(
                    id = target.id,
                    scope = scope,
                    spaceId = spaceId,
                    content = update.newContent,
                    importance = update.newImportance?.let { floatImportanceToInt(it) },
                    category = if (update.newFolderPath != null || update.newTags != null) {
                        update.newFolderPath
                    } else {
                        null
                    },
                    tags = if (update.newFolderPath != null || update.newTags != null) update.newTags else null,
                )
                updated++
            }
        }

        // 3. 合并相似记忆
        if (analysis.mergedEntities.isNotEmpty()) {
            val existing = factStore.getByScopeAndSpace(scope, spaceId)
            for (merge in analysis.mergedEntities) {
                if (merge.mergedContent.isBlank() || merge.sourceTitles.isEmpty()) continue
                val sources = merge.sourceTitles.mapNotNull { title ->
                    findFactByTitle(existing, title)
                }
                if (sources.isEmpty()) continue
                // 创建合并后新事实
                val mergedFact = FactStore.Fact(
                    fact = merge.mergedContent,
                    sessionId = sessionId,
                    importance = sources.maxOf { it.importance },
                    category = sources.firstOrNull()?.category ?: "general",
                    confidence = sources.maxOf { it.confidence },
                    source = "inferred",
                )
                factStore.add(mergedFact, scope, spaceId)
                // 删除源事实(并清理关联的 links)
                sources.forEach { src ->
                    factStore.delete(src.id, assistantId, scope, spaceId)
                }
                merged++
            }
        }

        // 4. 建立知识图谱边
        if (analysis.links.isNotEmpty()) {
            val existing = factStore.getByScopeAndSpace(scope, spaceId)
            val now = Instant.now().toString()
            val linkEntities = analysis.links.mapNotNull { link ->
                if (link.sourceTitle.isBlank() || link.targetTitle.isBlank()) return@mapNotNull null
                val source = findFactByTitle(existing, link.sourceTitle) ?: return@mapNotNull null
                val target = findFactByTitle(existing, link.targetTitle) ?: return@mapNotNull null
                MemoryLinkEntity(
                    sourceFactId = source.id,
                    targetFactId = target.id,
                    sourceTitle = link.sourceTitle,
                    targetTitle = link.targetTitle,
                    linkType = link.linkType,
                    weight = link.weight.coerceIn(0f, 1f),
                    spaceId = spaceId,
                    scope = scope,
                    createdAt = now,
                )
            }
            // 审计修复 (C-07): 建链前用最新 facts 集合过滤已删除 id。
            // 链接解析基于上方 [existing] 快照；快照之后至插入前，本分析的合并步骤（步骤 3）
            // 或并发删除（UI/decay）可能已删掉 source/target，若直接插入会对已删事实留下孤儿边。
            // 因此插入前重查一次当前存活 id 集合，拒绝指向已删除事实的边。
            if (linkEntities.isNotEmpty()) {
                val liveIds = factStore.getByScopeAndSpace(scope, spaceId).map { it.id }.toSet()
                val aliveLinks = linkEntities.filter { it.sourceFactId in liveIds && it.targetFactId in liveIds }
                if (aliveLinks.isNotEmpty()) {
                    linkDao.insertBatch(aliveLinks)
                    links = aliveLinks.size
                }
            }
        }

        // 5. 用户画像更新(暂存为 category="profile" 的事实,后续可扩展独立存储)
        if (!analysis.profileMarkdown.isNullOrBlank()) {
            val profileFact = FactStore.Fact(
                fact = analysis.profileMarkdown,
                tags = listOf("user_profile"),
                sessionId = sessionId,
                importance = 1,
                category = "identity",
                confidence = 1.0f,
                source = "inferred",
            )
            factStore.add(profileFact, scope, spaceId)
        }

        AnalysisResult(
            extracted = extracted,
            updated = updated,
            merged = merged,
            links = links,
            profileUpdated = !analysis.profileMarkdown.isNullOrBlank(),
        )
    }

    /**
     * 自动分类未分类记忆(批量 AI 分类)。
     *
     * 找出 category 为 "general" 的事实,每批 [CATEGORIZE_BATCH_SIZE] 条,
     * 调 LLM 分配 folderPath,更新到数据库。
     *
     * @param spaceId 记忆空间
     * @param scope 记忆作用域
     * @param assistantId 助手 id
     * @param model 目标模型
     * @param locale 语言
     * @return 成功分类的事实数量
     */
    suspend fun autoCategorizeMemories(
        spaceId: String = "default",
        scope: String = "main",
        assistantId: String = "default",
        model: Model? = null,
        locale: String = "zh-CN",
    ): Int = withContext(Dispatchers.IO) {
        // 入口闸:分类结果也要落库,恢复窗口内不白跑一次模型调用。
        if (gateBlocks()) {
            Logger.d(TAG_LOG, "autoCategorize: 备份恢复进行中,跳过")
            return@withContext 0
        }
        val fence = writeGate?.fence()
        val factStore = factDbProvider.getFactStore(assistantId)
        val all = factStore.getByScopeAndSpace(scope, spaceId)
        val uncategorized = all.filter { it.category == "general" }.take(CATEGORIZE_BATCH_SIZE)
        if (uncategorized.isEmpty()) return@withContext 0

        val systemPrompt = MemoryExtractPrompt.buildCategorizeSystemPrompt(locale)
        val userContent = uncategorized.joinToString("\n") { f ->
            "- ${f.fact.take(80)}"
        }

        val raw = try {
            llmClient.callText(
                systemPrompt = systemPrompt,
                userContent = userContent,
                model = model,
                temperature = 0.2f,
                maxTokens = CATEGORIZE_MAX_TOKENS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("MemoryAutoSaveScheduler", "autoCategorize LLM 调用失败: ${e.message}", e)
            return@withContext 0
        }

        val categorizations = parseCategorizeResult(raw) ?: return@withContext 0
        // 模型返回后再判一次:恢复窗口内不写分类结果。
        if (gateBlocks(fence)) {
            Logger.d(TAG_LOG, "autoCategorize: 恢复窗口内跳过写入")
            return@withContext 0
        }
        var count = 0
        for (cat in categorizations) {
            val target = findFactByTitle(uncategorized, cat.title) ?: continue
            val category = cat.folderPath.takeIf { it.isNotBlank() } ?: continue
            if (factStore.updateCategoryAndTags(target.id, category, null)) count++
        }
        count
    }

    // ── 内部工具 ──────────────────────────────────────────────────────

    /** 构建对话历史文本(供 LLM 输入)。 */
    private fun buildHistoryText(history: List<UIMessage>, locale: String): String {
        val isZh = locale.startsWith("zh")
        val userLabel = if (isZh) "用户" else "User"
        val assistantLabel = if (isZh) "助手" else "Assistant"
        val titleLabel = if (isZh) "## 对话历史" else "## Conversation History"

        val recent = history.takeLast(MAX_HISTORY_MESSAGES)
        // 注意：给模型的只是最近 [MAX_HISTORY_MESSAGES] 条，但幂等指纹覆盖完整历史 ——
        // 更早的消息被编辑也会让指纹变化，允许下一次重新提取；
        // 也不能反过来用这个窗口声称「更早的消息不需要 DeepMemory 处理」。
        val sb = StringBuilder(titleLabel).append("\n\n")
        for (msg in recent) {
            val role = when (msg.role) {
                io.zer0.ai.core.MessageRole.USER -> userLabel
                io.zer0.ai.core.MessageRole.ASSISTANT -> assistantLabel
                io.zer0.ai.core.MessageRole.TOOL -> if (isZh) "工具" else "Tool"
                io.zer0.ai.core.MessageRole.SYSTEM -> if (isZh) "系统" else "System"
            }
            val content = msg.content.take(MAX_MESSAGE_CHARS)
            sb.append("**$role**: ").append(content).append("\n\n")
        }
        return sb.toString().trim()
    }

    /**
     * 按标题模糊匹配事实(用于 updatedEntities/mergedEntities/links)。
     * 优先精确匹配,其次包含匹配。
     */
    private fun findFactByTitle(facts: List<FactStore.Fact>, title: String): FactStore.Fact? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        // 1. 精确匹配(忽略大小写)
        facts.firstOrNull { it.fact.equals(trimmed, ignoreCase = true) }?.let { return it }
        // 2. 包含匹配(标题是事实的子串,或事实是标题的子串)
        facts.firstOrNull {
            it.fact.contains(trimmed, ignoreCase = true) || trimmed.contains(it.fact, ignoreCase = true)
        }?.let { return it }
        // 3. 前 20 字前缀匹配
        val prefix = trimmed.take(20)
        facts.firstOrNull { it.fact.take(20).equals(prefix, ignoreCase = true) }?.let { return it }
        return null
    }

    /** Float 重要度(0.0~1.0)→ Int 重要度(0/1/2)。 */
    private fun floatImportanceToInt(importance: Float): Int = when {
        importance >= 0.7f -> 2
        importance >= 0.4f -> 1
        else -> 0
    }

    /** 扫描字符串找到 JSON 对象({ ... })的候选。 */
    private fun findJsonObjectCandidate(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** 自动分类结果项。 */
    @kotlinx.serialization.Serializable
    private data class CategorizeItem(
        val title: String,
        val folderPath: String,
    )

    /** 解析自动分类 LLM 输出。 */
    private fun parseCategorizeResult(raw: String): List<CategorizeItem>? {
        if (raw.isBlank()) return null
        var s = raw
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        val fenceMatch = Regex("""```(?:json)?\s*\n([\s\S]*?)\n```""").find(s)
        if (fenceMatch != null) {
            s = fenceMatch.groupValues[1]
        } else {
            s = s.trim()
        }

        if (!s.startsWith("[")) {
            val start = s.indexOf('[')
            val end = s.lastIndexOf(']')
            if (start < 0 || end <= start) return null
            s = s.substring(start, end + 1)
        }

        return runCatching {
            json.decodeFromString(ListSerializer(CategorizeItem.serializer()), s)
        }.getOrElse {
            Logger.w("MemoryAutoSaveScheduler", "categorize JSON 解析失败: ${it.message}")
            null
        }
    }
}
