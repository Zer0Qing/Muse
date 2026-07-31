package io.zer0.muse.worldbook

import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * P1-2: Worldbook 仓库。
 *
 * 负责 CRUD + 关键词匹配查询 + SillyTavern World Info JSON 导入导出。
 *
 * 关键词匹配两种模式:
 *  - isRegex=false: 子串匹配(contains),大小写由 [WorldBookEntryEntity.caseSensitive] 控制
 *  - isRegex=true:  正则匹配,关键词作为正则源串;caseSensitive 控制 RegexOption.IGNORE_CASE
 *
 * 正则编译结果缓存在 [regexCache],避免每次扫描重复编译(正则编译开销远大于子串匹配)。
 */
class WorldBookRepository(
    private val dao: WorldBookDao,
) {
    fun observeAll(): Flow<List<WorldBookEntryEntity>> = dao.observeAll()

    fun observeEnabled(): Flow<List<WorldBookEntryEntity>> = dao.observeEnabled()

    suspend fun getEnabled(): List<WorldBookEntryEntity> = dao.getEnabled()

    suspend fun getAlwaysActive(assistantId: String?): List<WorldBookEntryEntity> =
        dao.getAlwaysActive(assistantId)

    suspend fun getById(id: String): WorldBookEntryEntity? = dao.getById(id)

    suspend fun upsert(entity: WorldBookEntryEntity) = dao.upsert(entity)

    suspend fun delete(id: String) = dao.deleteById(id)

    /**
     * 在给定文本中匹配 Worldbook 条目。
     * 任一关键词命中即视为匹配;返回所有匹配的条目(按 priority DESC, name ASC)。
     *
     * @param entries 候选条目(应已过滤 enabled)
     * @param text 待匹配文本
     */
    fun matchAgainst(entries: List<WorldBookEntryEntity>, text: String): List<WorldBookEntryEntity> {
        if (entries.isEmpty() || text.isBlank()) return emptyList()
        return entries
            .filter { it.enabled && !it.alwaysActive }  // 常驻条目由 alwaysActive 路径处理
            .filter { entry ->
                val keywords = parseKeywords(entry.keywordsJson)
                if (keywords.isEmpty()) return@filter false
                keywords.any { kw ->
                    if (kw.isBlank()) false
                    else matchKeyword(text, kw, entry.caseSensitive, entry.isRegex)
                }
            }
            .sortedWith(compareByDescending<WorldBookEntryEntity> { it.priority }.thenBy { it.name })
    }

    /**
     * 扫描最近 [scanDepth] 层 USER 消息,返回命中的条目。
     * scanDepth 由每条 entry 自身控制;此处取全部候选条目的最大 scanDepth 作为扫描窗口,
     * 然后对每条 entry 仅扫描其自身 scanDepth 范围内的消息。
     *
     * @param userMessages 全部 USER 消息(按时间正序)
     * @param assistantId 当前助手 id(用于过滤绑定条目)
     * @return 命中条目列表(已排序、去重)
     */
    suspend fun getKeywordEntries(
        userMessages: List<UIMessage>,
        assistantId: String?,
    ): List<WorldBookEntryEntity> {
        val candidates = dao.getEnabled()
            .filter { it.alwaysActive.not() }  // 常驻由 alwaysActive 路径处理
            .filter { it.assistantId == null || it.assistantId == assistantId }
        if (candidates.isEmpty() || userMessages.isEmpty()) return emptyList()

        val matched = LinkedHashMap<String, WorldBookEntryEntity>()
        for (entry in candidates) {
            val depth = entry.scanDepth.coerceAtLeast(1)
            val window = userMessages.takeLast(depth)
            val hit = window.any { msg -> matchAgainst(listOf(entry), msg.content).isNotEmpty() }
            if (hit) matched[entry.id] = entry
        }
        return matched.values
            .sortedWith(compareByDescending<WorldBookEntryEntity> { it.priority }.thenBy { it.name })
    }

    /**
     * 关键词匹配核心逻辑。
     *
     * 正则模式:关键词作为正则源串,caseSensitive=false 时加 IGNORE_CASE。
     * 正则编译失败时记日志并返回 false(避免单条坏正则阻塞整个扫描)。
     * 正则编译结果按 "源串|ignoreCase" 缓存。
     */
    private fun matchKeyword(text: String, keyword: String, caseSensitive: Boolean, isRegex: Boolean): Boolean {
        return if (isRegex) {
            val cacheKey = "$keyword|$caseSensitive"
            val pattern = regexCache.computeIfAbsent(cacheKey) {
                runCatching {
                    val opts = if (caseSensitive) emptySet() else setOf(kotlin.text.RegexOption.IGNORE_CASE)
                    Regex(keyword, opts)
                }.getOrElse { e ->
                    Logger.w(TAG, "正则编译失败: ${e.message}, keyword=$keyword")
                    null
                }
            } ?: return false
            pattern.containsMatchIn(text)
        } else {
            if (caseSensitive) text.contains(keyword)
            else text.contains(keyword, ignoreCase = true)
        }
    }

    // ── SillyTavern World Info JSON 导入导出 ──

    /**
     * 导出为 SillyTavern World Info JSON 格式(兼容 SillyTavern 导入)。
     *
     * 字段映射(Muse → SillyTavern):
     *  - name → comment
     *  - keywordsJson → key (数组)
     *  - content → content
     *  - alwaysActive → constant
     *  - priority → order
     *  - enabled → disable (取反)
     *  - caseSensitive → caseSensitive
     *  - scanDepth → scanDepth
     *  - injectTarget → role (0=system/1=user/2=assistant)
     *  - injectPosition → position (0=before/1=after/4=at_depth)
     *  - insertionDepth → depth
     */
    suspend fun exportSillyTavernJson(): String {
        val entries = dao.getAll()
        val entriesObj = buildJsonObject {
            entries.forEachIndexed { index, entry ->
                put(index.toString(), buildSillyTavernEntry(entry, index))
            }
        }
        val root = buildJsonObject {
            put("entries", entriesObj)
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * 从 SillyTavern World Info JSON 导入。
     * 已有同 id 条目会被覆盖(id 重新生成为新 UUID,避免与现有冲突)。
     *
     * @return 成功导入的条目数
     */
    suspend fun importSillyTavernJson(jsonText: String): Int {
        val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { e ->
                Logger.w(TAG, "SillyTavern JSON 解析失败: ${e.message}")
                return 0
            }
        val entriesObj = root["entries"]?.jsonObject ?: return 0
        val toInsert = mutableListOf<WorldBookEntryEntity>()
        val now = System.currentTimeMillis()
        for ((_, value) in entriesObj) {
            val obj = value.jsonObject
            val entry = parseSillyTavernEntry(obj, now) ?: continue
            toInsert.add(entry)
        }
        if (toInsert.isEmpty()) return 0
        dao.insertAll(toInsert)
        Logger.i(TAG, "SillyTavern 导入完成: ${toInsert.size} 条")
        return toInsert.size
    }

    private fun buildSillyTavernEntry(entry: WorldBookEntryEntity, uid: Int): JsonObject = buildJsonObject {
        put("uid", uid)
        val keys = parseKeywords(entry.keywordsJson)
        put("key", JsonArray(keys.map { JsonPrimitive(it) }))
        put("keysecondary", JsonArray(emptyList()))
        put("comment", entry.name)
        put("content", entry.content)
        put("constant", entry.alwaysActive)
        put("vectorized", false)
        put("selective", true)
        put("selectiveLogic", 0)
        put("addMemo", false)
        put("order", entry.priority)
        put("position", when (WorldBookInjectPosition.fromStorage(entry.injectPosition)) {
            WorldBookInjectPosition.PREPEND -> 0
            WorldBookInjectPosition.APPEND -> 1
            WorldBookInjectPosition.AT_DEPTH -> 4
        })
        put("disable", !entry.enabled)
        put("excludeRecursion", false)
        put("preventRecursion", false)
        put("delayUntilRecursion", false)
        put("probability", 100)
        put("useProbability", true)
        put("title", entry.name)
        put("role", when (WorldBookInjectTarget.fromStorage(entry.injectTarget)) {
            WorldBookInjectTarget.SYSTEM -> 0
            WorldBookInjectTarget.USER -> 1
            WorldBookInjectTarget.ASSISTANT -> 2
        })
        put("depth", entry.insertionDepth)
        put("group", "")
        put("groupOverride", false)
        put("groupWeight", 100)
        put("scanDepth", entry.scanDepth)
        put("caseSensitive", entry.caseSensitive)
        put("matchWholeWords", null)
        put("automationId", "")
        put("displayIndex", uid)
    }

    private fun parseSillyTavernEntry(obj: JsonObject, now: Long): WorldBookEntryEntity? {
        return runCatching {
            val keys = obj["key"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val comment = (obj["comment"] as? JsonPrimitive)?.contentOrNull
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull
            val name = comment?.ifBlank { title } ?: title ?: "未命名"
            val content = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: ""
            val alwaysActive = (obj["constant"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val priority = (obj["order"] as? JsonPrimitive)?.intOrNull ?: 50
            val disabled = (obj["disable"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val caseSensitive = (obj["caseSensitive"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val scanDepth = (obj["scanDepth"] as? JsonPrimitive)?.intOrNull ?: 3
            val role = (obj["role"] as? JsonPrimitive)?.intOrNull ?: 0
            val position = (obj["position"] as? JsonPrimitive)?.intOrNull ?: 1
            val depth = (obj["depth"] as? JsonPrimitive)?.intOrNull ?: 0
            WorldBookEntryEntity(
                id = "wb-${UUID.randomUUID()}",
                name = name,
                keywordsJson = encodeKeywords(keys),
                content = content,
                priority = priority,
                enabled = !disabled,
                caseSensitive = caseSensitive,
                isRegex = false,
                alwaysActive = alwaysActive,
                scanDepth = scanDepth,
                injectTarget = when (role) {
                    1 -> WorldBookInjectTarget.USER.storage
                    2 -> WorldBookInjectTarget.ASSISTANT.storage
                    else -> WorldBookInjectTarget.SYSTEM.storage
                },
                injectPosition = when (position) {
                    0 -> WorldBookInjectPosition.PREPEND.storage
                    4 -> WorldBookInjectPosition.AT_DEPTH.storage
                    else -> WorldBookInjectPosition.APPEND.storage
                },
                insertionDepth = depth,
                assistantId = null,
                createdAt = now,
                updatedAt = now,
            )
        }.getOrElse { e ->
            Logger.w(TAG, "SillyTavern 条目解析失败: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "WorldBookRepository"

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        private val stringListSerializer = ListSerializer(String.serializer())
        /** 正则编译缓存:key = "源串|caseSensitive"。 */
        private val regexCache = ConcurrentHashMap<String, Regex?>()
        /** keywordsJson 解析缓存。 */
        private val keywordsCache = ConcurrentHashMap<String, List<String>>()

        fun parseKeywords(keywordsJson: String): List<String> =
            keywordsCache.computeIfAbsent(keywordsJson) {
                runCatching { json.decodeFromString(stringListSerializer, it) }
                    .getOrElse { e ->
                        Logger.w(TAG, "keywordsJson 解析失败: ${e.message}, raw=$keywordsJson")
                        emptyList()
                    }
            }

        fun encodeKeywords(keywords: List<String>): String =
            runCatching { json.encodeToString(stringListSerializer, keywords) }
                .getOrDefault("[]")
    }
}
