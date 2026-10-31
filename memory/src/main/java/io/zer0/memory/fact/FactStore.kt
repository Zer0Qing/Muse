package io.zer0.memory.fact

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.pii.PiiGuard
import io.zer0.memory.ticker.MemoryConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp

/**
 * 元事实存储。
 *
 * v6: 全文搜索升级为 FTS4 + 应用层 CJK 2-gram,保留 LIKE 作为单字/异常回退。
 *  - 增删改查(单条/批量/按 session/按 id)
 *  - 标签搜索(json_each 精确匹配,OR 逻辑,按匹配数降序)
 *  - 全文搜索(FTS4 MATCH,ngram 预处理;单字回退 LIKE)
 *  - FTS 索引一致性自检与全量 rebuild
 *
 * 所有写入前对 fact 字段做 PII 脱敏。
 *
 * v5: 添加事实去重(按内容前缀匹配)与智能重要度判定(关键词驱动)。
 *
 * v1.0.27 P0-1.3: addBatch 加事务,避免中途失败留下半完成状态(facts 表有数据但 facts_fts 缺失)。
 *  注入 FactDb 实例以使用 [androidx.room.withTransaction]。
 */
class FactStore(
    private val dao: FactDao,
    private val db: FactDb,
    /**
     * Pre-v15 legacy tombstone file. New deletions are stored transactionally in
     * [FactTombstoneEntity] in this assistant's database, partitioned by scope and space.
     * This optional file is read-only compatibility data because historical entries lack
     * assistant/scope/space ownership. A null file disables only legacy fallback, not Room tombstones.
     */
    private val tombstoneFile: File? = null,
    /**
     * v12: LLM 去重判定器 — 算法层(字符相似度/实体键)无法确定的模糊候选对交给大模型判断。
     * 默认 [NoopFactDedupJudge] 不调 LLM,行为与未接入完全一致;app 模块注入真实实现。
     */
    private val dedupJudge: FactDedupJudge = NoopFactDedupJudge,
    /**
     * v13 (T4-1): 事实修订记录 DAO — 关键记忆(importance≥1 或实体键合并)的变更历史。
     * null 时不记录(测试/兼容降级)。
     */
    private val revisionDao: FactRevisionDao? = null,
    /**
     * Phase 3 (可靠性 P1): 写入后的增量对账 hook(新增/批量/更新事实后触发)。
     *
     * FACTS 编译产物原先只由每日流水线(12–24h)对账一次,用户在记忆页新增/编辑事实后
     * system prompt 仍会注入旧表述。写入成功后经本 hook 立即对账,消除该延迟。
     *
     * - null(默认): 运行时从 Koin 惰性解析 [io.zer0.memory.compile.MemoryCompiler]
     *   (生产装配零改动);无 DI 环境(单测/CLI)解析不到时退化为 no-op,行为与旧版一致。
     * - 显式传入: 优先使用(单测注入 fake;DI 装配也可显式覆盖)。
     *
     * 触发在独立协程中做节流/合并(见 [scheduleReconcile]),不阻塞写入路径,
     * hook 内部再次写库会被递归防护忽略,由每日对账兜底。
     */
    private val reconcileHook: FactReconcileHook? = null,
    /** Owner of this per-assistant FactDb; guards explicit delete ownership. */
    val assistantId: String = "default",
    /** Only the default store owns clearing the legacy shared JSON file on a full reset. */
    private val clearLegacyTombstonesOnReset: Boolean = false,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** v6: 是否已经做过 FTS 索引一致性检查(避免每次搜索都重复 COUNT)。 */
    private var ftsConsistencyChecked = false

    /** 元事实数据(业务层结构,与 Entity 分离)。 */
    data class Fact(
        val id: Long = 0,
        val fact: String,
        val tags: List<String> = emptyList(),
        val time: String? = null,
        val sessionId: String? = null,
        val createdAt: String = Instant.now().toString(),
        /** v4: 重要程度(0=普通,1=重要,2=关键)。 */
        val importance: Int = 0,
        /** v5: 结构化分类(如 preference / identity / event / relationship / goal / medical)。 */
        val category: String = "general",
        /** v5: LLM 对事实可靠性的置信度,0.0 ~ 1.0。 */
        val confidence: Float = 1.0f,
        /** v5: 事实来源(user_explicit / inferred / imported)。 */
        val source: String = "inferred",
        /** v5: 事实过期时间 ISO 8601。 */
        val expiresAt: String? = null,
        /** v5: 最近一次确认时间 ISO 8601。 */
        val lastConfirmedAt: String? = null,
        /** v7: 最近一次命中时间 ISO 8601,用于 hitBonus 衰减时钟。 */
        val lastHitAt: String? = null,
        /** v14: 被检索命中的累计次数（0 = 从未被召回）。 */
        val hitCount: Int = 0,
        /**
         * v8: 记忆作用域,默认 "main" 表示主助手作用域。
         * 子助手/团队成员使用各自的 assistantId,用于隔离不同 Agent 的记忆。
         * [add] / [addBatch] 的 scope 参数会覆盖此字段。
         */
        val scope: String = "main",
        /**
         * v9: 记忆空间 id,默认 "default" 表示默认空间。
         * 用于多 Space 隔离(工作/生活/学习场景互不干扰)。
         * 与 [scope] 正交:scope 按 Agent 隔离,spaceId 按场景隔离。
         * [add] / [addBatch] 的 spaceId 参数会覆盖此字段。
         */
        val spaceId: String = "default",
        /** B4-05: 手动置顶时间 ISO 8601,null 表示未置顶。 */
        val pinnedAt: String? = null,
        /**
         * v12: 实体归一化键。同一实体的不同写法(如"张三"/"张先生")共享同一键,
         * 写入时按 entityKey 精确查重,命中即合并而非新增。
         */
        val entityKey: String? = null,
        /** v17: 持久化的衰减分数(见 [FactEntity.score])。 */
        val score: Float = 10f,
        /** v17: 出场时间 ISO 8601,null 表示仍在召回中(见 [FactEntity.suppressedAt])。 */
        val suppressedAt: String? = null,
        /**
         * v18: 本条记忆取代的旧记忆 id,null 表示不取代任何记忆(见 [FactEntity.supersedesId])。
         * 被指向的旧记忆仍然在库里、记忆页可见,只是不进编译产出的 FACTS 段。
         */
        val supersedesId: Long? = null,
        /**
         * v19: 本条记忆被合并进了哪条记忆,null 表示未被合并(见 [FactEntity.mergedIntoId])。
         * 与 [supersedesId] 是两种关系: 那条是内容取代(矛盾),本列是重复聚合;
         * 带本值的行仍在库里、记忆页可见,只是不参与自动召回与编译投影。
         */
        val mergedIntoId: Long? = null,
        val matchCount: Int? = null,
    )

    /**
     * Phase 3 (可靠性 P1): 事实写入后的增量对账入口。
     *
     * 由 [FactStore] 在新增/批量/更新成功后调用,携带本次写入的最终事实(已脱敏/已合并),
     * 由 host 侧(生产环境为 [io.zer0.memory.compile.MemoryCompiler.reconcileFactsSectionWithStore])
     * 把 FACTS 编译产物中的对应行对齐为 facts 表现值,消除 system prompt 注入最长 12–24h 延迟。
     *
     * @param facts 本次写入后的事实(最终文本;对账只读取 [Fact.fact])
     * @param scope 记忆作用域
     * @param spaceId 记忆空间
     */
    fun interface FactReconcileHook {
        suspend fun onFactsChanged(facts: List<Fact>, scope: String, spaceId: String)
    }

    /**
     * v5: 关键词 → 重要度映射。
     *  - 包含"过敏/密码/地址/生日/身份证/电话"等 → 重要度 2(关键)
     *  - 包含"喜欢/不喜欢/爱吃/最"等 → 重要度 1(重要)
     *  - 其他日常 → 重要度 0(普通)
     */
    private val criticalKeywords = listOf("过敏", "密码", "地址", "生日", "身份证", "电话", "血型", "紧急", "病历")
    private val importantKeywords = listOf("喜欢", "不喜欢", "爱吃", "最", "讨厌", "害怕", "梦想", "目标", "习惯")

    /** v1.x: 增强去重 — 否定词正则(出现次数不同 = 语义可能相反,不合并)。 */
    private val NEGATION_RE = Regex("不|没|未|别|无|非")

    /** v1.x: 增强去重 — 字符 bigram Jaccard 相似度阈值(≥ 视为重复)。
     * 实际重复多为小幅措辞差异(插入/删减/词序),Jaccard 一般在 0.5-0.8;
     * 0.5 阈值下"喜欢喝咖啡" vs "喜欢喝茶"(0.43)不会误合并。 */
    private val SIMILARITY_THRESHOLD = 0.5

    /**
     * v5: 智能判定重要度(0/1/2)。
     * 关键关键词优先匹配,其次重要关键词,默认普通。
     */
    private fun inferImportanceScore(text: String): Int {
        val lower = text.lowercase()
        if (criticalKeywords.any { lower.contains(it) }) return 2
        if (importantKeywords.any { lower.contains(it) }) return 1
        return 0
    }

    /**
     * v5: 按前缀匹配相似度判断两条事实是否相似。
     * 取较短文本,若较长文本以其开头则视为重复。
     *
     * v9: 先去常见主语前缀,避免"对青霉素过敏"和"用户对青霉素过敏"被当成两条事实。
     * v1.x: 增强 — 前缀不命中时再用字符 bigram 重叠率判定近似重复
     * (如"明天要参加英语四级考试" vs "明天上午9点考英语四级");
     * 否定词出现次数不同(如"不吃香菜" vs "吃香菜")强制不相似,避免语义反转误合并。
     */
    private fun isSimilar(a: String, b: String): Boolean {
        val na = normalizeDedupText(a)
        val nb = normalizeDedupText(b)
        if (na == nb) return true
        if (na.isBlank() || nb.isBlank()) return false
        // 否定词保护:否定出现次数不同 = 语义可能相反,绝不合并
        if (negationCount(na) != negationCount(nb)) return false
        val short = if (na.length <= nb.length) na else nb
        val long = if (na.length > nb.length) na else nb
        if (long.startsWith(short)) return true
        // 增强:字符 bigram Jaccard 重叠率(近似语义重复)
        if (na.length >= 4 && nb.length >= 4) {
            return bigramSimilarity(na, nb) >= SIMILARITY_THRESHOLD
        }
        return false
    }

    /**
     * v1.0.72: 找出近似重复的事实簇(供 LLM 合并去重)。
     *
     * 贪心分组:取全部事实,用 [isSimilar] 判定互相相似的分组到同一簇。
     * 单条事实不成簇。返回的每簇 ≥ 2 条,由调用方决定是否交给 LLM 合并。
     *
     * @param scope 记忆作用域(默认 main)
     * @param spaceId 记忆空间(默认 default,仅在该空间内聚簇)
     * @param maxGroups 最多返回簇数(限制 LLM 调用次数,默认 10)
     * @return 相似簇列表(每簇按 id 升序)
     */
    suspend fun findSimilarGroups(scope: String = "main", spaceId: String = "default", maxGroups: Int = 10): List<List<Fact>> =
        withContext(Dispatchers.IO) {
            // v12: 严格按 scope + space_id 双重过滤,避免跨空间误聚簇
            val all = dao.getByScopeAndSpace(scope, spaceId).map { it.toDomainFact() }
            if (all.size < 2) return@withContext emptyList()
            val used = mutableSetOf<Long>()
            val groups = mutableListOf<List<Fact>>()
            for (i in all.indices) {
                if (all[i].id in used) continue
                val cluster = mutableListOf(all[i])
                for (j in i + 1 until all.size) {
                    if (all[j].id in used) continue
                    // 与簇内任意一条相似即并入(传递闭包)
                    if (cluster.any { isSimilar(it.fact, all[j].fact) }) {
                        cluster.add(all[j])
                    }
                }
                if (cluster.size >= 2) {
                    used.addAll(cluster.map { it.id })
                    groups.add(cluster.sortedBy { it.id })
                    if (groups.size >= maxGroups) break
                } else {
                    used.add(all[i].id)
                }
            }
            groups
        }

    /** 否定词数量(不/没/未/别/无/非)。 */
    private fun negationCount(text: String): Int = NEGATION_RE.findAll(text).count()

    /** 字符 bigram Jaccard 相似度。 */
    private fun bigramSimilarity(a: String, b: String): Double {
        fun bigrams(s: String): Set<String> {
            if (s.length < 2) return setOf(s)
            return (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()
        }
        val ba = bigrams(a)
        val bb = bigrams(b)
        if (ba.isEmpty() || bb.isEmpty()) return 0.0
        val intersection = ba.intersect(bb).size.toDouble()
        return intersection / (ba.size + bb.size - intersection)
    }

    /**
     * v9: 为去重比较去除常见主语前缀。
     * 如"用户对青霉素过敏" → "对青霉素过敏";
     * "The user is allergic to penicillin" → "allergic to penicillin"。
     *
     * v12: 增强归一化 — 大小写折叠 + 全半角转换 + 去首尾空白 + 压缩内部空白。
     * 使"Zhang San"/"zhang san"/"张三 "跨写法可判等,支撑实体键去重第二通道。
     */
    private fun normalizeDedupText(text: String): String {
        return text.trim().lowercase()
            .replace(Regex("^(用户|我|他|她|这个用户|the user|i am|he is|she is)\\s*"), "")
            .trim()
            .normalizeFullWidth()
            .replace(WHITESPACE_RE, " ")
    }

    /**
     * v12: 全半角归一 — 全角字母/数字/常用符号转半角,兼容中英文混写重复。
     * 示例: "ａｂｃ１２３" → "abc123", "张三（同学）" → "张三(同学)"。
     */
    private fun String.normalizeFullWidth(): String {
        val sb = StringBuilder(length)
        for (ch in this) {
            val code = ch.code
            when {
                // 全角字母/数字(FF01-FF5E) → 半角(0x21-0x7E)
                code in 0xFF01..0xFF5E -> sb.append((code - 0xFEE0).toChar())
                // 全角空格
                code == 0x3000 -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * v12: 从事实文本推断实体键(提取阶段未给出时使用)。
     * 只认两类高置信信号,避免把普通词当人名造成误合并:
     *  - PiiGuard 脱敏后的占位符 {{name_N}}(姓氏+称谓已脱敏,占位符即稳定实体键)
     *  - 英文人名模式(两个大写开头的词,如 "Zhang San")
     * 中文人名由提取阶段 LLM 显式给出 entity_key(prompt 已要求);
     * 无法识别时返回 null,由文本相似度通道兜底,宁可漏记不可错记。
     */
    private fun inferEntityKey(fact: String): String? {
        val trimmed = fact.trim()
        if (trimmed.isBlank()) return null
        // 已脱敏人名标记直接作为实体键(mask 可逆占位符 [NAME_N] 等,保留实体区分度)
        val namePlaceholder = namePlaceholderRe.find(trimmed)?.value
        if (namePlaceholder != null) return namePlaceholder
        // 英文人名模式: 两个大写开头词(已脱敏场景少,主要覆盖 imported 数据)
        val enName = enNameRe.find(trimmed)?.value
        if (enName != null) return enName.lowercase()
        return null
    }

    /**
     * v12: 从事实文本中提取实体键。
     * 提取阶段已给出 entityKey 时直接使用;否则按人名规则推断;
     * 推断不出时回退为归一化文本(保证同文重复仍可判等)。
     */
    private fun resolveEntityKey(explicit: String?, fact: String): String? {
        if (!explicit.isNullOrBlank()) return explicit.trim().lowercase()
        return inferEntityKey(fact)
    }

    /** v12: 同实体判定公开入口(诊断/测试/反思任务复用)。 */
    fun debugIsSameEntity(a: String, b: String): Boolean = isSameEntityFact(a, b)

    /** v12: LLM 判定结果缓存(进程内 LRU,同步访问)— 同一事实对只问一次模型,防止重复消费 token。 */
    private val dedupVerdictCache = object : LinkedHashMap<String, DedupVerdict>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DedupVerdict>?): Boolean = size > 256
    }

    /**
     * v2.6.2（记忆审计）: 去重/合并互斥锁。
     *
     * `dedupPass` / `mergeSameEntityDuplicates` / `dedupPassAllScopes` 都是
     * “读候选 → 两两比较 → 分步写”的多步操作，并发调用（每日整理 + UI 手动整理 +
     * 写入合并）会互相干扰：同一对事实被两个协程判为重复，分别删除/覆盖，
     * 导致重复事实、FTS 残留或错误删除。本锁串行化同一 store 内的合并操作。
     */
    private val dedupMutex = kotlinx.coroutines.sync.Mutex()

    private fun dedupCacheKey(entityKey: String, cleaned: String): String = "$entityKey|$cleaned"

    /** v12: PII 占位符形态 — scrub 硬脱敏输出 [REDACTED];mask 可逆占位符 [NAME_1] 等;兼容旧 {{name_0}}。
     * IGNORE_CASE: normalizeDedupText 已 lowercase,大写占位符需不敏感匹配。 */
    private val piiPlaceholderRe =
        Regex(
            "\\[REDACTED\\]|\\[(?:API_KEY|INLINE_SECRET|PRIVATE_KEY|ID_CARD|CREDIT_CARD|SSN|EMAIL|PHONE|IPV4|ADDRESS|NAME|ENGLISH_NAME)_\\d+\\]|\\{\\{name_\\d+\\}\\}",
            RegexOption.IGNORE_CASE,
        )

    /** v12: 人名专属占位符(mask 可逆 [NAME_N] / 旧 {{name_N}}) — 仅此形态可作为实体键。 */
    private val namePlaceholderRe = Regex("\\[(?:NAME|ENGLISH_NAME)_\\d+\\]|\\{\\{name_\\d+\\}\\}", RegexOption.IGNORE_CASE)

    /** v12: 大写开头英文人名候选。 */
    private val enNameRe = Regex("\\b[A-Z][a-z]{1,12}\\s+[A-Z][a-z]{1,12}\\b")

    /**
     * v5: 合并两条相似事实,保留最高重要度、最高置信度与最新 created_at;
     *     分类优先采用新事实,来源以 user_explicit 优先。
     *
     * v9: 合并时若两条事实仅差主语,保留更短、不带主语的原始表述。
     */
    private fun mergeFact(existing: FactEntity, new: Fact): FactEntity {
        val mergedImportance = maxOf(existing.importance, if (new.importance > 0) new.importance else inferImportanceScore(new.fact))
        val mergedTags = (decodeTags(existing.tags) + new.tags).distinct()
        val existingTime = existing.time
        val mergedTime = if (new.time != null && (existingTime == null || new.time > existingTime)) new.time else existingTime
        val mergedSessionId = new.sessionId ?: existing.sessionId
        val mergedCreatedAt = Instant.now().toString()
        val mergedCategory = new.category.takeIf { it.isNotBlank() && it != "general" } ?: existing.category
        val mergedConfidence = maxOf(existing.confidence, new.confidence.coerceIn(0f, 1f))
        val mergedSource = if (existing.source == "user_explicit" || new.source == "user_explicit") "user_explicit" else new.source
        val mergedExpiresAt = new.expiresAt ?: existing.expiresAt
        val mergedLastConfirmedAt = new.lastConfirmedAt ?: existing.lastConfirmedAt
        // v7: 合并视为一次命中,重置衰减时钟
        val mergedLastHitAt = Instant.now().toString()
        // v12: 合并保留非空实体键(新事实显式携带时优先)
        val mergedEntityKey = new.entityKey ?: existing.entityKey
        return existing.copy(
            fact = pickMergedWording(existing.fact, new.fact),
            tags = json.encodeToString(ListSerializer(String.serializer()), mergedTags),
            time = mergedTime,
            sessionId = mergedSessionId,
            createdAt = mergedCreatedAt,
            importance = mergedImportance,
            category = mergedCategory,
            confidence = mergedConfidence,
            source = mergedSource,
            expiresAt = mergedExpiresAt,
            lastConfirmedAt = mergedLastConfirmedAt,
            lastHitAt = mergedLastHitAt,
            entityKey = mergedEntityKey,
        )
    }

    /**
     * v9: 合并两条相似事实的文本。
     * 若语义相同(去主语后相等),保留更短、更原始的表述;
     * 否则保留信息更完整(更长)的版本。
     *
     * v12: PII 占位符感知 — 脱敏版(如 [REDACTED]喜欢摄影)虽更长但信息密度低,
     * 优先保留无占位符的用户原文版本,避免合并后越并越"脱敏"。
     */
    private fun pickMergedWording(existing: String, new: String): String {
        val normExisting = normalizeDedupText(existing)
        val normNew = normalizeDedupText(new)
        if (normExisting == normNew) {
            // 仅差主语时保留更短的原始表述
            return if (new.length < existing.length) new else existing
        }
        val existingClean = !piiPlaceholderRe.containsMatchIn(normExisting)
        val newClean = !piiPlaceholderRe.containsMatchIn(normNew)
        return when {
            existingClean && !newClean -> existing // 旧版无占位符,保留旧版
            !existingClean && newClean -> new // 新版无占位符,保留新版
            else -> if (new.length > existing.length) new else existing // 同态,保留更长
        }
    }

    /**
     * v12: 同实体判定 — 比 [isSimilar] 更宽松,专用于同 entity_key 下的查重。
     *
     * 背景: PiiGuard 会把"张先生"脱敏成 {{name_0}},导致同实体的两条事实
     * (一条原文、一条占位符)的字符相似度被破坏,常规 [isSimilar] 判不相等。
     * 这里剥离占位符后做子串判定: 短文本是长文本子串即视为同实体同事实。
     *
     * 安全性: 只用于同 entityKey 通道(已限定同实体),不会跨实体误合并;
     * 子串含置最少 4 字,避免"喜欢"级别的过短子串误判。
     */
    internal fun isSameEntityFact(a: String, b: String): Boolean {
        if (isSimilar(a, b)) return true
        val pa = stripNamePlaceholders(a)
        val pb = stripNamePlaceholders(b)
        if (pa.length < 4 || pb.length < 4) return false
        val short = if (pa.length <= pb.length) pa else pb
        val long = if (pa.length > pb.length) pa else pb
        return long.contains(short)
    }

    /** v12: 剥离 PII 占位符并归一化(用于同实体文本对比)。 */
    private fun stripNamePlaceholders(text: String): String {
        val normalized = normalizeDedupText(text)
        return normalized.replace(piiPlaceholderRe, " ").replace(WHITESPACE_RE, " ").trim()
    }

    /**
     * v12 (T3-1): 反思任务 — 合并同实体键下的重复事实(存量整理)。
     * 对已入库的历史数据做一次全量扫描: 同 entity_key + 占位符感知判定的重复
     * 合并为一条(保留重要度/置信度更高、文本更完整的一方),置顶事实不参与。
     *
     * @return 合并掉的条数
     */
    suspend fun mergeSameEntityDuplicates(scope: String = "main", spaceId: String = "default"): Int = withContext(Dispatchers.IO) {
        val all = dao.getByScopeAndSpace(scope, spaceId).toMutableList()
        if (all.size < 2) return@withContext 0
        var merged = 0
        var i = 0
        while (i < all.size) {
            val current = all[i]
            var j = i + 1
            while (j < all.size) {
                val other = all[j]
                // 同实体键 + 占位符感知判定 + 置顶保护
                if (other.pinnedAt == null && current.pinnedAt == null &&
                    other.entityKey != null && current.entityKey != null &&
                    other.entityKey == current.entityKey &&
                    isSameEntityFact(current.fact, other.fact)
                ) {
                    val keeper = if (other.importance > current.importance) other else current
                    val mergedEntity = mergeForDedup(keeper, if (keeper.id == current.id) other else current)
                    dao.insert(mergedEntity.copy(id = keeper.id))
                    dao.deleteById(if (keeper.id == current.id) other.id else current.id)
                    dao.deleteFts(if (keeper.id == current.id) other.id else current.id)
                    syncFtsRow(keeper.id, FactFtsManager.toNgram(mergedEntity.fact))
                    all.removeAt(j)
                    if (keeper.id != current.id) all[i] = mergedEntity
                    merged++
                    continue
                }
                j++
            }
            i++
        }
        if (merged > 0) {
            Logger.i("FactStore", "反思合并: $merged 条重复事实(scope=$scope, space=$spaceId)")
        }
        merged
    }

    /**
     * v12 (T3-1): 反思任务 — 回填历史数据的实体键。
     * 早期数据 entity_key 为 null(迁移后遗留),按人名规则回填;
     * 回填后同一实体的不同写法在下一次反思时即可被 [mergeSameEntityDuplicates] 合并。
     *
     * @return 回填条数
     */
    suspend fun backfillEntityKeys(scope: String = "main", spaceId: String = "default"): Int = withContext(Dispatchers.IO) {
        val missing = dao.getByScopeAndSpace(scope, spaceId).filter { it.entityKey.isNullOrBlank() }
        if (missing.isEmpty()) return@withContext 0
        var updated = 0
        for (f in missing) {
            val key = inferEntityKey(f.fact) ?: continue
            if (dao.updateEntityKey(f.id, key) > 0) updated++
        }
        if (updated > 0) {
            Logger.i("FactStore", "反思回填实体键: $updated 条(scope=$scope, space=$spaceId)")
        }
        updated
    }

    /**
     * v12 (T3-2): 反思任务 — 矛盾检测(确定性规则版)。
     * 同实体键下,若两条事实的断言极性相反(喜欢/讨厌、会/不会、早睡/熬夜等关键词对),
     * 返回矛盾对供上层标记“待确认”(不自动删除)。
     *
     * v18: 写入时已被 supersedes 仲裁过的一对不再上报 —— 旧记忆已退出上下文,
     * 再列进“待确认”只会让用户对已处理完的冲突重新做一遍决定。
     *
     * @return 矛盾事实对列表(每对按 id 升序)
     */
    suspend fun detectContradictions(scope: String = "main", spaceId: String = "default"): List<Pair<Fact, Fact>> =
        withContext(Dispatchers.IO) {
            val byKey = dao.getByScopeAndSpace(scope, spaceId)
                .filter { !it.entityKey.isNullOrBlank() }
                .groupBy { it.entityKey!! }
            if (byKey.isEmpty()) return@withContext emptyList()
            val result = mutableListOf<Pair<Fact, Fact>>()
            for ((_, facts) in byKey) {
                for (i in facts.indices) {
                    for (j in i + 1 until facts.size) {
                        val a = facts[i].toDomainFact()
                        val b = facts[j].toDomainFact()
                        val resolved = a.supersedesId == b.id || b.supersedesId == a.id
                        if (!resolved && isContradictory(a.fact, b.fact)) {
                            result.add(if (a.id < b.id) a to b else b to a)
                        }
                    }
                }
            }
            result
        }

    /**
     * v12 (T3-2): 关键词级矛盾判定 — 同一实体下相反断言视为矛盾。
     * 高置信极性词对(喜欢↔讨厌/讨厌↔喜欢、会↔不会、是↔不是、早起↔熬夜等)。
     */
    private fun isContradictory(a: String, b: String): Boolean {
        fun polarity(text: String): Int {
            var score = 0
            if (text.contains("喜欢") || text.contains("爱吃") || text.contains("爱喝")) score += 1
            if (text.contains("讨厌") || text.contains("不喜欢") || text.contains("害怕") || text.contains("抗拒")) score -= 1
            if (text.contains("会") && !text.contains("不会")) score += 1
            if (text.contains("不会")) score -= 1
            if (text.contains("早起") || text.contains("早睡")) score += 1
            if (text.contains("熬夜") || text.contains("晚睡")) score -= 1
            return score
        }
        val pa = polarity(a)
        val pb = polarity(b)
        return pa != 0 && pb != 0 && pa * pb < 0
    }

    /**
     * v18: 写入时判定“本条新记忆取代了哪条旧记忆”。
     *
     * 只有证据充分时才返回旧记忆 id(由调用方写进 supersedes_id,该行随后退出编译上下文);
     * 拿不准一律返回 null —— 两条都留在库里、都进上下文,让模型看到冲突,
     * 比错误地剔除一条正确记忆安全。
     *
     * 同时满足全部条件才判为“取代”:
     *  - 同一个 entityKey(同实体;无键不做判定);
     *  - 新记忆是用户明确陈述(source=user_explicit) —— 推断/导入的陈述不足以推翻已有事实;
     *  - 断言极性相反([isContradictory] 的关键词级判定);
     *  - 与它矛盾的**在场**旧记忆只有一条(多条说明旧状态本身有歧义,交给人判断);
     *  - 旧记忆未手动置顶(置顶语义为“用户要求永远记得”);
     *  - 旧记忆不是关键事实(importance=2)而新记忆低于关键 —— 健康/安全问题不让普通陈述推翻。
     *
     * “在场”= 还没被别的记忆取代。已让位的旧行不再参与判定,否则用户“先喜欢、
     * 又讨厌、再喜欢”这类反复会卡死:中间那条排斥了第一条,而最后一条又只能
     * 跟第一条竞争,结果两条互斥断言一直留在上下文里。
     *
     * @return 应被本条取代的旧记忆;不满足条件时返回 null(两条都留在上下文)
     */
    private suspend fun findSupersededFact(
        newFact: String,
        newSource: String,
        newImportance: Int,
        entityKey: String?,
        scope: String,
        spaceId: String,
    ): FactEntity? {
        if (entityKey.isNullOrBlank() || newSource != "user_explicit") return null
        val superseded = dao.getSupersededIds(scope, spaceId).toSet()
        val contradictory = dao.findByEntityKey(entityKey, scope, spaceId)
            .filter { it.id !in superseded && isContradictory(newFact, it.fact) }
        val old = contradictory.singleOrNull() ?: return null
        val replaceable = old.pinnedAt == null && !(old.importance >= 2 && newImportance < 2)
        return old.takeIf { replaceable }
    }

    /**
     * v12 (T3-3): 反思任务 — 记忆晋升路径。
     * 同一实体的多条事实(重复确认 ≥ [minConfirmations] 条)说明该实体/主题被反复提及,
     * 重要性提升一级(0→1,1→2),晋升后参与衰减的门槛更高。
     *
     * @return 晋升条数
     */
    suspend fun promoteRepeatedFacts(scope: String = "main", spaceId: String = "default", minConfirmations: Int = 2): Int =
        withContext(Dispatchers.IO) {
            val byKey = dao.getByScopeAndSpace(scope, spaceId)
                .filter { !it.entityKey.isNullOrBlank() }
                .groupBy { it.entityKey!! }
            if (byKey.isEmpty()) return@withContext 0
            var promoted = 0
            for ((_, facts) in byKey) {
                if (facts.size < minConfirmations) continue
                for (f in facts) {
                    if (f.importance < 2) {
                        if (dao.updateImportance(f.id, f.importance + 1) > 0) promoted++
                    }
                }
            }
            if (promoted > 0) {
                Logger.i("FactStore", "反思晋升: $promoted 条事实提升重要度(scope=$scope, space=$spaceId)")
            }
            promoted
        }

    /** D6 第 2 期: 同实体多断言分组 —— “合并建议”的确定性候选,不代表应合并。 */
    data class SameEntityAssertions(
        /** 实体归一化键。 */
        val entityKey: String,
        /** 该实体下去重后的断言(每条一个 [Fact])。 */
        val assertions: List<Fact>,
    )

    /**
     * D6 第 2 期: 检测“同实体多断言”实体,为“合并建议”提供确定性候选。
     *
     * 设计(只检测,不改数据、不自动合并):
     *  - 仅统计 entity_key 非空且未置顶的实体(中文名无键的存量不误判,沿用“宁漏不错”);
     *  - 同实体的重复断言用 [isSameEntityFact] 去重后再计数;
     *  - 断言数 ≥ [minAssertions] 的实体视为“多断言实体”,可提示用户或供后续 LLM 版整合为实体画像。
     *
     * 定位: 与 [mergeSameEntityDuplicates](重叠断言)互补 —— 本方法处理
     * “同一实体多条不同断言”,为后续合并建议提供候选,当前不产生任何写操作。
     *
     * @return 按断言数降序的多断言分组;无匹配返回空列表
     */
    suspend fun findSameEntityMultiAssertionGroups(
        scope: String = "main",
        spaceId: String = "default",
        minAssertions: Int = DEFAULT_MIN_ASSERTIONS,
    ): List<SameEntityAssertions> = withContext(Dispatchers.IO) {
        if (minAssertions < 2) return@withContext emptyList()
        val byKey = dao.getByScopeAndSpace(scope, spaceId)
            .filter { !it.entityKey.isNullOrBlank() && it.pinnedAt == null }
            .map { it.toDomainFact() }
            .groupBy { it.entityKey!! }
        if (byKey.isEmpty()) return@withContext emptyList()
        val result = mutableListOf<SameEntityAssertions>()
        for ((key, facts) in byKey) {
            val distinct = mutableListOf<Fact>()
            for (fact in facts) {
                if (distinct.none { isSameEntityFact(it.fact, fact.fact) }) distinct.add(fact)
            }
            if (distinct.size >= minAssertions) {
                result.add(SameEntityAssertions(key, distinct))
            }
        }
        result.sortedByDescending { it.assertions.size }
    }

    /**
     * v13 (T4-1): 记录修订 — 仅对关键记忆(importance ≥ 1 或实体键非空)写入,防止表膨胀。
     * revisionDao 为 null 或写入失败时静默降级,不影响主流程。
     */
    private suspend fun recordRevisionIfKey(factId: Long, oldContent: String, newContent: String, reason: String) {
        val dao2 = revisionDao ?: return
        val current = dao.getById(factId) ?: return
        if (current.importance < 1 && current.entityKey.isNullOrBlank()) return
        if (oldContent == newContent) return
        resultOf {
            dao2.insert(
                FactRevisionEntity(
                    factId = factId,
                    oldContent = oldContent,
                    newContent = newContent,
                    changedAt = Instant.now().toString(),
                    reason = reason,
                ),
            )
        }.onError { msg, t ->
            Logger.w("FactStore", "记录修订失败(fact=$factId): $msg", t)
        }
    }

    /**
     * v13 (T4-1): 按事实 id 查询修订历史(新→旧)。
     *
     * @return 修订记录列表(可为空)
     */
    suspend fun getRevisions(factId: Long, limit: Int = 20): List<FactRevisionEntity> = withContext(Dispatchers.IO) {
        revisionDao?.getByFactId(factId, limit) ?: emptyList()
    }

    /**
     * v13 (T4-1): 回滚到指定修订 — 把事实内容恢复为某次修订的旧值。
     * 用于用户确认"合并错了/更新错了"后的恢复。
     *
     * @return 是否回滚成功
     */
    suspend fun revertToRevision(factId: Long, revisionId: Long): Boolean = withContext(Dispatchers.IO) {
        val dao2 = revisionDao ?: return@withContext false
        val revision = dao2.getByFactId(factId, limit = 50).firstOrNull { it.id == revisionId } ?: return@withContext false
        val current = dao.getById(factId) ?: return@withContext false
        // 回滚本身也是一次变更,记录当前值
        recordRevisionIfKey(factId, current.fact, revision.oldContent, "revert")
        val ok = dao.updateContent(factId, revision.oldContent, null) > 0
        if (ok) {
            syncFtsRow(factId, FactFtsManager.toNgram(revision.oldContent))
            // Phase 3 (P1): 回滚同样改变事实文本,立即对账避免注入旧表述
            scheduleReconcile(
                listOf(Fact(id = factId, fact = revision.oldContent, scope = current.scope, spaceId = current.spaceId)),
                current.scope,
                current.spaceId,
            )
        }
        ok
    }

    /**
     * v9: 查找数据库中与新事实语义相似的已有事实。
     * 先用原始前缀匹配,再用去主语后的前缀匹配,最后用子串搜索兜底,
     * 确保"对青霉素过敏"和"用户对青霉素过敏"能被识别为同一条。
     *
     * v9 改进: 新增 spaceId 过滤,仅在相同 scope + space_id 内查找相似事实,
     * 避免跨空间误合并(如"工作"空间的"喜欢美式咖啡"不应与"生活"空间的"喜欢美式咖啡"合并)。
     *
     * v12 改进: 新增 entityKey 参数,优先按实体归一化键精确查找。
     * 同一实体的不同写法(如“张三”/“张先生”/“张三老师”)共享同一 entity_key,
     * 命中即视为重复,直接解决“同一用户名 3 条重复记忆”问题。
     *
     * v19: **隐藏行不得作为写入目标** —— 已加入合并的行([FactEntity.mergedIntoId] 非空)
     * 既不进自动召回、也不进编译产出的 FACTS 段,不能当去重目标: 写进去的内容
     * 用户既看不到、也召不回来,等于数据静默丢失。
     * 已出场([FactEntity.suppressedAt] 非空)、已被取代的行写进去至少还能在记忆页看到,
     * 暂不在此列;但将来若再引入“彻底隐藏”的机制,所有匹配通道必须一起补上对应谓词。
     */
    private suspend fun findSimilarFact(
        cleaned: String,
        scope: String,
        spaceId: String = "default",
        entityKey: String? = null,
        excludeId: Long? = null,
    ): FactEntity? {
        // 0) v12: 实体键精确查重 — 同 entity_key + 同 scope/space 即视为同一实体,
        //    用占位符感知的 [isSameEntityFact] 判定文本是否实质同一。
        //    excludeId: 更新路径排除自身,避免 update 后先匹配到自己。
        if (!entityKey.isNullOrBlank()) {
            // v19: 已被合并进其他事实的行不再当去重目标 —— 它已退出上下文,
            // 把新内容并进去等于写入一条看不见的记忆。
            val sameKey = dao.findByEntityKey(entityKey, scope, spaceId).filter { it.mergedIntoId == null }
            val hit = sameKey.firstOrNull { it.id != excludeId && isSameEntityFact(it.fact, cleaned) }
            if (hit != null) return hit
            // v12: 算法层 miss — 同实体键下有候选但文本判定不相似(如语义等价但
            //    表述差异大、或 PII 脱敏后写法不同),交给 LLM 判断。只对 top 候选
            //    问一次并缓存结果,控制 token 消耗;低置信/失败宁可不合并。
            if (dedupJudge !== NoopFactDedupJudge && sameKey.isNotEmpty()) {
                val cacheKey = dedupCacheKey(entityKey, cleaned)
                val cached = synchronized(dedupVerdictCache) { dedupVerdictCache[cacheKey] }
                val verdict = cached ?: runCatching {
                    dedupJudge.judge(sameKey[0].fact, cleaned, entityKey, entityKey)
                }.getOrElse {
                    DedupVerdict.NOT_SAME
                }.also { synchronized(dedupVerdictCache) { dedupVerdictCache.put(cacheKey, it) } }
                if (verdict.highConfidenceSame) {
                    io.zer0.common.Logger.d(
                        "FactStore",
                        "LLM 判定同实体同事实: [${sameKey[0].fact.take(30)}] ≈ [${cleaned.take(30)}] (${verdict.confidence})",
                    )
                    return sameKey[0]
                }
            }
        }

        // 1) 原始前缀匹配(保持 v5 行为)+ space_id 过滤
        dao.findSimilarBySpace(cleaned.take(40), scope, spaceId)
            .firstOrNull { it.mergedIntoId == null && isSimilar(it.fact, cleaned) }?.let { return it }

        // 2) 去主语后的前缀匹配
        val normalized = normalizeDedupText(cleaned)
        if (normalized != cleaned.lowercase() && normalized.isNotBlank()) {
            dao.findSimilarBySpace(normalized.take(40), scope, spaceId)
                .firstOrNull { it.mergedIntoId == null && isSimilar(it.fact, cleaned) }?.let { return it }
        }

        // 3) 兜底:子串搜索,限制数量避免全表扫描
        // v9: 同时按 scope + space_id 过滤,避免跨作用域/跨空间误合并
        dao.likeSearchBySpace(cleaned.take(40), 20, scope, spaceId)
            .firstOrNull { it.mergedIntoId == null && isSimilar(it.fact, cleaned) }?.let { return it }

        return null
    }

    /**
     * v1.x: 全量去重 — 扫描指定作用域内相似事实并两两合并,返回被合并掉的数量。
     *
     * 处理存量重复(此前仅前缀匹配漏掉的近似重复,如"明天考四级" vs "明天上午9点考英语四级")。
     * 合并规则:
     *  - 置顶记忆(pinnedAt 非空)不参与合并(用户主动固定的)
     *  - 保留重要度/置信度更高、文本更完整的一方
     *  - 同 id REPLACE 更新 + 删除另一方
     */
    suspend fun dedupPass(scope: String = "main", maxPairs: Int = 300, spaceId: String? = null): Int = dedupMutex.withLock {
        withContext(Dispatchers.IO) {
            // v12: spaceId 非 null 时仅在该空间内去重,避免跨空间误合并;
            // null 保持旧行为(按 scope 全量)兼容既有调用方。
            val all = if (spaceId != null) {
                dao.getByScopeAndSpace(scope, spaceId).toMutableList()
            } else {
                dao.getByScope(scope).toMutableList()
            }
            if (all.size < 2) return@withContext 0
            var merged = 0
            var i = 0
            while (i < all.size && merged < maxPairs) {
                val current = all[i]
                var j = i + 1
                while (j < all.size && merged < maxPairs) {
                    val other = all[j]
                    if (current.pinnedAt == null && other.pinnedAt == null && isSimilar(current.fact, other.fact)) {
                        // 合并 other → current(保留重要度更高者为主,否则保留 current)
                        val keeper = if (other.importance > current.importance) other else current
                        val removed = if (keeper.id == current.id) other else current
                        val mergedEntity = mergeForDedup(keeper, removed)
                        dao.insert(mergedEntity.copy(id = keeper.id)) // REPLACE:覆盖 keeper
                        dao.deleteById(removed.id)
                        // v2.6.2（记忆审计）: 同步 FTS 索引 —— 旧实现只 insert+delete，
                        // 被合并行的旧索引残留（facts_after_delete 触发器只清被删 id），
                        // 导致搜索可能返回已合并掉的旧表述。与 mergeSameEntityDuplicates 对齐。
                        dao.deleteFts(removed.id)
                        syncFtsRow(keeper.id, FactFtsManager.toNgram(mergedEntity.fact))
                        all.removeAt(j)
                        if (keeper.id != current.id) all[i] = mergedEntity
                        merged++
                        continue
                    }
                    j++
                }
                i++
            }
            merged
        }
    }

    /**
     * 在当前空间内按所有记忆作用域分别去重。
     * 记忆中心默认展示全部 scope，整理入口也必须覆盖全部 scope，不能只处理 main。
     */
    suspend fun dedupPassAllScopes(spaceId: String = "default", maxPairs: Int = 300): Int = withContext(Dispatchers.IO) {
        val scopes = dao.getAll(null)
            .asSequence()
            .filter { it.spaceId == spaceId }
            .map { it.scope }
            .distinct()
            .toList()
        var merged = 0
        for (scope in scopes) {
            merged += dedupPass(scope = scope, maxPairs = maxPairs, spaceId = spaceId)
        }
        merged
    }

    /** v1.0.92: 库内实际存在的 scope+space 组合(供 LLM 整合器等遍历使用,不跨空间)。 */
    suspend fun listScopeSpacePairs(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        dao.getAll(null)
            .asSequence()
            .map { it.scope to it.spaceId }
            .distinct()
            .toList()
    }

    /** 按数据库中实际存在的 space + scope 组合执行去重，绝不跨空间比较。 */
    suspend fun dedupPassAllSpaces(maxPairs: Int = 300): Int = withContext(Dispatchers.IO) {
        val groups = dao.getAll(null)
            .asSequence()
            .map { it.scope to it.spaceId }
            .distinct()
            .toList()
        groups.sumOf { (scope, spaceId) ->
            dedupPass(scope = scope, maxPairs = maxPairs, spaceId = spaceId)
        }
    }

    /** v1.x: 合并两条相似事实(用于全量去重),保留信息更完整的文本。 */
    private suspend fun mergeForDedup(base: FactEntity, other: FactEntity): FactEntity {
        val mergedTags = (decodeTags(base.tags) + decodeTags(other.tags)).distinct()
        return base.copy(
            fact = if (base.fact.length >= other.fact.length) base.fact else other.fact,
            tags = json.encodeToString(ListSerializer(String.serializer()), mergedTags),
            time = base.time ?: other.time,
            importance = maxOf(base.importance, other.importance),
            category = if (base.category == "general") other.category else base.category,
            confidence = maxOf(base.confidence, other.confidence),
            source = if (base.source == "user_explicit" || other.source == "user_explicit") "user_explicit" else base.source,
            expiresAt = base.expiresAt ?: other.expiresAt,
            lastConfirmedAt = base.lastConfirmedAt ?: other.lastConfirmedAt,
            lastHitAt = Instant.now().toString(),
            // v12: 合并保留非空实体键
            entityKey = base.entityKey ?: other.entityKey,
        )
    }

    /**
     * v5: 新增一条元事实。自动去重 + 智能重要度。
     * 发现相似已有事实时合并(保留最高重要度和最新更新时间)。
     *
     * v8: 新增 scope 参数(默认 "main"),用于指定记忆作用域。
     *  - "main":主助手作用域(默认),用户与主助手的对话事实
     *  - assistantId:子助手/团队成员作用域
     * scope 参数会覆盖 entry.scope,调用方无需在 Fact 上单独设置。
     * 去重时仅在相同 scope 内查找相似事实,避免跨作用域误合并。
     *
     * v9: 新增 spaceId 参数(默认 "default"),用于指定记忆空间。
     *  - "default":默认空间(兼容旧调用方)
     *  - 自定义 id:用户创建的工作/生活/学习等空间
     * spaceId 与 scope 正交:一个 fact 既属于某 Agent scope,也属于某 Space。
     * 去重时仅在相同 scope + space_id 内查找,避免跨空间误合并。
     */
    suspend fun add(entry: Fact, scope: String = "main", spaceId: String = "default"): Long = withContext(Dispatchers.IO) {
        val (cleaned, detected) = PiiGuard.scrub(entry.fact)
        if (detected.isNotEmpty()) {
            io.zer0.common.Logger.d("FactStore", "PII detected in fact: $detected")
        }
        // v12: 实体键 — 提取阶段显式给出优先,否则按人名规则推断,再退化 null
        val entityKey = resolveEntityKey(entry.entityKey, cleaned)
        val newEntry = entry.copy(fact = cleaned, scope = scope, spaceId = spaceId, entityKey = entityKey)
        val existingSimilar = findSimilarFact(cleaned, scope, spaceId, entityKey)
        if (existingSimilar != null) {
            // v13 (T4-1): 合并前记录修订(被合并方原文入 revisions,可追溯)
            recordRevisionIfKey(
                factId = existingSimilar.id,
                oldContent = existingSimilar.fact,
                newContent = cleaned,
                reason = "merge:add",
            )
            val merged = mergeFact(existingSimilar, newEntry)
            // v2.6.2（记忆审计）: 合并写入包进事务 —— 旧实现 updateEntity + syncFtsRow
            // 分两步，中途失败会留下“正文已改、FTS 未同步”的不一致状态。
            db.withTransaction {
                dao.updateEntity(
                    merged.id, merged.fact, merged.tags, merged.time, merged.sessionId,
                    merged.createdAt, merged.importance, merged.category, merged.confidence,
                    merged.source, merged.expiresAt, merged.lastConfirmedAt, merged.lastHitAt,
                    merged.entityKey,
                )
                syncFtsRow(merged.id, FactFtsManager.toNgram(merged.fact))
            }
            io.zer0.common.Logger.d(
                "FactStore",
                "合并相似事实(scope=$scope, space=$spaceId, entity=$entityKey): ${existingSimilar.fact.take(
                    30,
                )}… ↔ ${cleaned.take(30)}… → id=${existingSimilar.id}",
            )
            // Phase 3 (P1): 合并后的最终文本立即对账,注入不再携带旧表述
            scheduleReconcile(
                listOf(Fact(id = merged.id, fact = merged.fact, scope = scope, spaceId = spaceId)),
                scope,
                spaceId,
            )
            return@withContext existingSimilar.id
        }
        val importance = if (newEntry.importance > 0) newEntry.importance else inferImportanceScore(cleaned)
        val now = Instant.now().toString()
        // v18: 冲突仲裁 — 只在证据充分时记下被本条取代的旧记忆(旧行不删,仅退出编译上下文)
        val superseded = findSupersededFact(cleaned, newEntry.source, importance, entityKey, scope, spaceId)
        val entity = FactEntity(
            fact = cleaned,
            tags = json.encodeToString(ListSerializer(String.serializer()), newEntry.tags),
            time = newEntry.time,
            sessionId = newEntry.sessionId,
            createdAt = now,
            importance = importance.coerceIn(0, 2),
            category = newEntry.category.takeIf { it.isNotBlank() } ?: "general",
            confidence = newEntry.confidence.coerceIn(0f, 1f),
            source = newEntry.source.takeIf { it.isNotBlank() } ?: "inferred",
            expiresAt = newEntry.expiresAt,
            lastConfirmedAt = newEntry.lastConfirmedAt,
            // v7: 新增事实视为一次命中,默认享受 hitBonus
            lastHitAt = newEntry.lastHitAt ?: now,
            // v8: 记忆作用域,由调用方指定(默认 "main")
            scope = scope,
            // v9: 记忆空间,由调用方指定(默认 "default")
            spaceId = spaceId,
            pinnedAt = newEntry.pinnedAt,
            // v12: 实体归一化键
            entityKey = entityKey,
            // v18: 被本条取代的旧记忆
            supersedesId = superseded?.id,
        )
        // 审查修复 (B-22): 单条 add 的事务化 — facts 与 facts_fts 两表写入包进同一事务,
        // 与 addBatch 对齐,避免 insertFts 失败时留下 facts 表有数据但 FTS 缺失的半完成状态。
        val insertedId = db.withTransaction {
            val id = dao.insert(entity)
            dao.insertFts(id, FactFtsManager.toNgram(cleaned))
            id
        }
        // Phase 3 (P1): 新增后立即增量对账(FACTS 产物中同义行对齐为表内定稿文本)
        scheduleReconcile(
            listOf(Fact(id = insertedId, fact = cleaned, scope = scope, spaceId = spaceId)),
            scope,
            spaceId,
        )
        insertedId
    }

    /**
     * v5: 批量新增。自动去重 + 智能重要度,原子事务保证一致性。
     *
     * v8: 新增 scope 参数(默认 "main"),批量写入时统一使用该作用域。
     * 去重时仅在相同 scope 内查找,避免跨作用域误合并。
     *
     * v9: 新增 spaceId 参数(默认 "default"),批量写入时统一使用该空间。
     * 去重时仅在相同 scope + space_id 内查找,避免跨空间误合并。
     *
     * v1.0.27 P0-1.3: 用 [FactDb.withTransaction] 包裹整个循环,确保 facts 与 facts_fts
     * 两表的写入要么全部成功要么全部回滚,避免中途失败留下半完成状态。
     */
    suspend fun addBatch(entries: List<Fact>, scope: String = "main", spaceId: String = "default"): Int = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext 0
        val now = Instant.now().toString()
        // Phase 3 (P1): 收集本次写入后的最终事实,事务提交后统一触发一次增量对账(节流合并)
        val touched = mutableListOf<Fact>()
        val inserted = db.withTransaction {
            var inserted = 0
            for (entry in entries) {
                val (cleaned, detected) = PiiGuard.scrub(entry.fact)
                if (detected.isNotEmpty()) {
                    io.zer0.common.Logger.d("FactStore", "PII detected in batch fact: $detected")
                }
                // v12: 实体键 — 提取阶段显式给出优先,否则按人名规则推断
                val entityKey = resolveEntityKey(entry.entityKey, cleaned)
                val newEntry = entry.copy(fact = cleaned, scope = scope, spaceId = spaceId, entityKey = entityKey)
                val existingSimilar = findSimilarFact(cleaned, scope, spaceId, entityKey)
                if (existingSimilar != null) {
                    val merged = mergeFact(existingSimilar, newEntry)
                    dao.updateEntity(
                        merged.id, merged.fact, merged.tags, merged.time, merged.sessionId,
                        merged.createdAt, merged.importance, merged.category, merged.confidence,
                        merged.source, merged.expiresAt, merged.lastConfirmedAt, merged.lastHitAt,
                        merged.entityKey,
                    )
                    syncFtsRow(merged.id, FactFtsManager.toNgram(merged.fact))
                    touched.add(Fact(id = merged.id, fact = merged.fact, scope = scope, spaceId = spaceId))
                } else {
                    val importance = if (newEntry.importance > 0) newEntry.importance else inferImportanceScore(cleaned)
                    val insertedId = dao.insert(
                        FactEntity(
                            fact = cleaned,
                            tags = json.encodeToString(ListSerializer(String.serializer()), newEntry.tags),
                            time = newEntry.time,
                            sessionId = newEntry.sessionId,
                            createdAt = now,
                            importance = importance.coerceIn(0, 2),
                            category = newEntry.category.takeIf { it.isNotBlank() } ?: "general",
                            confidence = newEntry.confidence.coerceIn(0f, 1f),
                            source = newEntry.source.takeIf { it.isNotBlank() } ?: "inferred",
                            expiresAt = newEntry.expiresAt,
                            lastConfirmedAt = newEntry.lastConfirmedAt,
                            lastHitAt = newEntry.lastHitAt ?: now,
                            scope = scope,
                            spaceId = spaceId,
                            pinnedAt = newEntry.pinnedAt,
                            entityKey = entityKey,
                            // v18: 同 add 的保守冲突判定
                            supersedesId = findSupersededFact(
                                cleaned, newEntry.source, importance, entityKey, scope, spaceId,
                            )?.id,
                        ),
                    )
                    dao.insertFts(insertedId, FactFtsManager.toNgram(cleaned))
                    // 新插入的 id 不会有重复 FTS,直接 insertFts 即可(upsertFts 多一次 DELETE 无必要)
                    touched.add(Fact(id = insertedId, fact = cleaned, scope = scope, spaceId = spaceId))
                }
                inserted++
            }
            inserted
        }
        scheduleReconcile(touched, scope, spaceId)
        inserted
    }

    /**
     * v6: 全文搜索。优先 FTS4 MATCH(ngram 预处理),单字/异常时回退 LIKE。
     * B-06: 结果同样过滤已过期事实(expires_at < now),与 getAll/getByScope 口径一致。
     */
    suspend fun searchFullText(query: String, limit: Int = 20): List<Fact> =
        searchFullTextWithMode(query, limit, MatchMode.EXACT)

    /**
     * 按指定匹配语义做全文搜索。
     *
     * [MatchMode.EXACT] 要求所有词命中([searchFullText] 的原有行为),
     * [MatchMode.ANY] 任一命中即算候选 —— 用于精确检索结果不足时的召回兜底。
     *
     * @param query 原始查询串,内部做 ngram/LIKE 预处理
     * @param limit 返回条数上限
     * @param mode 匹配语义
     */
    suspend fun searchFullTextWithMode(query: String, limit: Int = 20, mode: MatchMode = MatchMode.EXACT): List<Fact> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            ensureFtsIndexConsistent()
            runFtsOrLikeSearch(query.trim(), limit, mode).filterNot { it.isExpired() }.also { reviveSuppressed(it) }
        }

    /**
     * 两段式全文搜索:先精确,不够再放宽,合并去重后取前 [limit] 条。
     *
     * 两段式只影响召回,不改命中顺序里的优先级:精确命中的行始终排在前面。
     */
    suspend fun searchFullTextTwoStage(query: String, limit: Int = 20): List<Fact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureFtsIndexConsistent()
        val trimmed = query.trim()
        val exact = runFtsOrLikeSearch(trimmed, limit, MatchMode.EXACT).filterNot { it.isExpired() }
        val merged = if (exact.size >= limit) {
            exact
        } else {
            val broad = runFtsOrLikeSearch(trimmed, limit, MatchMode.ANY).filterNot { it.isExpired() }
            mergeDistinct(exact, broad, limit)
        }
        merged.also { reviveSuppressed(it) }
    }

    /** 按 scope + space 搜索，供会话内 search_memory 使用。 */
    suspend fun searchFullTextScoped(query: String, scope: String, spaceId: String, limit: Int = 20): List<Fact> =
        searchFullTextScopedWithMode(query = query, scope = scope, spaceId = spaceId, limit = limit, mode = MatchMode.EXACT)

    /** 按 scope + space + 指定匹配语义搜索。 */
    suspend fun searchFullTextScopedWithMode(
        query: String,
        scope: String,
        spaceId: String,
        limit: Int = 20,
        mode: MatchMode = MatchMode.EXACT,
    ): List<Fact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureFtsIndexConsistent()
        runFtsOrLikeSearchScoped(query.trim(), limit, scope, spaceId, mode)
            .filterNot { it.isExpired() }
            .also { reviveSuppressed(it) }
    }

    /**
     * 两段式 scoped 全文搜索:先精确,结果不足 [limit] 再按任一命中补一轮,合并去重。
     *
     * 事实通常散落在多行里,而 FTS4 的默认连接是 AND —— 一个句子里同时出现所有词才算命中。
     * 精确优先保证“整句命中”排在前面;任一命中兜底保证散落的单条事实不会被整体漏掉。
     */
    suspend fun searchFullTextScopedTwoStage(
        query: String,
        scope: String,
        spaceId: String,
        limit: Int = 20,
    ): List<Fact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureFtsIndexConsistent()
        val trimmed = query.trim()
        val exact = runFtsOrLikeSearchScoped(trimmed, limit, scope, spaceId, MatchMode.EXACT).filterNot { it.isExpired() }
        val merged = if (exact.size >= limit) {
            exact
        } else {
            val broad = runFtsOrLikeSearchScoped(trimmed, limit, scope, spaceId, MatchMode.ANY).filterNot { it.isExpired() }
            mergeDistinct(exact, broad, limit)
        }
        merged.also { reviveSuppressed(it) }
    }

    /** 合并两段结果:保留第一段顺序,第二段只补第一段没有的 fact,总体不超过 [limit]。 */
    private fun mergeDistinct(primary: List<Fact>, secondary: List<Fact>, limit: Int): List<Fact> {
        val seen = HashSet<Long>(primary.size + secondary.size)
        return (primary + secondary).filter { seen.add(it.id) }.take(limit)
    }

    /**
     * 审查修复 (B-19): 按 space_id 过滤、scope 不限(全部作用域)的全文搜索。
     * 记忆中心 scope=null 语义(全部)下仍按当前 [spaceId] 隔离,避免跨空间搜索泄漏。
     */
    suspend fun searchFullTextBySpace(query: String, spaceId: String, limit: Int = 20): List<Fact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureFtsIndexConsistent()
        runFtsOrLikeSearchBySpaceId(query.trim(), limit, spaceId).filterNot { it.isExpired() }.also { reviveSuppressed(it) }
    }

    /**
     * v12 (T2-2): 运行时相关记忆检索 — 按当前问题召回 top-K 相关事实。
     * 在 [searchFullText] 基础上增加 scope + space 过滤,防止跨助手/跨空间串记忆;
     * 用于 system prompt 的 <relevant_memory> 段(按 query 召回,而非全量注入)。
     *
     * v17: 已出场(分数低于阈值)的记忆不参与召回;取数窗口按出场情况逐步放大,
     * 保证出场几条不会让召回条数跟着变少。
     */
    suspend fun searchRelevantFacts(query: String, scope: String = "main", spaceId: String = "default", limit: Int = 8): List<Fact> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            ensureFtsIndexConsistent()
            // 必须在 SQL/FTS 查询阶段就按 scope + space 过滤。
            // 先全库取 limit*3 再在内存过滤会被其他助手/空间的高相关结果占满，
            // 造成当前作用域明明有事实却返回空或不完整结果。
            val hits = recallEligibleFacts(query.trim(), scope, spaceId, limit)
            // 命中回写:让 last_hit_at 反映真实使用情况(衰减加成与“最近命中”都依赖它)
            recordHits(hits)
            hits
        }

    /**
     * v17: 自动召回候选 — 排除已出场/已过期事实后凑够 [limit] 条。
     *
     * FTS 先按窗口取再在内存过滤,所以出场记忆会占掉窗口名额。
     * 若窗口已被结果占满而合格项不足 [limit],成倍放大窗口重取(最多到 8 倍),
     * 确保“出场了几条”不会变成“召回数量变少”。
     *
     * v18: 已被取代的旧记忆也不进自动召回 —— 否则两条互斥断言会一起出现在
     * <relevant_memory> 里,与 FACTS 段的仲裁结果自相矛盾。
     */
    private suspend fun recallEligibleFacts(query: String, scope: String, spaceId: String, limit: Int): List<Fact> {
        if (limit <= 0) return emptyList()
        val superseded = supersededIds(scope, spaceId)
        val merged = mergedIds(scope, spaceId)
        var window = limit * 2
        val maxWindow = limit * 8
        while (true) {
            val raw = runFtsOrLikeSearchScoped(query, window, scope, spaceId)
                .filterNot { it.isExpired() }
            val eligible = raw.filter { it.isInRecall() && it.id !in superseded && it.id !in merged }
            // raw 行数不到窗口 = 库里已经没有更多候选,再放大也没用
            val truncated = raw.size >= window
            if (eligible.size >= limit || !truncated || window >= maxWindow) {
                return eligible.take(limit)
            }
            window *= 2
        }
    }

    /**
     * 检索命中回写 —— 刷新 last_hit_at 与持久分数。
     *
     * 只回写时间/计数/分数,不改变任何内容;失败只记日志,绝不影响检索结果本身。
     */
    private suspend fun recordHits(hits: List<Fact>) {
        val ids = hits.mapNotNull { fact -> fact.id.takeIf { it > 0L } }
        if (ids.isEmpty()) return
        runCatching { dao.updateLastHitAt(ids, Instant.now().toString()) }
            .onFailure { Logger.w("FactStore", "命中回写失败(不影响检索): ${it.message}") }
        // 命中次数同样验证“这条记忆还在被用到”，与时间一起回写
        runCatching { dao.incrementHitCount(ids) }
            .onFailure { Logger.w("FactStore", "命中计数失败(不影响检索): ${it.message}") }
        // v17: 命中同时给持久分数加成,被引用的记忆不易出场
        runCatching {
            val config = MemoryConfig()
            dao.boostScores(ids, config.hitBonus, revivalCeiling(config), Instant.now().toString())
        }.onFailure { Logger.w("FactStore", "命中分数回拨失败(不影响检索): ${it.message}") }
    }

    /**
     * v17: 出场记忆的「访问回拨」。
     *
     * 显式检索(记忆页 / search_memory 工具)命中一条已出场的记忆,说明它仍被需要:
     * 补一次命中加成、清除出场标记,下一次自动召回就能重新命中它。
     * 只处理已出场的行,正常记忆不受影响;失败只记日志,不影响检索结果。
     */
    private suspend fun reviveSuppressed(hits: List<Fact>) {
        val ids = hits.filter { it.suppressedAt != null }.map { it.id }.filter { it > 0L }
        if (ids.isEmpty()) return
        runCatching {
            val config = MemoryConfig()
            dao.boostScores(ids, config.hitBonus, revivalCeiling(config), Instant.now().toString())
        }.onFailure { Logger.w("FactStore", "出场记忆回拨失败(不影响检索): ${it.message}") }
    }

    private suspend fun runFtsOrLikeSearchScoped(
        trimmed: String,
        limit: Int,
        scope: String,
        spaceId: String,
        mode: MatchMode = MatchMode.EXACT,
    ): List<Fact> {
        val like: suspend () -> List<Fact> = { likeScopedByWords(trimmed, limit, scope, spaceId) }
        if (FactFtsManager.shouldFallbackToLike(trimmed)) return like()
        val matchQuery = FactFtsManager.toMatchQuery(trimmed, orSemantics = mode == MatchMode.ANY)
        if (matchQuery.isBlank()) return like()
        val ftsResults = resultOf { dao.searchFtsBySpace(matchQuery, limit, scope, spaceId) }
            .onError { msg, t -> Logger.w("FactStore", "scoped FTS search failed, fallback to LIKE: $msg", t) }
            .getOrNull() ?: emptyList()
        if (ftsResults.isEmpty()) return like()
        return ftsResults.map { it.toDomainFact() }
    }

    /**
     * scoped LIKE 回退:整串不命中时拆词逐查,任一命中算候选,命中词多的排在前面。
     *
     * 整串命中保持原有顺序(置顶/重要度/时间),拆词结果才按命中词数重排 ——
     * 这样单词查询与拆分前的行为完全一致。
     */
    private suspend fun likeScopedByWords(trimmed: String, limit: Int, scope: String, spaceId: String): List<Fact> {
        val whole = dao.likeSearchBySpace(trimmed, limit, scope, spaceId)
        val rows = if (whole.isNotEmpty()) {
            whole
        } else {
            likeByWords(trimmed, limit) { term -> dao.likeSearchBySpace(term, limit, scope, spaceId) }
        }
        return rows.map { it.toDomainFact() }
    }

    /**
     * 按词逐个 LIKE 后合并:同一行只留一条,按命中词数降序(同数保持首次出现顺序)。
     *
     * @param query 逐词执行的查询体,由调用方决定作用域/空间过滤
     */
    private suspend fun likeByWords(
        trimmed: String,
        limit: Int,
        query: suspend (String) -> List<FactEntity>,
    ): List<FactEntity> {
        val terms = FactFtsManager.toQueryTerms(trimmed)
        if (terms.size < 2) return emptyList()
        val matched = LinkedHashMap<Long, Pair<FactEntity, Int>>()
        for (term in terms) {
            query(term).forEach { row ->
                val previous = matched[row.id]
                matched[row.id] = if (previous == null) row to 1 else previous.first to previous.second + 1
            }
        }
        return matched.values.sortedByDescending { it.second }.map { it.first }.take(limit)
    }

    /** 审查修复 (B-19): 仅按 space_id 过滤的 FTS/LIKE 全文搜索(FTS 失败回退 LIKE 同空间查询)。 */
    private suspend fun runFtsOrLikeSearchBySpaceId(
        trimmed: String,
        limit: Int,
        spaceId: String,
        mode: MatchMode = MatchMode.EXACT,
    ): List<Fact> {
        val like: suspend () -> List<Fact> = {
            val whole = dao.likeSearchBySpaceId(trimmed, limit, spaceId)
            val rows = if (whole.isNotEmpty()) {
                whole
            } else {
                likeByWords(trimmed, limit) { term -> dao.likeSearchBySpaceId(term, limit, spaceId) }
            }
            rows.map { it.toDomainFact() }
        }
        if (FactFtsManager.shouldFallbackToLike(trimmed)) return like()
        val matchQuery = FactFtsManager.toMatchQuery(trimmed, orSemantics = mode == MatchMode.ANY)
        if (matchQuery.isBlank()) return like()
        val ftsResults = resultOf { dao.searchFtsBySpaceId(matchQuery, limit, spaceId) }
            .onError { msg, t -> Logger.w("FactStore", "space-only FTS search failed, fallback to LIKE: $msg", t) }
            .getOrNull() ?: emptyList()
        if (ftsResults.isEmpty()) return like()
        return ftsResults.map { it.toDomainFact() }
    }

    /** 先尝试 FTS4 MATCH，单字或异常时回退 LIKE。 */
    private suspend fun runFtsOrLikeSearch(
        trimmed: String,
        limit: Int,
        mode: MatchMode = MatchMode.EXACT,
    ): List<Fact> {
        val like: suspend () -> List<Fact> = {
            val whole = dao.likeSearch(trimmed, limit)
            val rows = if (whole.isNotEmpty()) whole else likeByWords(trimmed, limit) { term -> dao.likeSearch(term, limit) }
            rows.map { it.toDomainFact() }
        }
        if (FactFtsManager.shouldFallbackToLike(trimmed)) return like()
        val matchQuery = FactFtsManager.toMatchQuery(trimmed, orSemantics = mode == MatchMode.ANY)
        if (matchQuery.isBlank()) return like()
        val ftsResults = resultOf { dao.searchFts(matchQuery, limit) }
            .onError { msg, t -> io.zer0.common.Logger.w("FactStore", "FTS search failed, fallback to LIKE: $msg", t) }
            .getOrNull() ?: emptyList()
        if (ftsResults.isEmpty()) return like()
        return ftsResults.map { it.toDomainFact() }
    }

    /**
     * 按标签搜索(精确匹配,OR 逻辑,按匹配数降序)。
     * 使用 json_each 精确匹配标签值,避免 LIKE 子串误匹配。
     * B-06: 结果过滤已过期事实。
     */
    suspend fun searchByTags(queryTags: List<String>, dateRange: DateRange? = null, limit: Int = 20): List<Fact> =
        searchByTagsInternal(queryTags, dateRange, limit, null, null)

    /** 按 scope + space 搜索标签，供会话内 search_memory 使用。 */
    suspend fun searchByTagsScoped(
        queryTags: List<String>,
        scope: String,
        spaceId: String,
        dateRange: DateRange? = null,
        limit: Int = 20,
    ): List<Fact> = searchByTagsInternal(queryTags, dateRange, limit, scope, spaceId)

    private suspend fun searchByTagsInternal(
        queryTags: List<String>,
        dateRange: DateRange?,
        limit: Int,
        scope: String?,
        spaceId: String?,
    ): List<Fact> = withContext(Dispatchers.IO) {
        if (queryTags.isEmpty()) return@withContext emptyList()
        val plan = TagSearchPlan(queryTags, dateRange, limit, scope, spaceId)
        val rows = dao.tagSearch(SimpleSQLiteQuery(plan.sql, plan.args))
        plan.refine(rows)
            .map { fact ->
                if (scope != null && spaceId != null) {
                    // TagSearchRow 兼容旧 Room 投影时可能没有回填新增列；
                    // SQL 已经按边界过滤，这里把宿主边界明确写回领域对象。
                    fact.copy(scope = scope, spaceId = spaceId)
                } else {
                    fact
                }
            }
            .filterNot { it.isExpired() }
            .also { reviveSuppressed(it) }
    }

    /**
     * 获取所有元事实(按时间降序)。
     *
     * v8: 新增可选 scope 参数,null 表示全部作用域,非 null 仅返回指定作用域的事实。
     * v17: [includeSuppressed] 默认 true(列表/去重/对账/UI 需要看到全部行);
     * 注入上下文的召回调用传 false,排除已出场(分数低于阈值)的记忆。
     * v18: 传 false 时同时排除已被取代的旧记忆(行仍在库里,只是不进上下文)。
     */
    suspend fun getAll(scope: String? = null, includeSuppressed: Boolean = true): List<Fact> = withContext(Dispatchers.IO) {
        val rows = dao.getAll(scope)
            .map { it.toDomainFact() }
            .filterNot { it.isExpired() }
        if (includeSuppressed) rows else rows.filterRecallable(scope, null)
    }

    /** B-18: 事实是否已过期(expires_at 存在且早于当前时间)。 */
    private fun Fact.isExpired(): Boolean {
        val expiresAt = expiresAt ?: return false
        val t = runCatching { java.time.Instant.parse(expiresAt) }.getOrNull() ?: return false
        return t.isBefore(java.time.Instant.now())
    }

    /** 按 session_id 查询。B-06: 过滤已过期事实。 */
    suspend fun getBySession(sessionId: String): List<Fact> = withContext(Dispatchers.IO) {
        dao.getBySession(sessionId).map { it.toDomainFact() }.filterNot { it.isExpired() }
    }

    /** 按 id 查询。B-06: 已过期事实返回 null(与列表口径一致)。 */
    suspend fun getById(id: Long): Fact? = withContext(Dispatchers.IO) {
        dao.getById(id)?.toDomainFact()?.takeIf { !it.isExpired() }
    }

    /** 总数。 */
    suspend fun size(): Int = withContext(Dispatchers.IO) { dao.count() }

    /** Compatibility path: derive scope/space from the row inside the transaction; assistant identity comes from this store. */
    suspend fun delete(id: Long): Boolean = deleteInternal(id, expectedAssistantId = assistantId)

    /** Delete only when all caller-provided ownership dimensions match the selected store and row. */
    suspend fun delete(id: Long, assistantId: String, scope: String, spaceId: String): Boolean =
        deleteInternal(id, assistantId, scope, spaceId)

    private suspend fun deleteInternal(
        id: Long,
        expectedAssistantId: String,
        expectedScope: String? = null,
        expectedSpaceId: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (normalizeAssistantId(expectedAssistantId) != normalizeAssistantId(assistantId)) return@withContext false
        val deleted = db.withTransaction {
            val target = dao.getById(id) ?: return@withTransaction null
            if (expectedScope != null && target.scope != expectedScope) return@withTransaction null
            if (expectedSpaceId != null && target.spaceId != expectedSpaceId) return@withTransaction null
            val normalized = normalizeTombstone(target.fact)
            // Keep deletion state atomic: if the durable marker cannot be written, the source row and
            // its derived indexes remain untouched when Room rolls this transaction back.
            if (normalized.isNotBlank()) {
                db.factTombstoneDao().insertIfAbsent(
                    FactTombstoneEntity(
                        normalizedFact = normalized,
                        scope = target.scope,
                        spaceId = target.spaceId,
                        deletedAt = Instant.now().toString(),
                    ),
                )
                db.factTombstoneDao().trimScopeToLimit(target.scope, target.spaceId, TOMBSTONE_MAX_ENTRIES)
            }
            dao.deleteFts(id)
            val removed = dao.deleteById(id) > 0
            if (!removed) error("Fact disappeared during transactional delete (id=$id)")
            // memory_links deliberately has no FK cascade; remove both source/target edges atomically.
            db.memoryLinkDao().deleteByFactId(id)
            // v2.6.2（记忆审计）: 同步删除修订历史 —— 否则用户删了敏感记忆后，
            // 旧正文仍留在 fact_revisions 且可通过修订接口读出，形成隐私残留。
            revisionDao?.deleteByFactId(id)
            target.toDomainFact()
        }
        if (deleted != null) scheduleReconcile(listOf(deleted), deleted.scope, deleted.spaceId)
        deleted != null
    }

    /**
     * 会话被删除时清掉该会话在本库产生的全部事实。
     *
     * 删除语义与 [delete] 一致:每条事实先写持久化墓碑,再清 FTS 索引、图谱边与修订记录,
     * 整批在同一个 Room 事务里完成。墓碑是关键 —— 编译侧靠它判定“这条不允许再补录”,
     * 少了它下一轮 [io.zer0.memory.compile.MemoryCompiler] 会把摘要里的同一句话重新补录回表。
     *
     * 幂等：会话已无事实(或已清过)时返回 0,不会报错也不会重复写墓碑。
     *
     * @return 实际删除的行数
     */
    suspend fun deleteBySession(sessionId: String): Int {
        val id = sessionId.trim()
        if (id.isEmpty()) return 0
        val rows = withContext(Dispatchers.IO) { dao.getBySession(id) }
        return deleteRows(rows) { dao.deleteBySession(id) }
    }

    /**
     * 助手被删除时清掉该作用域在本库的全部事实。
     *
     * 分库文件由删除助手的一方负责删,本方法只管库内的行 —— 主库 `facts.db` 里
     * 仍可能残留同 scope 的历史行,那是“删了助手但记忆页还看得见”的另一条来源。
     *
     * @return 实际删除的行数
     */
    suspend fun deleteAllByScope(scope: String): Int {
        val key = scope.trim()
        if (key.isEmpty()) return 0
        val rows = withContext(Dispatchers.IO) { dao.getByScope(key) }
        return deleteRows(rows) { dao.deleteAllByScope(key) }
    }

    /**
     * 批量硬删一组事实:墓碑 + FTS + 图谱边 + 修订记录,整批一个事务。
     *
     * [removeRows] 负责真正的 DELETE(facts_fts 有级联触发器,不另写删除语句);
     * [rows] 只用来写墓碑与清派生数据,所以先读后删、行集会随事务一起生效。
     */
    private suspend fun deleteRows(rows: List<FactEntity>, removeRows: suspend () -> Int): Int = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext 0
        val removed = db.withTransaction {
            val now = Instant.now().toString()
            val touchedScopes = mutableSetOf<Pair<String, String>>()
            rows.forEach { row ->
                val normalized = normalizeTombstone(row.fact)
                if (normalized.isNotBlank()) {
                    db.factTombstoneDao().insertIfAbsent(
                        FactTombstoneEntity(
                            normalizedFact = normalized,
                            scope = row.scope,
                            spaceId = row.spaceId,
                            deletedAt = now,
                        ),
                    )
                    touchedScopes += row.scope to row.spaceId
                }
                // memory_links / fact_revisions 没有外键级联,按 id 逐条清,否则留下指向空行的孤儿。
                db.memoryLinkDao().deleteByFactId(row.id)
                revisionDao?.deleteByFactId(row.id)
            }
            touchedScopes.forEach { (scope, spaceId) ->
                db.factTombstoneDao().trimScopeToLimit(scope, spaceId, TOMBSTONE_MAX_ENTRIES)
            }
            removeRows()
        }
        // 提交后再调度对账:编译产物里的 FACTS 段是按本库事实投影的,删完得让它重算一次。
        rows.groupBy { it.scope to it.spaceId }.forEach { (key, group) ->
            scheduleReconcile(
                group.map { Fact(id = it.id, fact = it.fact, scope = key.first, spaceId = key.second) },
                key.first,
                key.second,
            )
        }
        removed
    }

    private fun normalizeAssistantId(id: String): String = id.trim().ifBlank { "default" }

    /**
     * v2.6.2（记忆审计）: 原子合并一组相似事实 —— 把 keeper 正文改为 [mergedContent]
     * 并删除 [removableIds] 其余成员，全部在**同一个 Room 事务**内完成。
     *
     * 背景: 修复 LLM 整合的不可逆丢数据 —— 旧流程先逐条删除、再 update keeper，
     * 两步不在同一事务；若部分删除成功而 keeper 更新失败，原始事实已经丢失，
     * “回写”只是新增一条，不会恢复被删事实的原字段/置顶/标签/链接/修订。
     *
     * 本方法保证：任一步失败时整个事务回滚，keeper 与成员保持原状。
     * 不走 update() 的内部自动去重（避免与簇内删除叠加成“双删清空”）。
     *
     * @return 实际删除的成员数；keeper 不存在或归属不符时返回 -1（未做任何改动）
     */
    suspend fun mergeIntoKeeper(keeperId: Long, mergedContent: String, removableIds: List<Long>, scope: String, spaceId: String): Int =
        withContext(Dispatchers.IO) {
            val trimmed = mergedContent.trim()
            if (trimmed.isEmpty()) return@withContext -1
            val scrubbed = PiiGuard.scrub(trimmed).cleaned
            var removedCount = -1
            var reconciled: Fact? = null
            db.withTransaction {
                val keeper = dao.getById(keeperId) ?: return@withTransaction
                if (keeper.scope != scope || keeper.spaceId != spaceId) return@withTransaction
                // 修订记录（事务内；失败仅记日志，不回滚合并语义）
                recordRevisionIfKey(
                    factId = keeperId,
                    oldContent = keeper.fact,
                    newContent = scrubbed,
                    reason = "llm_consolidate",
                )
                if (dao.updateContent(keeperId, scrubbed, null) <= 0) return@withTransaction
                syncFtsRow(keeperId, FactFtsManager.toNgram(scrubbed))
                var removed = 0
                for (id in removableIds) {
                    if (id == keeperId) continue
                    if (removeMemberIfOwned(id, scope, spaceId)) removed++
                }
                removedCount = removed
                reconciled = Fact(id = keeperId, fact = scrubbed, scope = scope, spaceId = spaceId)
            }
            reconciled?.let { scheduleReconcile(listOf(it), it.scope, it.spaceId) }
            removedCount
        }

    /**
     * v2.6.2: 合并时删除一个成员事实（含 tombstone/FTS/links/修订清理）。
     * 调用方必须已持有事务；成员不存在或归属不符时返回 false（不动）。
     */
    private suspend fun removeMemberIfOwned(id: Long, scope: String, spaceId: String): Boolean {
        val target = dao.getById(id)
        if (target == null || target.scope != scope || target.spaceId != spaceId) return false
        val normalized = normalizeTombstone(target.fact)
        if (normalized.isNotBlank()) {
            db.factTombstoneDao().insertIfAbsent(
                FactTombstoneEntity(
                    normalizedFact = normalized,
                    scope = target.scope,
                    spaceId = target.spaceId,
                    deletedAt = Instant.now().toString(),
                ),
            )
        }
        dao.deleteFts(id)
        dao.deleteById(id)
        db.memoryLinkDao().deleteByFactId(id)
        revisionDao?.deleteByFactId(id)
        return true
    }

    /**
     * v19: 多条合并成一条 —— 新增一条聚合事实,把源行标记为“已合并进它”。
     *
     * 与 [mergeIntoKeeper] 的区别: 那条是“留一条、删其余”,源行的独有信息只能靠调用方写进 keeper;
     * 本条不删任何行,而是另插一条 [mergedText],源行只被 [FactEntity.mergedIntoId] 指向新行,
     * 所以可以整批撤销([unmergeFacts]) —— 合并后用户还能回头。
     *
     * 事务: 新行插入 + FTS 索引 + 源行标记全在同一个 Room 事务内。任一步失败整批回滚,
     * 不会留下“新行已建、源行还参与召回”的半完成状态 —— 那会让重复内容双份进上下文。
     *
     * 元数据继承规则:
     *  - importance / confidence 取源最高;category 取第一个非 general 的源(与 [mergeForDedup] 一致)
     *  - tags 合并去重(按源 id 升序的首次出现顺序)
     *  - time / sessionId / expiresAt / lastConfirmedAt / entityKey 取第一个非空值
     *  - source: 源里有 user_explicit 则为 user_explicit,否则沿用主源
     *  - pinnedAt: 源里有置顶则新行也置顶(取最早那个置顶时间) —— 用户主动固定的不因合并丢失
     *  - hitCount: 取源之和,合并行承接这些行被召回的次数
     *  - score 取源最高分 —— 合并只是去重、不是新记忆,不把旧记忆的年龄清零;
     *    score_updated_at 记为本次合并时间,suppressed_at 清空(合并本身算一次人工访问)
     *  - supersedes_id: 列只有一列,源里有多条不同的取代指向时只能留一个(取数字最小的),
     *    尽量不让已被取代的旧记忆因为源行退出上下文而重新回到上下文
     *
     * 源行筛选: 只接受存在、未被合并过、且与主源同 scope + 同 space 的行。
     * 已经带 merged_into_id 的行不再参与合并 —— 否则会形成链式指向,
     * 撤销时就无法分辨“这张行的合并是谁做的”。
     *
     * 同一条原则的另一面: 被合并的行也**不能作为写入去重目标**(新内容会被并进一条看不见的行),
     * 约束在 [findSimilarFact] 的匹配谓词里。
     *
     * @param sourceIds 参与合并的事实 id(重复值与不存在的 id 忽略)
     * @param mergedText 合并后的正文(去重后已整合各源独有信息)
     * @param scope 归属校验:非 null 时只接受该作用域的源
     * @param spaceId 归属校验:非 null 时只接受该空间的源
     * @return 新事实 id;无可用源(空列表 / 全部不存在或已被合并 / 正文为空)时返回 null
     */
    suspend fun mergeFacts(
        sourceIds: List<Long>,
        mergedText: String,
        scope: String? = null,
        spaceId: String? = null,
    ): Long? = withContext(Dispatchers.IO) {
        val trimmed = mergedText.trim()
        if (trimmed.isEmpty()) return@withContext null
        val ids = sourceIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return@withContext null
        val scrubbed = PiiGuard.scrub(trimmed).cleaned
        var mergedFact: Fact? = null
        var newId: Long? = null
        db.withTransaction {
            val candidates = ids.mapNotNull { dao.getById(it) }
                .filter { candidate ->
                    candidate.mergedIntoId == null &&
                        (scope == null || candidate.scope == scope) &&
                        (spaceId == null || candidate.spaceId == spaceId)
                }
            val owner = candidates.firstOrNull() ?: return@withTransaction
            // 同一批选择必须落在同一个 scope + space: 跨边界的行不并入,避免源行指向另一个作用域的新行
            val sources = candidates
                .filter { it.scope == owner.scope && it.spaceId == owner.spaceId }
                .sortedBy { it.id }
            if (sources.isEmpty()) return@withTransaction
            val now = Instant.now().toString()
            val entity = FactEntity(
                fact = scrubbed,
                tags = json.encodeToString(ListSerializer(String.serializer()), inheritedTags(sources)),
                time = sources.firstNotNullOfOrNull { it.time },
                sessionId = sources.firstNotNullOfOrNull { it.sessionId },
                createdAt = now,
                importance = sources.maxOf { it.importance }.coerceIn(0, 2),
                category = sources.firstOrNull { it.category.isNotBlank() && it.category != "general" }?.category
                    ?: "general",
                confidence = sources.maxOf { it.confidence }.coerceIn(0f, 1f),
                source = if (sources.any { it.source == "user_explicit" }) "user_explicit" else owner.source,
                expiresAt = sources.firstNotNullOfOrNull { it.expiresAt },
                lastConfirmedAt = sources.firstNotNullOfOrNull { it.lastConfirmedAt },
                lastHitAt = now,
                hitCount = sources.sumOf { it.hitCount },
                scope = owner.scope,
                spaceId = owner.spaceId,
                pinnedAt = sources.mapNotNull { it.pinnedAt }.minOrNull(),
                entityKey = sources.firstNotNullOfOrNull { it.entityKey },
                score = sources.maxOf { it.score },
                scoreUpdatedAt = now,
                suppressedAt = null,
                supersedesId = sources.mapNotNull { it.supersedesId }.minOrNull(),
            )
            val id = dao.insert(entity)
            syncFtsRow(id, FactFtsManager.toNgram(scrubbed))
            dao.updateMergedInto(sources.map { it.id }, id)
            newId = id
            mergedFact = entity.copy(id = id).toDomainFact()
        }
        // 写后增量对账: 投影取“在场且未被合并”的行,下一次注入就是合并后的文本(源行自动退场)
        mergedFact?.let { scheduleReconcile(listOf(it), it.scope, it.spaceId) }
        newId
    }

    /**
     * v19: 撤销合并 —— 删除合并产物,把指向它的源行的 merged_into_id 清空。
     *
     * 同一个 Room 事务: 先清指向、再删行。顺序不能反 —— 先删行会留下一批指向已不存在 id 的
     * 孤儿标记,那些源行既回不到召回、也不再属于任何合并。
     *
     * 删行时写删除墓碑(与 [deleteInternal] 一致): 否则下一轮 FACTS 对账会把编译产物里
     * 还留着的那行合并文本当成“产物孤儿”重新补录回 facts 表 —— 刚撤销的合并又复活了。
     *
     * @return 是否删掉了合并产物(id 不存在时返回 false,不做任何改动)
     */
    suspend fun unmergeFacts(mergedFactId: Long): Boolean = withContext(Dispatchers.IO) {
        val removed = db.withTransaction {
            val target = dao.getById(mergedFactId) ?: return@withTransaction null
            dao.clearMergedInto(mergedFactId)
            val normalized = normalizeTombstone(target.fact)
            if (normalized.isNotBlank()) {
                db.factTombstoneDao().insertIfAbsent(
                    FactTombstoneEntity(
                        normalizedFact = normalized,
                        scope = target.scope,
                        spaceId = target.spaceId,
                        deletedAt = Instant.now().toString(),
                    ),
                )
            }
            dao.deleteFts(mergedFactId)
            if (dao.deleteById(mergedFactId) <= 0) error("Merged fact disappeared during transactional unmerge (id=$mergedFactId)")
            db.memoryLinkDao().deleteByFactId(mergedFactId)
            revisionDao?.deleteByFactId(mergedFactId)
            target.toDomainFact()
        }
        removed?.let { scheduleReconcile(listOf(it), it.scope, it.spaceId) }
        removed != null
    }

    /**
     * v19: 某条合并事实聚合了哪些原始行(供 UI 展示“由 N 条合并”与撤销前预览)。
     *
     * 不按 expires_at 过滤: 合并关系是历史事实,已过期的源行仍属于这次合并。
     */
    suspend fun getMergedChildren(factId: Long): List<Fact> = withContext(Dispatchers.IO) {
        dao.getMergedChildren(factId).map { it.toDomainFact() }
    }

    /** v19: 合并时继承的标签 —— 各源标签按 id 升序首次出现顺序去重。 */
    private fun inheritedTags(sources: List<FactEntity>): List<String> {
        val seen = LinkedHashSet<String>()
        for (source in sources) {
            decodeTags(source.tags).forEach { tag -> if (tag.isNotBlank()) seen.add(tag) }
        }
        return seen.toList()
    }

    /**
     * P2: 更新单条 Fact 内容(用于记忆页 UI 编辑 Fact 层)。
     *
     * 与 [add] 不同,这里不走 PII 脱敏(用户手动编辑的内容,保持原样),
     * 仅做轻量去空白处理。返回是否更新成功(目标 id 不存在时返回 false)。
     *
     * v8: 新增可选 scope 参数:
     *  - scope 为 null(默认):只更新 content,保留原有作用域
     *  - scope 非 null:同时更新 content 与 scope(用于 UI 切换事实作用域)
     */
    suspend fun update(id: Long, content: String, scope: String? = null): Boolean = withContext(Dispatchers.IO) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return@withContext false
        // B-17: 更新同样走 PII 脱敏 — 调用方含 LLM 输出路径(MemoryAutoSaveScheduler
        // update 合并去重结果),旧注释"用户手动编辑不走脱敏"的前提不成立;
        // 与 add 保持一致,敏感信息一律不落明文。
        val scrubbed = PiiGuard.scrub(trimmed).cleaned
        val target = dao.getById(id)
        val updated = dao.updateContent(id, scrubbed, scope) > 0
        // Phase 3 (P1): 更新后的最终事实(合并场景为合并结果),用于写后增量对账
        var reconciled: Fact? = null
        if (updated) {
            // v1.0.51: 用 upsertFts 避免更新已有事实时产生重复 FTS 条目
            syncFtsRow(id, FactFtsManager.toNgram(scrubbed))
            // v12: 更新后接入去重检查 — 新内容与同 scope/space 下其他事实重复时,
            //    自动合并(删除被覆盖方,保留更完整表述),修复"更新路径绕过去重"漏洞。
            val current = target ?: dao.getById(id)
            if (current != null) {
                // v13 (T4-1): update 记录修订(重要性 ≥1 的关键记忆)
                recordRevisionIfKey(
                    factId = id,
                    oldContent = current.fact,
                    newContent = scrubbed,
                    reason = "update",
                )
                val mergedEntity = current.copy(fact = scrubbed)
                // v12: excludeId=自身 — 更新后 findSimilarFact 不应匹配到自己
                val dup = findSimilarFact(scrubbed, mergedEntity.scope, mergedEntity.spaceId, mergedEntity.entityKey, excludeId = id)
                if (dup != null && dup.id != id) {
                    val merged = mergeFact(
                        dup,
                        Fact(
                            fact = scrubbed,
                            scope = dup.scope,
                            spaceId = dup.spaceId,
                            entityKey = dup.entityKey,
                        ),
                    )
                    dao.updateEntity(
                        merged.id, merged.fact, merged.tags, merged.time, merged.sessionId,
                        merged.createdAt, merged.importance, merged.category, merged.confidence,
                        merged.source, merged.expiresAt, merged.lastConfirmedAt, merged.lastHitAt,
                        merged.entityKey,
                    )
                    syncFtsRow(merged.id, FactFtsManager.toNgram(merged.fact))
                    dao.deleteFts(id)
                    dao.deleteById(id)
                    io.zer0.common.Logger.d("FactStore", "update 触发去重合并: $id → ${merged.id}")
                    reconciled = Fact(id = merged.id, fact = merged.fact, scope = merged.scope, spaceId = merged.spaceId)
                } else {
                    reconciled = Fact(
                        id = id,
                        fact = scrubbed,
                        scope = mergedEntity.scope,
                        spaceId = mergedEntity.spaceId,
                    )
                }
            }
        }
        reconciled?.let { scheduleReconcile(listOf(it), it.scope, it.spaceId) }
        updated
    }

    /**
     * v2.6.2（记忆审计）: 原子更新一条事实的正文 + 重要度 + 分类/标签。
     *
     * 旧实现（MemoryAutoSaveScheduler）分三次调 update/setImportance/updateCategoryAndTags，
     * 并发自动保存或同一分析中两条更新指向同一事实时后写覆盖前写，且三类字段不在同一事务，
     * 会出现半更新。本方法在同一 Room 事务内完成全部字段与 FTS 同步。
     *
     * @return 是否更新成功（目标不存在或归属不符返回 false）
     */
    // LongParameterList 豁免：字段更新本就多字段对应，拆对象反而降低调用可读性。
    @Suppress("LongParameterList")
    suspend fun applyFieldUpdates(
        id: Long,
        scope: String,
        spaceId: String,
        content: String?,
        importance: Int?,
        category: String?,
        tags: List<String>?,
    ): Boolean = withContext(Dispatchers.IO) {
        val scrubbedContent = content?.trim()?.takeIf { it.isNotEmpty() }?.let { PiiGuard.scrub(it).cleaned }
        val scrubbedTags = tags?.map { PiiGuard.scrub(it).cleaned }
        val tagsJson = scrubbedTags?.let { json.encodeToString(ListSerializer(String.serializer()), it) }
        db.withTransaction {
            val target = dao.getById(id) ?: return@withTransaction false
            if (target.scope != scope || target.spaceId != spaceId) return@withTransaction false
            if (scrubbedContent != null) {
                recordRevisionIfKey(factId = id, oldContent = target.fact, newContent = scrubbedContent, reason = "update")
                if (dao.updateContent(id, scrubbedContent, null) <= 0) return@withTransaction false
                syncFtsRow(id, FactFtsManager.toNgram(scrubbedContent))
            }
            if (importance != null) dao.updateImportance(id, importance.coerceIn(0, 2))
            if (category != null || tagsJson != null) dao.updateCategoryAndTags(id, category, tagsJson)
            true
        }
    }

    /**
     * v4: 更新单条 Fact 的重要程度(用于记忆页 UI 手动调整)。
     * @param importance 0=普通,1=重要,2=关键
     * @return 是否更新成功
     */
    suspend fun setImportance(id: Long, importance: Int): Boolean = withContext(Dispatchers.IO) {
        dao.updateImportance(id, importance.coerceIn(0, 2)) > 0
    }

    /** B4-05: 设置/取消手动置顶。 */
    suspend fun setPinned(id: Long, pinned: Boolean): Boolean = withContext(Dispatchers.IO) {
        dao.updatePinnedAt(id, if (pinned) Instant.now().toString() else null) > 0
    }

    /**
     * v10 P2-3: 更新指定 fact 的分类和标签(用于 AI 记忆管理)。
     *
     * @param category 分类(null 保留原值)
     * @param tags 标签列表(null 保留原值,非 null 则替换)
     * @return 是否更新成功
     */
    suspend fun updateCategoryAndTags(id: Long, category: String? = null, tags: List<String>? = null): Boolean =
        withContext(Dispatchers.IO) {
            // B-17: tags 逐项 PII 脱敏 — 调用方含 LLM 路径(autoCategorize/AI 记忆管理),
            // prompt 鼓励用人名做标签,不脱敏会把姓名明文落库。
            val scrubbedTags = tags?.map { PiiGuard.scrub(it).cleaned }
            val tagsJson = scrubbedTags?.let { json.encodeToString(ListSerializer(String.serializer()), it) }
            dao.updateCategoryAndTags(id, category, tagsJson) > 0
        }

    /** 清空所有。 */
    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.clearFts()
            dao.deleteAll()
            db.factTombstoneDao().deleteAll()
            // v2.6.2（记忆审计）: 同时清修订历史，避免重置后旧正文仍可读出。
            revisionDao?.deleteAll()
        }
        if (clearLegacyTombstonesOnReset) {
            synchronized(tombstoneLock) { tombstoneFile?.let { runCatching { it.delete() } } }
        }
    }

    /**
     * P0-10: 重置记忆级联清理 — 事实 + FTS + 墓碑 + 知识图谱边(memory_links)一次清空。
     *
     * 此前"重置记忆"只删 facts 行,指向已删事实的图谱边残留成孤儿(memory_links 建表时
     * 刻意不加外键级联),且子助手分库完全未清理。本方法供数据管理页"重置记忆"与
     * 删除助手级联共用。
     */
    suspend fun clearAllWithLinks(): Unit = withContext(Dispatchers.IO) {
        clearAll()
        db.memoryLinkDao().deleteAll()
    }

    // ─── Phase 3 (可靠性 P1): 写入后增量对账(节流/合并/防递归) ───

    /**
     * 写后对账的合并窗口(ms)。窗口内的多次写入(addBatch/连续 add/合并)合并为一次
     * hook 调用,避免连续写入放大对账开销。internal 便于单测缩短等待时间。
     */
    internal var reconcileDebounceMs: Long = DEFAULT_RECONCILE_DEBOUNCE_MS

    /** 对账调度锁:保护 [reconcilePending] 与 [reconcileJob](写入可能来自多协程)。 */
    private val reconcileLock = Any()

    /** 待对账事实,按 (scope, spaceId) 分组;窗口结束时逐组调用 hook。 */
    private val reconcilePending = LinkedHashMap<Pair<String, String>, MutableList<Fact>>()

    /** 当前节流窗口的调度任务;窗口内重复写入不重复调度。 */
    private var reconcileJob: Job? = null

    /** hook 执行期间置位 — 防止 hook 内部再写 facts 造成递归触发。 */
    private val reconcileInFlight = AtomicBoolean(false)

    /** 对账协程 scope(只承载轻量节流任务,不阻塞写入路径)。 */
    private val reconcileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 默认 hook 的解析结果缓存(显式注入优先,见 [resolveReconcileHook])。 */
    @Volatile
    private var resolvedDefaultHook: FactReconcileHook? = null

    /**
     * Phase 3 (P1): 调度一次写后增量对账。
     *
     * - 节流/合并: [reconcileDebounceMs] 窗口内的多次写入合并为一次 hook 调用;
     * - 防递归: hook 执行期间的写入不再触发(由每日流水线对账兜底);
     * - 无 hook(单测/无 DI)时零调度开销,行为与旧版完全一致。
     */
    private fun scheduleReconcile(facts: List<Fact>, scope: String, spaceId: String) {
        if (facts.isEmpty()) return
        if (reconcileInFlight.get()) return
        if (resolveReconcileHook() == null) return
        synchronized(reconcileLock) {
            val bucket = reconcilePending.getOrPut(scope to spaceId) { mutableListOf() }
            facts.forEach { fact ->
                if (bucket.none { it.id == fact.id && it.fact == fact.fact }) bucket.add(fact)
            }
            if (reconcileJob?.isActive != true) {
                reconcileJob = reconcileScope.launch {
                    delay(reconcileDebounceMs)
                    flushReconcile()
                }
            }
        }
    }

    /** Phase 3 (P1): 取出待对账批次并执行 hook;失败仅告警(每日编译对账兜底)。 */
    private suspend fun flushReconcile() {
        val batches = synchronized(reconcileLock) {
            if (reconcilePending.isEmpty()) {
                reconcileJob = null
                return
            }
            val copy = reconcilePending.entries.map { (key, facts) ->
                Triple(key.first, key.second, facts.toList())
            }
            reconcilePending.clear()
            reconcileJob = null
            copy
        }
        val hook = resolveReconcileHook() ?: return
        reconcileInFlight.set(true)
        try {
            for ((scope, spaceId, facts) in batches) {
                resultOf { hook.onFactsChanged(facts, scope, spaceId) }
                    .onError { msg, t ->
                        Logger.w("FactStore", "写后对账失败(下次编译兜底): ${t?.message ?: msg}")
                    }
            }
        } finally {
            reconcileInFlight.set(false)
        }
    }

    /** Phase 3 (P1): 显式注入优先;否则惰性解析默认 hook 并缓存(解析失败则下次重试)。 */
    private fun resolveReconcileHook(): FactReconcileHook? {
        reconcileHook?.let { return it }
        resolvedDefaultHook?.let { return it }
        return resolveDefaultReconcileHook()?.also { resolvedDefaultHook = it }
    }

    // ─── S-04: 删除墓碑(防已删事实从摘要复活) ───

    /**
     * Legacy pre-v15 JSON tombstone read lock. New deletion markers are transactional Room rows
     * and are isolated by this FactStore's assistant database, scope, and space.
     */
    private val tombstoneLock = TOMBSTONE_LOCK

    /** 所有本库墓碑；主要供兼容调用，编译/注入应使用带 scope 和 space 的重载。 */
    suspend fun getTombstones(): List<String> = withContext(Dispatchers.IO) {
        (db.factTombstoneDao().getAllNormalizedFacts() + loadLegacyTombstones()).distinct()
    }

    /** 只返回当前 assistant 数据库、scope 和 space 的删除标记。 */
    suspend fun getTombstones(scope: String, spaceId: String): List<String> = withContext(Dispatchers.IO) {
        val currentFacts = dao.getByScopeAndSpace(scope, spaceId)
            .map { it.toDomainFact() }
            .filterNot { it.isExpired() }
            .map { comparableTombstone(it.fact) }
            .toSet()
        val scoped = db.factTombstoneDao().getNormalizedFacts(scope, spaceId)
            .filterNot { comparableTombstone(it) in currentFacts }
        // Pre-v15 installs have an unscoped shared JSON list. Keep those legacy markers fail-closed,
        // except where an exact active fact in this assistant/scope/space proves it was re-added.
        val legacy = loadLegacyTombstones()
            .filterNot { comparableTombstone(it) in currentFacts }
        (scoped + legacy).distinct()
    }

    private fun loadLegacyTombstones(): List<String> {
        val file = tombstoneFile ?: return emptyList()
        return synchronized(tombstoneLock) {
            if (!file.exists()) return@synchronized emptyList()
            runCatching {
                json.decodeFromString(ListSerializer(String.serializer()), file.readText())
            }.getOrElse {
                Logger.w("FactStore", "readTombstones failed: ${it.message}")
                emptyList()
            }
        }
    }

    /** 规范化: 去首尾空白 + 压缩连续空白,保证跨措辞微差仍可匹配。 */
    private fun normalizeTombstone(text: String): String = text.trim().replace(WHITESPACE_RE, " ")

    /** Exact comparison used to detect a legitimate re-add without applying substring deletion semantics. */
    private fun comparableTombstone(text: String): String = normalizeTombstone(text).replace(PUNCT_RE, "")

    companion object {
        /** D6 第 2 期: “多断言实体”默认阈值 —— 同实体 ≥3 条不同断言才值得提示整合。 */
        const val DEFAULT_MIN_ASSERTIONS = 3

        /**
         * 全文检索的匹配语义。
         *
         * [EXACT] 要求查询里的词全部命中(原有行为);[ANY] 任一命中即算候选。
         * 两者只差 MATCH 表达式的连接符与 LIKE 兜底的排序,不改变作用域/空间过滤。
         */
        enum class MatchMode {
            /** 全部词命中。 */
            EXACT,

            /** 任一命中。 */
            ANY,
        }

        private val WHITESPACE_RE = Regex("\\s+")

        /** B-26: 进程级墓碑锁(所有 FactStore 实例共享同一把锁)。 */
        val TOMBSTONE_LOCK = Any()

        /** B-10: 墓碑条目上限(超出裁剪最旧,文件体积与过滤开销有界)。 */
        const val TOMBSTONE_MAX_ENTRIES = 1000

        /** Phase 3 (P1): 写后对账合并窗口默认值(ms)。 */
        const val DEFAULT_RECONCILE_DEBOUNCE_MS = 1500L

        /**
         * Phase 3 (P1): 默认写后对账 hook — 运行时从 Koin 惰性解析
         * [io.zer0.memory.compile.MemoryCompiler],把 FACTS 编译产物与 facts 表对齐。
         *
         * 不在 FactStore 构造期解析:MemoryCompiler 反向依赖 FactStore,构造期查找会构成
         * Koin 循环依赖;首次写入触发时才查找。无 DI(单测/CLI)或未注册时返回 null(禁用),
         * 行为与旧版一致(仅靠每日编译对账)。
         */
        private fun resolveDefaultReconcileHook(): FactReconcileHook? {
            val koin = org.koin.core.context.GlobalContext.getOrNull() ?: return null
            val compiler = runCatching {
                koin.get<io.zer0.memory.compile.MemoryCompiler>()
            }.getOrNull() ?: return null
            return FactReconcileHook { facts, scope, spaceId ->
                compiler.reconcileFactsSectionWithStore(
                    facts,
                    io.zer0.memory.compile.MemoryCompileTarget(scope = scope, spaceId = spaceId),
                )
            }
        }

        /** B-07: 标点/空白剥离(第二匹配通道)。 */
        private val PUNCT_RE = Regex("[，。！？、；：,.!?;:()（）\\[\\]【】\"'“”‘’\\s]+")

        /**
         * 审查修复 (2.0 B-07): 共享墓碑过滤 — 逐行规范化匹配。
         *
         * 编译对象是另一 LLM 通道改写后的摘要,措辞几乎必然与墓碑原文不同,
         * 纯 contains 匹配常失效。双通道匹配:
         *  - 通道 1: 空白压缩后的子串匹配(墓碑记录时已压缩);
         *  - 通道 2: 剥离标点/空白后的子串匹配(捕获 LLM 改写时新增的标点/换行)。
         * 完整语义归一(近义改写)需要 LLM 判定,超出本方法范围。
         * 定义为 companion 成员,供 MemoryCompiler/DeepMemoryProcessor 以类名调用。
         */
        fun filterTombstonedLines(text: String, tombstones: List<String>): String {
            if (tombstones.isEmpty()) return text
            return text.lines()
                .filterNot { line ->
                    val normalized = line.trim()
                    normalized.isNotEmpty() && tombstones.any { matchesTombstone(normalized, it) }
                }
                .joinToString("\n")
        }

        /** B-07: 单条文本是否命中某墓碑(双通道匹配,见 [filterTombstonedLines])。 */
        fun matchesTombstone(text: String, tombstone: String): Boolean {
            val normalized = text.trim().replace(WHITESPACE_RE, " ")
            val tomb = tombstone.trim().replace(WHITESPACE_RE, " ")
            if (normalized.contains(tomb)) return true
            val stripped = normalized.replace(PUNCT_RE, "")
            val strippedTomb = tomb.replace(PUNCT_RE, "")
            return strippedTomb.isNotBlank() && stripped.contains(strippedTomb)
        }
    }

    /**
     * v6: 检查 facts_fts 索引一致性,不一致则全量 rebuild。
     *
     * 触发场景:
     * - v5→v6 迁移后(facts_fts 空表,需首次全量索引)
     * - 外部修改 facts 表(备份导入后)
     * - 索引损坏(FTS 查询异常后下次搜索自愈)
     *
     * 调用一次后设置 [ftsConsistencyChecked],避免重复 COUNT。
     */
    suspend fun ensureFtsIndexConsistent() = withContext(Dispatchers.IO) {
        if (ftsConsistencyChecked) return@withContext
        ftsConsistencyChecked = true
        val factCount = resultOf { dao.countFacts() }.getOrNull() ?: -1
        val ftsCount = resultOf { dao.countFts() }.getOrNull() ?: -1
        if (factCount < 0 || ftsCount < 0) {
            io.zer0.common.Logger.w("FactStore", "FTS count check failed: facts=$factCount fts=$ftsCount")
            return@withContext
        }
        if (factCount == ftsCount) {
            io.zer0.common.Logger.d("FactStore", "FTS index consistent: $ftsCount rows")
            return@withContext
        }
        io.zer0.common.Logger.i("FactStore", "FTS index inconsistent: facts=$factCount fts=$ftsCount, rebuilding...")
        rebuildFtsIndex()
    }

    /**
     * v6: 全量重建 facts_fts 索引。清空后遍历 facts 重新插入(ngram 转换)。
     *
     * 调用方应在 IO 线程。
     */
    suspend fun rebuildFtsIndex() = withContext(Dispatchers.IO) {
        dao.clearFts()
        val rows = dao.getAllForFtsRebuild()
        var ok = 0
        rows.forEach { row ->
            resultOf {
                dao.insertFts(row.id, FactFtsManager.toNgram(row.fact))
                ok++
            }.onError { msg, t ->
                io.zer0.common.Logger.w("FactStore", "FTS rebuild insert failed for ${row.id}: $msg", t)
            }
        }
        io.zer0.common.Logger.i("FactStore", "FTS rebuild done: $ok/${rows.size} facts indexed")
    }

    /**
     * 按 [MemoryConfig] 配置执行一轮 fact 衰减。
     *
     * v4 改进:关键事实(importance=2)永不衰减,避免"青霉素过敏"等高风险信息被遗忘。
     * v7 改进:引入命中加成(hitBonus)。
     * v8 改进:新增可选 scope 参数,null 表示全部作用域(兼容旧调用方)。
     *
     * v17（衰减可回拨）:从"到期硬删除"改为"分数出场"。
     *  - 分数落库到 `score` 列,不再每次按年龄现算,阈值判定有稳定依据;
     *  - 结算时钟:上次结算时间(score_updated_at) → 首次结算回退到 created_at;
     *    自上次结算后又被命中/合并(last_hit_at 更新)的行补一次 hitBonus 并重置时钟;
     *  - 结算后分数低于 [MemoryConfig.compileThreshold] 的行写入 suppressed_at(出场):
     *    不再参与自动召回与上下文注入,但整行保留,可被后续引用/显式检索拉回;
     *  - 分数回昇(或改为关键/置顶)的行清除 suppressed_at 重新参与召回。
     * 只有 `expires_at` 到期的时效性事实仍然硬删除 —— 那是写入时的显式 TTL,不由衰减阈值决定。
     * 真正的清理(单条删除/清空记忆)走 [delete] / [clearAll] 等显式入口。
     *
     * 该方法在 daily pipeline(deepMemory step 之后)由
     * [io.zer0.memory.deep.DeepMemoryProcessor] 调用,每个 assistant 的 FactStore 各跑一次。
     *
     * @return 本轮新出场(即不再参与召回)的 fact 数
     */
    suspend fun applyDecay(config: MemoryConfig, scope: String? = null, spaceId: String? = null): Int =
        withContext(Dispatchers.IO) {
            val now = Instant.now()
            val nowIso = now.toString()
            // B-18: 先清理已到期事实(expires_at < now) — 时效性事实由显式 TTL 决定,不属于衰减
            dao.deleteExpired(nowIso, scope, spaceId)
            // v2.6.2（记忆审计）: 批量删除后统一清孤儿边/修订。
            db.memoryLinkDao().deleteOrphans()
            revisionDao?.deleteOrphans()

            val candidates = dao.getDecayCandidates(scope, spaceId)
            var outOfRecall = 0
            db.withTransaction {
                for (candidate in candidates) {
                    // 时间戳无法解析时本轮不结算该行:宁可不衰减,不能误判成低分出场
                    val elapsed = elapsedDays(candidate.refAt, now) ?: continue
                    var next = decayedScore(candidate.score, elapsed, config)
                    if (candidate.hitSinceDecay != 0) {
                        next = minOf(next + config.hitBonus, revivalCeiling(config))
                    }
                    dao.updateDecayedScore(candidate.id, next, nowIso)
                }
                outOfRecall = dao.suppressBelowThreshold(nowIso, config.compileThreshold, scope, spaceId)
                dao.releaseAboveThreshold(config.compileThreshold, scope, spaceId)
            }
            if (outOfRecall > 0) {
                Logger.d("FactStore", "applyDecay: $outOfRecall 条事实分数低于阈值,已退出召回(行保留)")
            }
            outOfRecall
        }

    /** 一天的毫秒数(衰减结算用)。 */
    private val millisPerDay = 86_400_000.0

    /**
     * [refAt] 到 [now] 的间隔天数(负数按 0 算)。
     * 时间戳无法解析时返回 null —— 调用方应跳过该行,而不是当作"无限旧"直接出场。
     */
    private fun elapsedDays(refAt: String?, now: Instant): Double? {
        if (refAt.isNullOrBlank()) return null
        val ref = runCatching { Instant.parse(refAt) }.getOrNull() ?: return null
        val millis = java.time.Duration.between(ref, now).toMillis()
        return if (millis <= 0L) 0.0 else millis / millisPerDay
    }

    /**
     * 指数衰减:`score × exp(-λ·天数)`,λ = [MemoryConfig.decayPerDay] × [MemoryConfig.forgetSpeed]。
     * 与 [MemoryConfig.factScore] 同一模型:指数无记忆性,增量结算与按年龄一次结算等价。
     */
    private fun decayedScore(score: Float, elapsedDays: Double, config: MemoryConfig): Float {
        val lambda = (config.decayPerDay * config.forgetSpeed).toDouble()
        if (lambda <= 0.0) return score
        val next = score.toDouble() * exp(-lambda * elapsedDays)
        return if (next.isNaN() || next < 0.0) 0f else next.toFloat()
    }

    /** 分数上限:命中后不超过"全新记忆 + 一次命中加成",防止反复命中把分数堆到无意义高位。 */
    private fun revivalCeiling(config: MemoryConfig): Float = config.baseImportance + config.hitBonus

    /** v17: 这条记忆是否仍在召回范围内(未出场;关键/置顶恒在)。 */
    private fun Fact.isInRecall(): Boolean = suppressedAt == null || pinnedAt != null || importance >= 2

    /**
     * v18: 按 scope/space 取出“已被取代”的旧记忆 id 集合。
     *
     * 查询失败时返回空集 —— 拿不到仲裁依据时宁可两条都留在上下文,
     * 也不能因为读不到指向而被误剔除一条正确记忆。
     */
    private suspend fun supersededIds(scope: String?, spaceId: String?): Set<Long> =
        runCatching { dao.getSupersededIds(scope, spaceId).toSet() }
            .onFailure { Logger.w("FactStore", "读取被取代记忆失败(本次不剔除): ${it.message}") }
            .getOrDefault(emptySet())

    /**
     * v19: 按 scope/space 取出“已被合并进其他事实”的行 id 集合。
     *
     * 与 [supersededIds] 同样容错: 读不到时返回空集 —— 宁可多注入一条重复记忆,
     * 也不能因为一次读失败把正常记忆挡在上下文外。
     */
    private suspend fun mergedIds(scope: String?, spaceId: String?): Set<Long> =
        runCatching { dao.getMergedIds(scope, spaceId).toSet() }
            .onFailure { Logger.w("FactStore", "读取已合并记忆失败(本次不剔除): ${it.message}") }
            .getOrDefault(emptySet())

    /**
     * v18/v19: 注入上下文用的可见性过滤 —— 已出场的不进,已被取代的不进,
     * 已被合并进其他事实的也不进。列表/记忆页走默认参数(不过滤),
     * 所以被取代、被合并的记忆依旧可见、依旧可以显式检索到。
     */
    private suspend fun List<Fact>.filterRecallable(scope: String?, spaceId: String?): List<Fact> {
        val superseded = supersededIds(scope, spaceId)
        val merged = mergedIds(scope, spaceId)
        return filter { it.isInRecall() && it.id !in superseded && it.id !in merged }
    }

    // ── v8: 按作用域(scope)查询/观察/衰减 ────────────────────────────────

    /**
     * v8: 按 scope 观察事实列表(Flow 形式),用于 UI 实时刷新。
     * 排序与 [getAll] 一致:importance DESC + time DESC。
     */
    fun observeByScope(scope: String): Flow<List<Fact>> =
        dao.observeByScope(scope, java.time.Instant.now().toString()).map { entities -> entities.map { it.toDomainFact() } }

    /**
     * v8: 按 scope 同步查询事实列表。
     * 用于 system prompt 注入、子助手记忆检索等场景。
     */
    /**
     * v17: [includeSuppressed] 默认 true(记忆页/去重等需要看到全部行),
     * 写进上下文的召回路径传 false —— 出场的记忆不该再参与注入,
     * 但“能不能看见”与“要不要注入”必须是两件事。
     * v18: 传 false 时同时排除已被取代的旧记忆。
     */
    suspend fun getByScope(scope: String, includeSuppressed: Boolean = true): List<Fact> = withContext(Dispatchers.IO) {
        val rows = dao.getByScope(scope)
            .map { it.toDomainFact() }
            .filterNot { it.isExpired() }
        if (includeSuppressed) rows else rows.filterRecallable(scope, null)
    }

    /**
     * v8: 按作用域清理 —— 删除指定 scope 下早于 [cutoffIso] 且 importance < [minImportance] 的事实。
     *
     * 这是显式清理入口(数据管理/重置场景)、不是衰减路径:
     * 衰减由 [applyDecay] 按持久分数判定,只出场不删行;
     * 本方法直接传 [cutoffIso] 硬删,调用方需明确知道自己在删数据。
     *
     * @return 实际删除的行数
     */
    suspend fun deleteByScopeExceptImportant(scope: String, cutoffIso: String, minImportance: Int): Int = withContext(Dispatchers.IO) {
        dao.deleteByScopeExceptImportant(scope, cutoffIso, minImportance)
    }

    // ── v9: 按 Space(space_id)查询/观察/衰减 ───────────────────────────

    /**
     * v9: 按 space_id 观察事实列表(Flow 形式),用于记忆页 UI 实时刷新。
     */
    fun observeBySpace(spaceId: String): Flow<List<Fact>> =
        dao.observeBySpace(spaceId, java.time.Instant.now().toString()).map { entities -> entities.map { it.toDomainFact() } }

    /**
     * v9: 按 space_id 同步查询事实列表。
     * 用于记忆页 UI 展示、system prompt 注入等场景。
     * v18: [includeSuppressed]=false 时同时排除已被取代的旧记忆。
     */
    suspend fun getBySpace(spaceId: String, includeSuppressed: Boolean = true): List<Fact> = withContext(Dispatchers.IO) {
        val rows = dao.getBySpace(spaceId)
            .map { it.toDomainFact() }
            .filterNot { it.isExpired() }
        if (includeSuppressed) rows else rows.filterRecallable(null, spaceId)
    }

    /**
     * v9: 按 scope + space_id 双重过滤查询事实列表。
     * scope 按 Agent 隔离,space_id 按场景隔离,两者正交。
     * v18: [includeSuppressed]=false 时同时排除已被取代的旧记忆。
     */
    suspend fun getByScopeAndSpace(scope: String, spaceId: String, includeSuppressed: Boolean = true): List<Fact> =
        withContext(Dispatchers.IO) {
            val rows = dao.getByScopeAndSpace(scope, spaceId)
                .map { it.toDomainFact() }
                .filterNot { it.isExpired() }
            if (includeSuppressed) rows else rows.filterRecallable(scope, spaceId)
        }

    /**
     * v9: 按 scope + space_id 双重过滤观察事实列表(Flow 形式)。
     */
    fun observeByScopeAndSpace(scope: String, spaceId: String): Flow<List<Fact>> =
        dao.observeByScopeAndSpace(scope, spaceId, java.time.Instant.now().toString())
            .map { entities -> entities.map { it.toDomainFact() } }

    /**
     * v9: 按 space_id 清理 —— 删除指定 Space 下早于 [cutoffIso] 且 importance < [minImportance] 的事实。
     *
     * 同 [deleteByScopeExceptImportant]:显式清理入口,不是衰减路径。
     *
     * @return 实际删除的行数
     */
    suspend fun deleteBySpaceExceptImportant(spaceId: String, cutoffIso: String, minImportance: Int): Int = withContext(Dispatchers.IO) {
        dao.deleteBySpaceExceptImportant(spaceId, cutoffIso, minImportance)
    }

    /**
     * v9: 统计指定 Space 下的事实数量。
     */
    suspend fun countBySpace(spaceId: String): Int = withContext(Dispatchers.IO) {
        dao.countBySpace(spaceId)
    }

    // ════════════════════════════
    //  内部转换
    // ════════════════════════════

    /**
     * v1.0.51: 安全 upsert FTS 索引 — 先删后插,避免 FTS4 表产生重复 fact_id 条目。
     *
     * FTS4 虚拟表无唯一约束,`INSERT OR REPLACE` 只按 rowid 去重不按 fact_id 去重,
     * 合并/更新事实时直接 insertFts 会产生重复索引行,导致搜索返回重复结果。
     */
    private suspend fun syncFtsRow(factId: Long, contentNgram: String) {
        dao.deleteFts(factId)
        dao.insertFts(factId, contentNgram)
    }

    private fun FactEntity.toDomainFact(): Fact = Fact(
        id = id,
        fact = fact,
        tags = decodeTags(tags),
        time = time,
        sessionId = sessionId,
        createdAt = createdAt,
        importance = importance,
        category = category,
        confidence = confidence,
        source = source,
        expiresAt = expiresAt,
        lastConfirmedAt = lastConfirmedAt,
        lastHitAt = lastHitAt,
        hitCount = hitCount,
        // v8: 透传 scope 字段
        scope = scope,
        // v9: 透传 spaceId 字段
        spaceId = spaceId,
        // B4-05: 透传置顶时间
        pinnedAt = pinnedAt,
        // v12: 透传实体归一化键
        entityKey = entityKey,
        // v17: 透传持久分数与出场标记
        score = score,
        suppressedAt = suppressedAt,
        // v18: 透传“取代了哪条旧记忆”
        supersedesId = supersedesId,
        // v19: 透传“被合并进了哪条记忆”
        mergedIntoId = mergedIntoId,
    )

    private fun FactTagSearchRow.toDomainFact(): Fact = Fact(
        id = id,
        fact = fact,
        tags = decodeTags(tags),
        time = time,
        sessionId = sessionId,
        createdAt = createdAt,
        importance = importance,
        category = category,
        confidence = confidence,
        source = source,
        expiresAt = expiresAt,
        lastConfirmedAt = lastConfirmedAt,
        lastHitAt = lastHitAt,
        matchCount = matchCount,
        entityKey = entityKey,
        scope = scope,
        spaceId = spaceId,
        score = score,
        suppressedAt = suppressedAt,
    )

    private fun decodeTags(raw: String): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrElse {
        io.zer0.common.Logger.w("FactStore", "parseTags failed: ${it.message}")
        emptyList()
    }

    /** 日期范围。 */
    data class DateRange(val from: String? = null, val to: String? = null)
}

