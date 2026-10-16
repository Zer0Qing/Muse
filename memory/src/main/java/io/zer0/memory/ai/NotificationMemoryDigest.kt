package io.zer0.memory.ai

import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * v2.6.2（用户反馈）: 通知 → 记忆 梳理器。
 *
 * 背景:用户希望助手「自己知道」收到的通知（如快递到达、日程提醒、订单状态），
 * 并能在主动消息里自然提起。通知监听已采集数据，但此前从未进入记忆管道。
 *
 * 职责:
 *  1. 接收一段时间窗口内的通知快照
 *  2. 交给大工具模型（[MemoryLlmClient]，由 app 层路由到 UtilityTier.LARGE）
 *     做梳理 / 整合 / 去重 / 价值判断
 *  3. 只把「值得记」的结构化事实写入 [FactStore]（写入侧再做一次相似度去重）
 *
 * 设计要点:
 *  - **筛选而非全量**:营销推送 / 新闻 / 点赞等噪音不落库，否则记忆检索质量崩坏。
 *    用户可要求「记更多」，故筛选倾向偏宽松（事件类、生活类、约定类都记），
 *    但纯噪音仍剔除。
 *  - **时效**:只处理 [windowMs] 窗口内的通知，避免把三天前的旧闻当新闻提。
 *  - **幂等**:同一批通知内容指纹（通知键集合）只处理一次，防止重复落库。
 *  - 通知事实标记 `source = "notification"`，便于用户单独识别与清理。
 */
