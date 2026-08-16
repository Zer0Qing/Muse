package io.zer0.memory.fact

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.pii.PiiGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

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
     * 审计修复 (S-04): 删除墓碑文件路径(JSON 字符串数组)。
     *
     * 背景: 注入链路读 compiled_sections(由 MemoryCompiler.compileFacts 从 30 天会话摘要
     * LLM 编译),而记忆页"删除事实"只删 facts 表 — 已删事实会从摘要中"复活"并继续注入。
     * 删除时把事实原文记录为墓碑,compileFacts/compileToday 按墓碑过滤输入与输出,
     * 已删事实不再复活。为 null 时墓碑禁用(测试环境或未注入时降级)。
     */
    private val tombstoneFile: File? = null,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** v6: 是否已经做过 FTS 索引一致性检查(避免每次搜索都重复 COUNT)。 */
    private var ftsConsistencyChecked = false

    /** S-04: 墓碑缓存(null = 未加载)。 */
    private var tombstoneCache: List<String>? = null

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
        /**
         * v8: 记忆作用域,默认 "main" 表示主助手作用域。
         * 子助手/团队成员使用各自的 assistantId,用于隔离不同 Agent 的记忆。
         * [add] / [addBatch] 的 scope 参数会覆盖此字段。
         */
        val scope: String = "main",
        /**
         * v9: 记忆空间 id,默认 "default" 表示默认空间。
         * 用于多 Space 隔离(类似 Notion 工作区,工作/生活/学习场景互不干扰)。
         * 与 [scope] 正交:scope 按 Agent 隔离,spaceId 按场景隔离。
         * [add] / [addBatch] 的 spaceId 参数会覆盖此字段。
         */
        val spaceId: String = "default",
        /** B4-05: 手动置顶时间 ISO 8601,null 表示未置顶。 */
        val pinnedAt: String? = null,
        val matchCount: Int? = null,
    )

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
     * @param maxGroups 最多返回簇数(限制 LLM 调用次数,默认 10)
     * @return 相似簇列表(每簇按 id 升序)
     */
    suspend fun findSimilarGroups(scope: String = "main", maxGroups: Int = 10): List<List<Fact>> =
        withContext(Dispatchers.IO) {
            val all = dao.getByScope(scope).map { it.toDomainFact() }
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
    private fun negationCount(text: String): Int =
        NEGATION_RE.findAll(text).count()

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
     */
    private fun normalizeDedupText(text: String): String {
        return text.trim().lowercase()
            .replace(Regex("^(用户|我|他|她|这个用户|the user|i am|he is|she is)\\s*"), "")
            .trim()
    }

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
        )
    }

    /**
     * v9: 合并两条相似事实的文本。
     * 若语义相同(去主语后相等),保留更短、更原始的表述;
     * 否则保留信息更完整(更长)的版本。
     */
    private fun pickMergedWording(existing: String, new: String): String {
        val normExisting = normalizeDedupText(existing)
        val normNew = normalizeDedupText(new)
        return if (normExisting == normNew) {
            // 仅差主语时保留更短的原始表述
            if (new.length < existing.length) new else existing
        } else {
            if (new.length > existing.length) new else existing
        }
    }

    /**
     * v9: 查找数据库中与新事实语义相似的已有事实。
     * 先用原始前缀匹配,再用去主语后的前缀匹配,最后用子串搜索兜底,
     * 确保"对青霉素过敏"和"用户对青霉素过敏"能被识别为同一条。
     *
     * v9 改进: 新增 spaceId 过滤,仅在相同 scope + space_id 内查找相似事实,
     * 避免跨空间误合并(如"工作"空间的"喜欢美式咖啡"不应与"生活"空间的"喜欢美式咖啡"合并)。
     */
    private suspend fun findSimilarFact(cleaned: String, scope: String, spaceId: String = "default"): FactEntity? {
        // 1) 原始前缀匹配(保持 v5 行为)+ space_id 过滤
        dao.findSimilarBySpace(cleaned.take(40), scope, spaceId)
            .firstOrNull { isSimilar(it.fact, cleaned) }?.let { return it }

        // 2) 去主语后的前缀匹配
        val normalized = normalizeDedupText(cleaned)
        if (normalized != cleaned.lowercase() && normalized.isNotBlank()) {
            dao.findSimilarBySpace(normalized.take(40), scope, spaceId)
                .firstOrNull { isSimilar(it.fact, cleaned) }?.let { return it }
        }

        // 3) 兜底:子串搜索,限制数量避免全表扫描
        // v9: 同时按 scope + space_id 过滤,避免跨作用域/跨空间误合并
        dao.likeSearchBySpace(cleaned.take(40), 20, scope, spaceId)
            .firstOrNull { isSimilar(it.fact, cleaned) }?.let { return it }

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
    suspend fun dedupPass(scope: String = "main", maxPairs: Int = 300): Int = withContext(Dispatchers.IO) {
        val all = dao.getByScope(scope).toMutableList()
        if (all.size < 2) return@withContext 0
        var merged = 0
        var i = 0
        while (i < all.size && merged < maxPairs) {
            val current = all[i]
            var j = i + 1
            while (j < all.size && merged < maxPairs) {
                val other = all[j]
                if (other.pinnedAt == null && isSimilar(current.fact, other.fact)) {
                    // 合并 other → current(保留重要度更高者为主,否则保留 current)
                    val keeper = if (other.importance > current.importance) other else current
                    val mergedEntity = mergeForDedup(keeper, if (keeper.id == current.id) other else current)
                    dao.insert(mergedEntity.copy(id = keeper.id)) // REPLACE:覆盖 keeper
                    dao.deleteById(if (keeper.id == current.id) other.id else current.id)
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
        val newEntry = entry.copy(fact = cleaned, scope = scope, spaceId = spaceId)
        val existingSimilar = findSimilarFact(cleaned, scope, spaceId)
        if (existingSimilar != null) {
            val merged = mergeFact(existingSimilar, newEntry)
            dao.updateEntity(
                merged.id, merged.fact, merged.tags, merged.time, merged.sessionId,
                merged.createdAt, merged.importance, merged.category, merged.confidence,
                merged.source, merged.expiresAt, merged.lastConfirmedAt, merged.lastHitAt,
            )
            syncFtsRow(merged.id, FactFtsManager.toNgram(merged.fact))
            io.zer0.common.Logger.d("FactStore", "合并相似事实(scope=$scope, space=$spaceId): ${existingSimilar.fact.take(30)}… ↔ ${cleaned.take(30)}… → id=${existingSimilar.id}")
            return@withContext existingSimilar.id
        }
        val importance = if (newEntry.importance > 0) newEntry.importance else inferImportanceScore(cleaned)
        val now = Instant.now().toString()
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
        )
        val insertedId = dao.insert(entity)
        dao.insertFts(insertedId, FactFtsManager.toNgram(cleaned))
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
        db.withTransaction {
            var inserted = 0
            for (entry in entries) {
                val (cleaned, detected) = PiiGuard.scrub(entry.fact)
                if (detected.isNotEmpty()) {
                    io.zer0.common.Logger.d("FactStore", "PII detected in batch fact: $detected")
                }
                val newEntry = entry.copy(fact = cleaned, scope = scope, spaceId = spaceId)
                val existingSimilar = findSimilarFact(cleaned, scope, spaceId)
                if (existingSimilar != null) {
                    val merged = mergeFact(existingSimilar, newEntry)
                    dao.updateEntity(
                        merged.id, merged.fact, merged.tags, merged.time, merged.sessionId,
                        merged.createdAt, merged.importance, merged.category, merged.confidence,
                        merged.source, merged.expiresAt, merged.lastConfirmedAt, merged.lastHitAt,
                    )
                    syncFtsRow(merged.id, FactFtsManager.toNgram(merged.fact))
                } else {
                    val importance = if (newEntry.importance > 0) newEntry.importance else inferImportanceScore(cleaned)
                    val insertedId = dao.insert(FactEntity(
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
                    ))
                    dao.insertFts(insertedId, FactFtsManager.toNgram(cleaned))
                    // 新插入的 id 不会有重复 FTS,直接 insertFts 即可(upsertFts 多一次 DELETE 无必要)
                }
                inserted++
            }
            inserted
        }
    }

    /**
     * v6: 全文搜索。优先 FTS4 MATCH(ngram 预处理),单字/异常时回退 LIKE。
     */
    suspend fun searchFullText(query: String, limit: Int = 20): List<Fact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureFtsIndexConsistent()
        runFtsOrLikeSearch(query.trim(), limit)
    }

    /** 先尝试 FTS4 MATCH，单字或异常时回退 LIKE。 */
    private suspend fun runFtsOrLikeSearch(trimmed: String, limit: Int): List<Fact> {
        if (FactFtsManager.shouldFallbackToLike(trimmed)) {
            return dao.likeSearch(trimmed, limit).map { it.toDomainFact() }
        }
        val matchQuery = FactFtsManager.toMatchQuery(trimmed)
        if (matchQuery.isBlank()) {
            return dao.likeSearch(trimmed, limit).map { it.toDomainFact() }
        }
        val ftsResults = resultOf { dao.searchFts(matchQuery, limit) }
            .onError { msg, t -> io.zer0.common.Logger.w("FactStore", "FTS search failed, fallback to LIKE: $msg", t) }
            .getOrNull() ?: emptyList()
        if (ftsResults.isEmpty()) {
            return dao.likeSearch(trimmed, limit).map { it.toDomainFact() }
        }
        return ftsResults.map { it.toDomainFact() }
    }

    /**
     * 按标签搜索(精确匹配,OR 逻辑,按匹配数降序)。
     * 使用 json_each 精确匹配标签值,避免 LIKE 子串误匹配。
     */
    suspend fun searchByTags(
        queryTags: List<String>,
        dateRange: DateRange? = null,
        limit: Int = 20,
    ): List<Fact> = withContext(Dispatchers.IO) {
        if (queryTags.isEmpty()) return@withContext emptyList()
        val plan = TagSearchPlan(queryTags, dateRange, limit)
        val rows = dao.tagSearch(SimpleSQLiteQuery(plan.sql, plan.args))
        plan.refine(rows)
    }

    /**
     * 获取所有元事实(按时间降序)。
     *
     * v8: 新增可选 scope 参数,null 表示全部作用域,非 null 仅返回指定作用域的事实。
     */
    suspend fun getAll(scope: String? = null): List<Fact> = withContext(Dispatchers.IO) {
        dao.getAll(scope).map { it.toDomainFact() }.filterNot { it.isExpired() }
    }

    /** B-18: 事实是否已过期(expires_at 存在且早于当前时间)。 */
    private fun Fact.isExpired(): Boolean {
        val expiresAt = expiresAt ?: return false
        val t = runCatching { java.time.Instant.parse(expiresAt) }.getOrNull() ?: return false
        return t.isBefore(java.time.Instant.now())
    }

    /** 按 session_id 查询。 */
    suspend fun getBySession(sessionId: String): List<Fact> = withContext(Dispatchers.IO) {
        dao.getBySession(sessionId).map { it.toDomainFact() }
    }

    /** 按 id 查询。 */
    suspend fun getById(id: Long): Fact? = withContext(Dispatchers.IO) {
        dao.getById(id)?.toDomainFact()
    }

    /** 总数。 */
    suspend fun size(): Int = withContext(Dispatchers.IO) { dao.count() }

    /** 删除单条。返回是否删除成功。 */
    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        val target = dao.getById(id)
        dao.deleteFts(id)
        val removed = dao.deleteById(id) > 0
        if (removed) {
            // 审计修复 (C-07): 同步清理指向该事实的 memory_links 孤儿边 —
            // memory_links 建表时刻意不加外键级联（避免级联性能损耗，见 FactDb.MIGRATION_9_10），
            // 事实删除后须在应用层删除以 source/target 指向该 id 的边，避免知识图谱脏边。
            db.memoryLinkDao().deleteByFactId(id)
            if (target != null) {
                // 审计修复 (S-04): 记录删除墓碑 — 已删事实不得从会话摘要重新编译时"复活"。
                // 规范化后去重存储,只增不删(除非 clearAll)。
                recordTombstone(target.fact)
            }
        }
        removed
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
        val updated = dao.updateContent(id, scrubbed, scope) > 0
        if (updated) {
            // v1.0.51: 用 upsertFts 避免更新已有事实时产生重复 FTS 条目
            syncFtsRow(id, FactFtsManager.toNgram(scrubbed))
        }
        updated
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
    suspend fun updateCategoryAndTags(id: Long, category: String? = null, tags: List<String>? = null): Boolean = withContext(Dispatchers.IO) {
        // B-17: tags 逐项 PII 脱敏 — 调用方含 LLM 路径(autoCategorize/AI 记忆管理),
        // prompt 鼓励用人名做标签,不脱敏会把姓名明文落库。
        val scrubbedTags = tags?.map { PiiGuard.scrub(it).cleaned }
        val tagsJson = scrubbedTags?.let { json.encodeToString(ListSerializer(String.serializer()), it) }
        dao.updateCategoryAndTags(id, category, tagsJson) > 0
    }

    /** 清空所有。 */
    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        dao.clearFts()
        dao.deleteAll()
        // S-04: 用户主动重置全部记忆时,墓碑一并清空(不再需要过滤)。
        tombstoneFile?.let { runCatching { it.delete() } }
        tombstoneCache = null
    }

    // ─── S-04: 删除墓碑(防已删事实从摘要复活) ───

    /** 全部删除墓碑(规范化文本)。墓碑文件不存在/解析失败时返回空列表。 */
    suspend fun getTombstones(): List<String> = withContext(Dispatchers.IO) {
        loadTombstones()
    }

    private fun loadTombstones(): List<String> {
        tombstoneCache?.let { return it }
        val file = tombstoneFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        val list = runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), file.readText())
        }.getOrElse {
            Logger.w("FactStore", "readTombstones failed: ${it.message}")
            emptyList()
        }
        tombstoneCache = list
        return list
    }

    private fun recordTombstone(content: String) {
        val file = tombstoneFile ?: return
        val normalized = normalizeTombstone(content)
        if (normalized.isBlank()) return
        val current = loadTombstones()
        if (current.contains(normalized)) return // 幂等:相同事实只记一次
        writeTombstones(current + normalized)
        tombstoneCache = current + normalized
    }

    private fun writeTombstones(list: List<String>) {
        val file = tombstoneFile ?: return
        file.parentFile?.mkdirs()
        val encoded = json.encodeToString(ListSerializer(String.serializer()), list)
        // 临时文件 + rename 原子替换,避免进程被杀留下半截 JSON
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(encoded)
            if (!tmp.renameTo(file)) file.writeText(encoded)
        }.onFailure { e ->
            // 墓碑写入失败不阻断删除流程;下次编译时该事实可能复活,但删除本身已生效
            Logger.w("FactStore", "writeTombstones failed: ${e.message}")
        }
    }

    /** 规范化: 去首尾空白 + 压缩连续空白,保证跨措辞微差仍可匹配。 */
    private fun normalizeTombstone(text: String): String =
        text.trim().replace(WHITESPACE_RE, " ")

    private companion object {
        private val WHITESPACE_RE = Regex("\\s+")
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
     * 按 [MemoryConfig] 配置执行一轮 fact 衰减(遗忘)。
     *
     * v4 改进:关键事实(importance=2)永不衰减,避免"青霉素过敏"等高风险信息被遗忘。
     * v7 改进:引入命中加成(hitBonus):
     *  - 从未命中(last_hit_at IS NULL)的事实按 baseImportance 衰减
     *  - 已命中(last_hit_at IS NOT NULL)的事实按 (baseImportance + hitBonus) 衰减,并以 last_hit_at 作为时钟起点
     *  - 合并/新增事实时自动记录命中时间
     *
     * v8 改进:新增可选 scope 参数,null 表示全部作用域(兼容旧调用方),
     * 非 null 时仅衰减指定作用域的事实,避免一个助手的衰减节奏影响其他助手。
     *
     * 该方法在 daily pipeline(deepMemory step 之后)由
     * [io.zer0.memory.deep.DeepMemoryProcessor] 调用,每个 assistant 的 FactStore 各跑一次。
     *
     * @return 实际删除的 fact 数
     */
    suspend fun applyDecay(config: io.zer0.memory.ticker.MemoryConfig, scope: String? = null): Int = withContext(Dispatchers.IO) {
        val neverHitCutoff = io.zer0.memory.ticker.MemoryConfig.safeCutoffDays(config, hit = false)
        val hitCutoff = io.zer0.memory.ticker.MemoryConfig.safeCutoffDays(config, hit = true)
        if (neverHitCutoff.isInfinite() || neverHitCutoff.isNaN() || hitCutoff.isInfinite() || hitCutoff.isNaN()) {
            return@withContext 0
        }
        val now = java.time.Instant.now()
        // B-18: 先清理已过期事实(expires_at < now) — 时效性事实过期后不再驻留/注入
        dao.deleteExpired(now.toString(), scope)
        if (neverHitCutoff <= 0f || hitCutoff <= 0f) {
            // base 已低于阈值,配置上等同于"立即遗忘全部" —— 但 v4: 关键事实(importance=2)仍保留
            io.zer0.common.Logger.w("FactStore", "applyDecay: cutoff<=0, deleting non-critical facts (config=$config, scope=$scope)")
            return@withContext dao.deleteOlderThanExceptImportant(now.toString(), 2, scope)
        }
        val neverHitCutoffInstant = now.minus(neverHitCutoff.toLong(), java.time.temporal.ChronoUnit.DAYS)
        val hitCutoffInstant = now.minus(hitCutoff.toLong(), java.time.temporal.ChronoUnit.DAYS)
        // v4: minImportance=2 表示仅删除 importance < 2 的 fact,关键事实(importance=2)永不衰减
        // v7: 区分命中/未命中事实,分别用不同 cutoff
        // v8: scope 非 null 时仅衰减指定作用域
        dao.deleteOlderThanWithHit(neverHitCutoffInstant.toString(), hitCutoffInstant.toString(), 2, scope)
    }

    // ── v8: 按作用域(scope)查询/观察/衰减 ────────────────────────────────

    /**
     * v8: 按 scope 观察事实列表(Flow 形式),用于 UI 实时刷新。
     * 排序与 [getAll] 一致:importance DESC + time DESC。
     */
    fun observeByScope(scope: String): Flow<List<Fact>> =
        dao.observeByScope(scope).map { entities -> entities.map { it.toDomainFact() } }

    /**
     * v8: 按 scope 同步查询事实列表。
     * 用于 system prompt 注入、子助手记忆检索等场景。
     */
    suspend fun getByScope(scope: String): List<Fact> = withContext(Dispatchers.IO) {
        dao.getByScope(scope).map { it.toDomainFact() }.filterNot { it.isExpired() }
    }

    /**
     * v8: 按作用域衰减删除 — 仅删除指定 scope 下早于 [cutoffIso] 且 importance < [minImportance] 的事实。
     *
     * 与 [applyDecay] 的区别:
     *  - [applyDecay] 按 [MemoryConfig] 计算截止时间,使用命中加成时钟
     *  - 本方法直接传入 [cutoffIso],不区分命中/未命中,用于简单的按时间清理
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
        dao.observeBySpace(spaceId).map { entities -> entities.map { it.toDomainFact() } }

    /**
     * v9: 按 space_id 同步查询事实列表。
     * 用于记忆页 UI 展示、system prompt 注入等场景。
     */
    suspend fun getBySpace(spaceId: String): List<Fact> = withContext(Dispatchers.IO) {
        dao.getBySpace(spaceId).map { it.toDomainFact() }.filterNot { it.isExpired() }
    }

    /**
     * v9: 按 scope + space_id 双重过滤查询事实列表。
     * scope 按 Agent 隔离,space_id 按场景隔离,两者正交。
     */
    suspend fun getByScopeAndSpace(scope: String, spaceId: String): List<Fact> = withContext(Dispatchers.IO) {
        dao.getByScopeAndSpace(scope, spaceId).map { it.toDomainFact() }.filterNot { it.isExpired() }
    }

    /**
     * v9: 按 scope + space_id 双重过滤观察事实列表(Flow 形式)。
     */
    fun observeByScopeAndSpace(scope: String, spaceId: String): Flow<List<Fact>> =
        dao.observeByScopeAndSpace(scope, spaceId).map { entities -> entities.map { it.toDomainFact() } }

    /**
     * v9: 按 space_id 衰减删除 — 仅删除指定 Space 下早于 [cutoffIso] 且 importance < [minImportance] 的事实。
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
        // v8: 透传 scope 字段
        scope = scope,
        // v9: 透传 spaceId 字段
        spaceId = spaceId,
        // B4-05: 透传置顶时间
        pinnedAt = pinnedAt,
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
) {

    val sql: String = buildString {
        val likeClauses = queryTags.joinToString(" OR ") { "tags LIKE ?" }
        append("SELECT *, 0 as matchCount FROM facts WHERE (").append(likeClauses).append(")")
        if (dateRange?.from != null) append(" AND time >= ?")
        if (dateRange?.to != null) append(" AND time <= ?")
        append(" ORDER BY importance DESC, time DESC LIMIT ?")
    }

    val args: Array<Any> = buildList {
        queryTags.forEach { add("%\"$it\"%") }
        dateRange?.from?.let { add(it) }
        dateRange?.to?.let { add(it) }
        add(limit * 2) // 多拉候选,内存过滤后可能丢弃部分
    }.toTypedArray()

    fun refine(rows: List<FactTagSearchRow>): List<FactStore.Fact> {
        val tagSet = queryTags.toSet()
        return rows.mapNotNull { row ->
            val tags = runCatching {
                kotlinx.serialization.json.Json.decodeFromString<List<String>>(row.tags ?: "[]")
            }.getOrDefault(emptyList())
            val matchCount = tags.count { it in tagSet }
            if (matchCount > 0) FactStore.Fact(
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
                matchCount = matchCount,
            ) else null
        }.sortedWith(
            compareByDescending<FactStore.Fact> { it.importance }
                .thenByDescending { it.matchCount }
                .thenByDescending { it.time }
        ).take(limit)
    }
}