/**
 * 标签搜索计划：构造 LIKE 候选 SQL，并在 Kotlin 层做 JSON 精确匹配与 matchCount 排序。
 * 避免依赖 SQLite 的 json_each（部分测试环境/旧版 Android SQLite 不支持）。
 */
private class TagSearchPlan(
    private val queryTags: List<String>,
    private val dateRange: FactStore.DateRange?,
    private val limit: Int,
    private val scope: String? = null,
    private val spaceId: String? = null,
) {

    val sql: String = buildString {
        val likeClauses = queryTags.joinToString(" OR ") { "tags LIKE ?" }
        append("SELECT *, 0 as matchCount FROM facts WHERE (").append(likeClauses).append(")")
        if (dateRange?.from != null) append(" AND time >= ?")
        if (dateRange?.to != null) append(" AND time <= ?")
        if (scope != null) append(" AND scope = ?")
        if (spaceId != null) append(" AND space_id = ?")
        append(" ORDER BY importance DESC, time DESC LIMIT ?")
    }

    val args: Array<Any> = buildList {
        queryTags.forEach { add("%\"$it\"%") }
        dateRange?.from?.let { add(it) }
        dateRange?.to?.let { add(it) }
        scope?.let { add(it) }
        spaceId?.let { add(it) }
        add(limit * 2) // 多拉候选,内存过滤后可能丢弃部分
    }.toTypedArray()

    fun refine(rows: List<FactTagSearchRow>): List<FactStore.Fact> {
        val tagSet = queryTags.toSet()
        return rows.mapNotNull { row ->
            val tags = runCatching {
                kotlinx.serialization.json.Json.decodeFromString<List<String>>(row.tags)
            }.getOrDefault(emptyList())
            val matchCount = tags.count { it in tagSet }
            if (matchCount > 0) {
                FactStore.Fact(
                    id = row.id,
                    fact = row.fact,
                    tags = tags,
                    time = row.time,
                    sessionId = row.sessionId,
                    createdAt = row.createdAt,
                    importance = row.importance,
                    category = row.category,
                    confidence = row.confidence,
                    source = row.source,
                    expiresAt = row.expiresAt,
                    lastConfirmedAt = row.lastConfirmedAt,
                    lastHitAt = row.lastHitAt,
                    entityKey = row.entityKey,
                    score = row.score,
                    suppressedAt = row.suppressedAt,
                    matchCount = matchCount,
                )
            } else {
                null
            }
        }.sortedWith(
            compareByDescending<FactStore.Fact> { it.importance }
                .thenByDescending { it.matchCount }
                .thenByDescending { it.time },
        ).take(limit)
    }
}