class NotificationMemoryDigest(
    private val factDbProvider: FactDbProvider,
    private val llmClient: MemoryLlmClient,
) {

    /** 单条通知（由 app 层从通知监听服务投影而来，避免 memory 模块依赖 app）。 */
    @Serializable
    data class NotificationInput(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val timestamp: Long,
    )

    /** 梳理结果。 */
    @Serializable
    data class DigestResult(
        val extracted: Int = 0,
        val skipped: Boolean = false,
        val reason: String = "",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /** 并发控制:同一时刻只跑一次通知梳理（LLM 调用较贵）。 */
    private val mutex = Mutex()

    /** 已处理的通知批次指纹（进程内 LRU，防止同一批通知反复梳理）。 */
    private val processedFingerprints = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > MAX_FINGERPRINTS
    }

    /**
     * 梳理一批通知并写入记忆。
     *
     * @param notifications 通知快照（调用方已按时间窗口过滤）
     * @param scope 记忆作用域（助手 id；"main" 表示主助手）
     * @param spaceId 记忆空间
     * @param model 大工具模型（null 时由 MemoryLlmClient 回退主链路模型）
     * @param locale 语言
     * @return 梳理统计
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun digest(
        notifications: List<NotificationInput>,
        scope: String = "main",
        spaceId: String = "default",
        model: Model? = null,
        locale: String = "zh-CN",
    ): DigestResult = mutex.withLock {
        if (notifications.isEmpty()) return@withLock DigestResult(skipped = true, reason = "无通知")
        val fingerprint = fingerprintOf(notifications)
        synchronized(processedFingerprints) {
            if (processedFingerprints.containsKey(fingerprint)) {
                return@withLock DigestResult(skipped = true, reason = "该批通知已梳理")
            }
        }

        val factStore = factDbProvider.getFactStore(scope)
        val existingPreview = runCatching {
            factStore.getByScopeAndSpace(scope, spaceId)
                .take(MAX_EXISTING_PREVIEW)
                .joinToString("\n") { "- ${it.fact.take(60)}" }
                .takeIf { it.isNotBlank() }
        }.getOrNull()

        val raw = try {
            withContext(Dispatchers.IO) {
                llmClient.callText(
                    systemPrompt = buildSystemPrompt(locale, existingPreview),
                    userContent = buildUserContent(notifications),
                    model = model,
                    temperature = 0.3f,
                    maxTokens = DIGEST_MAX_TOKENS,
                    timeoutMs = 60_000L,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "通知梳理 LLM 调用失败: ${e.message}", e)
            return@withLock DigestResult(skipped = true, reason = "LLM 调用失败")
        }
        val parsed = parseResult(raw)
        if (parsed == null || parsed.facts.isEmpty()) {
            markProcessed(fingerprint)
            return@withLock DigestResult(skipped = true, reason = "无可记内容")
        }

        // 落库:source=notification,便于用户识别与清理；写入侧再做相似度去重。
        val entries = parsed.facts
            .filter { it.isNotBlank() }
            .take(MAX_FACTS_PER_BATCH)
            .map { text ->
                FactStore.Fact(
                    fact = text,
                    sessionId = null,
                    importance = 0,
                    category = "event",
                    confidence = 0.8f,
                    source = "notification",
                )
            }
        val inserted = runCatching { factStore.addBatch(entries, scope, spaceId) }
            .onFailure { Logger.w(TAG, "通知事实落库失败: ${it.message}", it) }
            .getOrDefault(0)

        markProcessed(fingerprint)
        Logger.i(TAG, "通知梳理完成: 输入 ${notifications.size} 条 → 落库 $inserted 条")
        DigestResult(extracted = inserted)
    }

    private fun markProcessed(fingerprint: String) {
        synchronized(processedFingerprints) { processedFingerprints[fingerprint] = true }
    }

    private fun fingerprintOf(notifications: List<NotificationInput>): String {
        val seed = notifications.joinToString("|") { "${it.packageName}:${it.timestamp}:${it.title.take(30)}" }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    private fun buildUserContent(notifications: List<NotificationInput>): String = buildString {
        appendLine("## 最近收到的通知")
        appendLine()
        notifications.sortedBy { it.timestamp }.forEach { n ->
            val app = n.appLabel.ifBlank { n.packageName }
            appendLine("- [$app] ${n.title}${if (n.text.isNotBlank()) " — ${n.text.take(200)}" else ""}")
        }
    }

    /** 解析 LLM 输出:期望 `{"facts": ["...", "..."]}`。容错去围栏/think。 */
    internal fun parseResult(raw: String): DigestJson? {
        val s0 = raw.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        val fence = Regex("""```(?:json)?\s*\n([\s\S]*?)\n```""").find(s0)
        var s = fence?.groupValues?.get(1) ?: s0.trim()
        if (!s.startsWith("{")) {
            val start = s.indexOf('{')
            val end = s.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            s = s.substring(start, end + 1)
        }
        return runCatching { json.decodeFromString(DigestJson.serializer(), s) }
            .onFailure { Logger.w(TAG, "通知梳理 JSON 解析失败: ${it.message}") }
            .getOrNull()
    }

    @Serializable
    internal data class DigestJson(val facts: List<String> = emptyList())

    companion object {
        private const val TAG = "NotifMemoryDigest"
        private const val DIGEST_MAX_TOKENS = 1500
        private const val MAX_FACTS_PER_BATCH = 15
        private const val MAX_EXISTING_PREVIEW = 20
        private const val MAX_FINGERPRINTS = 50

        /**
         * 构建 system prompt。
         *
         * 用户要求「记更多」，故筛选倾向偏宽松：事件 / 生活 / 约定 / 待办都记；
         * 仅剔除纯营销噪音。要求输出结构化短句（一条一个独立事实），便于后续去重与检索。
         */
        internal fun buildSystemPrompt(locale: String = "zh-CN", existingPreview: String? = null): String {
            val isZh = locale.startsWith("zh")
            val existingSection = if (!existingPreview.isNullOrBlank()) {
                if (isZh) {
                    "\n## 已有记忆（避免重复）\n\n$existingPreview\n"
                } else {
                    "\n## Existing memories (avoid duplicates)\n\n$existingPreview\n"
                }
            } else {
                ""
            }
            return if (isZh) {
                """
你是一个记忆整理助手。用户希望助手「记得」他手机上收到的通知，并能在之后自然提起。
下面给你用户最近收到的一批通知，请从中提炼值得长期记住的事实。

$existingSection
## 值得记的（尽量多记）
- 快递/物流、订单状态、外卖进度
- 日程提醒、会议、约会、航班车次
- 账单/缴费/还款提醒、金额与截止日期
- 健康提醒（吃药、体检、运动）
- 验证码、预约确认、取件码等有时效的具体信息
- 用户与他人的约定、待办事项

## 不值得记的（剔除）
- 纯营销推广、广告、优惠券推送
- 新闻资讯、天气播报（除非用户明显关心）
- 社交点赞、关注、评论提醒
- 系统更新、App 版本提示

## 输出要求
- 每条事实用一句独立的、自足的中文短句表达（脱离上下文也能看懂）
- 合并同一事项的多条通知（如同一快递的多次更新只记最终状态）
- 与「已有记忆」重复的不要重复输出
- 只输出 JSON，不要任何额外说明或 markdown 围栏：
{"facts": ["事实1", "事实2"]}
如果整批通知都不值得记，返回 {"facts": []}
                """.trimIndent()
            } else {
                """
You are a memory organizer. The user wants the assistant to REMEMBER notifications received on their phone.
Below is a batch of recent notifications. Extract facts worth remembering long-term.

$existingSection
## Worth remembering (prefer more)
- Deliveries, orders, food progress
- Calendar reminders, meetings, flights/trains
- Bills, payments, deadlines with amounts
- Health reminders (medication, checkups)
- Verification codes, appointment/parcel pickup codes
- Commitments and to-dos

## Not worth remembering (drop)
- Pure marketing, ads, coupons
- News, generic weather
- Social likes/follows/comments
- System or app updates

## Output
- One self-contained sentence per fact
- Merge multiple notifications about the same item
- Skip anything already in "Existing memories"
- JSON only, no markdown fences:
{"facts": ["fact 1", "fact 2"]}
If nothing is worth remembering, return {"facts": []}
                """.trimIndent()
            }
        }
    }
}
