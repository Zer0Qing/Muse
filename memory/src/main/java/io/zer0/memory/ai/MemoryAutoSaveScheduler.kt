package io.zer0.memory.ai

import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.pii.PiiGuard
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
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val analysisSemaphore = Semaphore(MAX_CONCURRENT_ANALYSIS)

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
        this.scope.launch {
            analysisSemaphore.withPermit {
                resultOf {
                    runAutoSave(sessionId, history, assistantId, spaceId, scope, model, locale)
                }.onSuccess { result ->
                    Logger.i(
                        "MemoryAutoSaveScheduler",
                        "autoSave 完成(session=${sessionId.take(8)}…): " +
                            "extracted=${result.extracted}, updated=${result.updated}, " +
                            "merged=${result.merged}, links=${result.links}",
                    )
                }.onError { msg, t ->
                    Logger.w("MemoryAutoSaveScheduler", "autoSave 失败(session=${sessionId.take(8)}…): $msg", t)
                    // C-05: 失败后延迟补跑一次 — 调用侧( ChatViewModel)有 30s 同会话去重,
                    // 失败后立即重试会被去重挡掉,记忆静默丢失;延迟 30s 绕过窗口补跑。
                    // 注意:本方法参数 scope: String 会遮蔽同名字段,补跑协程须用
                    // this@MemoryAutoSaveScheduler.scope(CoroutineScope) 显式引用。
                    this@MemoryAutoSaveScheduler.scope.launch {
                        delay(30_000)
                        if (!this@MemoryAutoSaveScheduler.scope.isActive) return@launch
                        analysisSemaphore.withPermit {
                            resultOf {
                                runAutoSave(sessionId, history, assistantId, spaceId, scope, model, locale)
                            }.onSuccess { retryResult ->
                                Logger.i(
                                    "MemoryAutoSaveScheduler",
                                    "autoSave 补跑成功(session=${sessionId.take(8)}…): " +
                                        "extracted=${retryResult.extracted}, updated=${retryResult.updated}, " +
                                        "merged=${retryResult.merged}, links=${retryResult.links}",
                                )
                            }.onError { retryMsg, retryT ->
                                Logger.w("MemoryAutoSaveScheduler", "autoSave 补跑仍失败(session=${sessionId.take(8)}…): $retryMsg", retryT)
                            }
                        }
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
     *  4. applyAnalysis 落库
     */
    private suspend fun runAutoSave(
        sessionId: String,
        history: List<UIMessage>,
        assistantId: String,
        spaceId: String,
        scope: String,
        model: Model?,
        locale: String,
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val factStore = factDbProvider.getFactStore(assistantId)
        val linkDao = factDbProvider.getFactDb(assistantId).memoryLinkDao()

        // 1. 构建已有事实预览(取最近 MAX_EXISTING_FACTS_PREVIEW 条)
        val existingFacts = factStore.getByScopeAndSpace(scope, spaceId)
        val existingPreview = if (existingFacts.isEmpty()) null else {
            existingFacts.take(MAX_EXISTING_FACTS_PREVIEW).joinToString("\n") { f ->
                "- ${f.fact.take(60)}"
            }
        }

        // 2. 调 LLM 提取
        val analysis = extractEntities(history, existingPreview, model, locale)
            ?: return@withContext AnalysisResult()

        // 3. 落库
        applyAnalysis(analysis, factStore, linkDao, sessionId, spaceId, scope)
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
        parseAnalysisResult(PiiGuard.unmask(raw, maskedInput.map))
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
        if (fenceMatch != null) s = fenceMatch.groupValues[1]
        else s = s.trim()

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
    ): AnalysisResult = withContext(Dispatchers.IO) {
        var extracted = 0
        var updated = 0
        var merged = 0
        var links = 0

        // 1. 提取新实体 → 写入 facts(自动去重合并)
        if (analysis.extractedEntities.isNotEmpty()) {
            val facts = analysis.extractedEntities.map { entity ->
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
                )
            }
            extracted = factStore.addBatch(facts, scope, spaceId)
        }

        // 2. 更新已有事实(按 matchTitle 模糊匹配)
        if (analysis.updatedEntities.isNotEmpty()) {
            val existing = factStore.getByScopeAndSpace(scope, spaceId)
            for (update in analysis.updatedEntities) {
                val target = findFactByTitle(existing, update.matchTitle) ?: continue
                factStore.update(target.id, update.newContent)
                if (update.newImportance != null) {
                    factStore.setImportance(target.id, floatImportanceToInt(update.newImportance))
                }
                if (update.newFolderPath != null || update.newTags != null) {
                    factStore.updateCategoryAndTags(target.id, update.newFolderPath, update.newTags)
                }
                updated++
            }
        }

        // 3. 合并相似记忆
        if (analysis.mergedEntities.isNotEmpty()) {
            val existing = factStore.getByScopeAndSpace(scope, spaceId)
            for (merge in analysis.mergedEntities) {
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
                    linkDao.deleteByFactId(src.id)
                    factStore.delete(src.id)
                }
                merged++
            }
        }

        // 4. 建立知识图谱边
        if (analysis.links.isNotEmpty()) {
            val existing = factStore.getByScopeAndSpace(scope, spaceId)
            val now = Instant.now().toString()
            val linkEntities = analysis.links.mapNotNull { link ->
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
        val sb = StringBuilder(titleLabel).append("\n\n")
        for (msg in recent) {
            val role = if (msg.role == io.zer0.ai.core.MessageRole.USER) userLabel else assistantLabel
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
        if (fenceMatch != null) s = fenceMatch.groupValues[1]
        else s = s.trim()

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
