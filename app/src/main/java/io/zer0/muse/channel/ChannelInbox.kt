package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.ProcessWriteGate
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.sharing.takeCodePoints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * v1.0.92: 渠道入站收件箱 — 记录外部 IM 平台推送进来的消息(webhook 接收侧)。
 *
 * 全局单例(静态对象):WebServer 的 webhook 路由写入,UI 读取展示。
 * 最近处理记录最多 [MAX_ITEMS] 条;未完成事件作为有界队列持久化。
 * 收件箱内容会经 PII 遮蔽后入库,降低敏感信息残留。
 */
object ChannelInbox {

    @Serializable
    enum class DispatchState {
        /** 旧版 JSON 缺少队列字段;升级后不重放历史记录。 */
        COMPLETED,
        PENDING,
        PROCESSING,
        IGNORED,

        /** 需要人工复核的终态;保留必要恢复材料,不会自动重试。 */
        BLOCKED,
    }

    data class Media(
        val kind: String = "",
        val base64: String = "",
        val path: String = "",
    )

    data class Source(
        val platform: String,
        val from: String,
        val channelId: String = "",
        /** 平台事件 ID;平台缺少稳定 ID 时可使用接收端派生的重投键。 */
        val eventId: String = "",
        /** 渠道临时回发凭据的 SecureKeyStore 密文;只在事件待处理期间落盘。 */
        val encryptedReplyContextToken: String = "",
    )

    /** 单条入站消息(摘要 + 原始负载,供排查与后续路由使用)。 */
    @Serializable
    data class Inbound(
        val platform: String,
        /** 消息来源(群/用户 openid / chat_id 等,因平台而异)。 */
        val from: String = "",
        /** 可读摘要(消息文本,已做 PII 遮蔽并截断)。 */
        val summary: String = "",
        /** 原始负载(Raw JSON,截断存储)。 */
        val raw: String = "",
        val timestamp: Long = System.currentTimeMillis(),
        /** v2.0.1: 媒体类型("image" 等;空 = 纯文本)。 */
        val mediaKind: String = "",
        /** v2.0.1: 图片 base64(压缩后;仅 image 类)。 */
        val mediaBase64: String = "",
        /**
         * v2.x (B4): 非图片媒体(视频/文件)落盘后的本地绝对路径。
         *
         * 视频/文件体积大,不适合走 [mediaBase64];下载后保存到私有目录,
         * 路径记于此,供后续处理/引用。空 = 无本地文件(纯文本或未落盘)。
         */
        val mediaPath: String = "",
        /** 渠道配置 ID:webhook 来源为认证配置,长连接来源为当前接收配置。 */
        val sourceChannelId: String = "",
        /** 事件去重 ID;无可用稳定 ID 的来源可以为空。 */
        val sourceEventId: String = "",
        /** 本地派发 ID;无平台事件 ID 时也能定位并恢复同一条队列项。 */
        val dispatchId: String = "",
        /** 旧版 JSON 缺失此字段时默认完成,避免升级后重放历史消息。 */
        val dispatchState: DispatchState = DispatchState.COMPLETED,
        val attemptCount: Int = 0,
        val retryAtMillis: Long = 0L,
        /** 发信前持久化,重试时复用以避免重复调用模型生成不同回复。 */
        val preparedReply: String = "",
        /** 渠道临时回发凭据密文;完成派发后立即清空。 */
        val encryptedReplyContextToken: String = "",
        /** 加密后的 Agent 工具轮次断点;完成派发后立即清空。 */
        val encryptedAgentCheckpoint: String = "",
        /** 终态错误码;当前用于标记不可读取的 Agent 断点。 */
        val dispatchErrorCode: String = "",
        /**
         * 队列序号（2026-11-01 分片存储引入）。
         *
         * 分片文件名用它排序，恢复顺序 = 旧实现的数组顺序；0 表示旧数据，迁移时按原下标补齐。
         * 它只表达**入队先后**，不表达调度优先级 —— 优先级仍由 dispatchState + 列表顺序决定。
         */
        val seq: Long = 0L,
    )

    private const val MAX_ITEMS = 100
    internal const val MAX_PENDING_ITEMS = MAX_ITEMS
    private const val MAX_RAW_LENGTH = 4000
    private const val INBOX_FILE = "channel_inbox.json"

    /** 分片目录名（目录型备份白名单里也要有它）。 */
    internal const val INBOX_DIR_NAME = "channel_inbox"
    private const val WEBHOOK_EVENT_LEDGER_FILE = "channel_webhook_event_ids.json"

    private val _messages = MutableStateFlow<List<Inbound>>(emptyList())
    val messages: StateFlow<List<Inbound>> = _messages.asStateFlow()

    internal val journal = ChannelInboxJournal(_messages)

    /** 兼容旧观察者;只代表新事件已入 inbox,不代表自动回复处理完成。 */
    var onInbound: ((Inbound) -> Unit)?
        get() = journal.onInbound
        set(value) {
            journal.onInbound = value
        }

    /** 绑定应用上下文并恢复历史记录(幂等;webhook 首次触发或 UI 进入时调用)。 */
    @Synchronized
    fun attach(context: Context) {
        val directory = context.applicationContext.filesDir
        journal.attach(
            File(directory, INBOX_FILE),
            File(directory, WEBHOOK_EVENT_LEDGER_FILE),
        )
    }

    /** 记录一条入站消息(summary 做 PII 遮蔽与截断)。 */
    fun record(source: Source, text: String?, rawPayload: String, media: Media = Media()): Boolean {
        val safeSummary = text
            ?.let { t ->
                runCatching { io.zer0.memory.pii.PiiGuard.scrub(t).cleaned }
                    .getOrDefault("")
            }
            // 摘要会落库：按码点截断，避免把 emoji 切成半个代理对
            ?.takeCodePoints(2000)
            .orEmpty()
        val item = Inbound(
            platform = source.platform,
            from = source.from,
            summary = safeSummary,
            raw = rawPayload.take(MAX_RAW_LENGTH),
            mediaKind = media.kind,
            mediaBase64 = media.base64,
            mediaPath = media.path,
            sourceChannelId = source.channelId,
            sourceEventId = source.eventId,
            dispatchId = UUID.randomUUID().toString(),
            dispatchState = DispatchState.PENDING,
            encryptedReplyContextToken = source.encryptedReplyContextToken,
        )
        return journal.record(source, item)
    }

    /** 清空已处理历史;未完成队列项会保留,避免清理操作丢失自动回复。 */
    fun clear() = journal.clear()
}

@Suppress("TooManyFunctions") // Queue transitions stay together so every state write shares the same persistence boundary.
internal class ChannelInboxJournal(
    private val messages: MutableStateFlow<List<ChannelInbox.Inbound>> = MutableStateFlow(emptyList()),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** 旧单文件（迁移源）；分片目录就绪后只作为备份白名单里的历史产物保留。 */
    private var file: File? = null

    /** 分片目录：一条队列项一个文件，见 [persistItem]。 */
    private var shardDir: File? = null

    /** 已分配的最大序号（新事件取 +1）。 */
    private var maxSeq: Long = 0L

    private var webhookEventDeduplicator: WebhookEventDeduplicator? = null
    private var webhookEventDeduplicationFailure: Throwable? = null

    @Volatile
    var onInbound: ((ChannelInbox.Inbound) -> Unit)? = null

    @Volatile
    private var pendingListener: (() -> Unit)? = null

    @Synchronized
    fun attach(inboxFile: File, ledgerFile: File) {
        if (shardDir != null) return
        file = inboxFile
        val dir = File(inboxFile.parentFile, ChannelInbox.INBOX_DIR_NAME)
        shardDir = dir
        when {
            // 分片优先：目录里已经有文件就不再碰旧单文件。
            dir.isDirectory && !dir.listFiles().isNullOrEmpty() -> restoreFromShards(dir)
            inboxFile.exists() -> migrateFromLegacyFile(inboxFile)
            else -> Unit
        }
        runCatching { WebhookEventDeduplicator(ledgerFile, nowMillis) }
            .onSuccess {
                webhookEventDeduplicator = it
                webhookEventDeduplicationFailure = null
            }.onFailure { error ->
                webhookEventDeduplicationFailure = error
                Logger.e(TAG, "Webhook 事件去重账本不可用，拒绝处理带事件 ID 的消息", error)
            }
    }

    fun record(source: ChannelInbox.Source, item: ChannelInbox.Inbound): Boolean {
        var wake = false
        var observerItem: ChannelInbox.Inbound? = null
        var ledgerFailure: Throwable? = null
        val accepted = synchronized(this) {
            val stableEventId = source.eventId.isNotBlank() && source.channelId.isNotBlank()
            val existing = if (stableEventId) messages.value.firstOrNull { it.matches(source) } else null
            if (existing != null) {
                wake = existing.isDispatchActive()
                if (wake && source.encryptedReplyContextToken.isNotBlank() &&
                    source.encryptedReplyContextToken != existing.encryptedReplyContextToken
                ) {
                    val index = messages.value.indexOf(existing)
                    val updated = messages.value.toMutableList().also {
                        it[index] = existing.copy(encryptedReplyContextToken = source.encryptedReplyContextToken)
                    }
                    check(persistItem(updated[index])) { "Channel inbox could not refresh the inbound reply context" }
                    messages.value = updated
                }
                ledgerFailure = runCatching { claimEvent(source) }.exceptionOrNull()
                false
            } else if (isEventClaimed(source)) {
                // 重连 Resume 补发的重复事件会落在这里;账本保留 10 分钟,
                // 没有日志的话它们就是无声消失,事后完全查不到痕迹。
                Logger.i(
                    TAG,
                    "事件已被去重账本认领,跳过(platform=${source.platform}, from=${source.from}, " +
                        "eventId=${source.eventId})",
                )
                false
            } else {
                if (pendingCountLocked() >= ChannelInbox.MAX_PENDING_ITEMS) throw ChannelInboxCapacityException()
                val enriched = item.copy(seq = ++maxSeq)
                val candidate = listOf(enriched) + messages.value
                val retained = retain(candidate)
                // 先落盘这条新事件，再改内存；被裁掉的条目随后删分片。
                check(persistItem(enriched)) { "Channel inbox could not persist the inbound event" }
                // 用 seq 判"有没有被裁掉"，不能比 data class 相等：
                // normalize 会把终态条目的 mediaBase64 清空，清空后的副本与原对象不相等 ——
                // 按相等来判会把"刚被规范化过的那条"误当成被裁掉，删掉它的分片。
                val keptSeqs = retained.mapTo(HashSet()) { it.seq }
                deleteDropped(candidate.filterNot { it.seq in keptSeqs })
                messages.value = retained
                observerItem = enriched
                wake = true
                ledgerFailure = runCatching { claimEvent(source) }.exceptionOrNull()
                true
            }
        }
        observerItem?.let { inbound ->
            runCatching { onInbound?.invoke(inbound) }
                .onFailure { error -> Logger.w(TAG, "入站监听回调失败: ${error.message}") }
        }
        if (wake) notifyPendingListener()
        ledgerFailure?.let { throw it }
        return accepted
    }

    fun setPendingListener(listener: (() -> Unit)?) {
        val shouldWake = synchronized(this) {
            pendingListener = listener
            listener != null && messages.value.any { it.dispatchState == ChannelInbox.DispatchState.PENDING }
        }
        if (shouldWake) notifyPendingListener()
    }

    @Synchronized
    fun recoverInterruptedDispatches() {
        val current = messages.value
        val recovered = normalize(
            current.map { item ->
                if (item.dispatchState == ChannelInbox.DispatchState.PROCESSING) {
                    item.copy(dispatchState = ChannelInbox.DispatchState.PENDING, retryAtMillis = 0L)
                } else {
                    item
                }
            },
        )
        if (recovered != current) {
            val changedSeqs = recovered
                .filter { updated -> current.none { it.seq == updated.seq && it == updated } }
                .mapTo(HashSet()) { it.seq }
            changedSeqs.forEach { seq ->
                val normalizedItem = recovered.first { it.seq == seq }
                check(persistItem(normalizedItem)) { "Channel inbox could not recover interrupted dispatches" }
            }
            // 同上：只删"seq 已不存在"的分片。按相等判会把刚改过状态的那几条一并删掉（它们
            // 在 current 里是 PROCESSING、在 recovered 里是 PENDING），等于刚写完就删。
            val keptSeqs = recovered.mapTo(HashSet()) { it.seq }
            deleteDropped(current.filterNot { it.seq in keptSeqs })
            messages.value = recovered
        }
    }

    @Synchronized
    fun claimNextPending(): ChannelInbox.Inbound? {
        val now = nowMillis()
        val index = messages.value.indexOfFirst {
            it.dispatchState == ChannelInbox.DispatchState.PENDING && it.retryAtMillis <= now
        }
        if (index < 0) return null
        val current = messages.value
        val claimed = current[index].copy(dispatchState = ChannelInbox.DispatchState.PROCESSING)
        val updated = current.toMutableList().also { it[index] = claimed }
        check(persistItem(claimed)) { "Channel inbox could not persist dispatch claim" }
        messages.value = updated
        return claimed
    }

    /**
     * v2.6.23(C2): 带去抖窗口的批量领取。
     *
     * 真人连发是常态(一句一行、想到就补一句)。逐条各跑一轮模型有两个后果:
     * 「只回复了半句的前半」和「同一段对话被回复两遍」。本方法把同一渠道同一来话者、
     * 时间相邻的连续**纯文本**消息合成一批交给自动回复,整批只生成一条回复。
     *
     * 规则:
     *  - 队头是媒体消息 → 单条返回,不等待(媒体消息通常独立成条,内容也无法与文本拼接);
     *  - 同源的连发还没静默满窗口(now - 最新一条 < windowMs) → 把队头的 retryAt 推到窗口末尾,
     *    本次返回空 —— 不吞掉任何消息,只是等它把话说完;
     *  - 窗口已满 → 按时间序领取文本批(封顶 maxBatch),逐条转 PROCESSING 落盘。
     * 吸收进来的每一条仍是独立派发:回复成功一起 COMPLETED,重试/拦截各按自己的 dispatchId 记账。
     */
    @Synchronized
    fun claimPendingBatch(windowMs: Long, maxBatch: Int): List<ChannelInbox.Inbound> {
        val now = nowMillis()
        val current = messages.value
        // 队头 = 最早入队的可领取项(record 把新事件放在表头,列表尾部才是「先来」)。
        val headIndex = current.indexOfLast {
            it.dispatchState == ChannelInbox.DispatchState.PENDING && it.retryAtMillis <= now
        }
        if (headIndex < 0) return emptyList()
        val head = current[headIndex]
        if (head.mediaKind.isNotEmpty() || head.mediaPath.isNotEmpty()) {
            return listOf(claimOne(current, headIndex))
        }
        val sameOrigin = current.withIndex().filter { (index, item) ->
            index != headIndex &&
                item.dispatchState == ChannelInbox.DispatchState.PENDING &&
                item.retryAtMillis <= now &&
                item.sourceChannelId == head.sourceChannelId &&
                item.from == head.from &&
                item.mediaKind.isEmpty() &&
                item.mediaPath.isEmpty()
        }
        val newest = (sameOrigin.map { it.value.timestamp } + head.timestamp).maxOrNull() ?: head.timestamp
        // 窗口未满就连单条也等:来话者随时可能补一句,提前回复等于只答半句。
        if (now - newest < windowMs) {
            // 连发还没停:队头推迟到「最后一句说完」,不领取任何一条
            val deferredHead = head.copy(retryAtMillis = newest + windowMs)
            check(persistItem(deferredHead)) { "Channel inbox could not persist debounce deferral" }
            messages.value = current.toMutableList().also { it[headIndex] = deferredHead }
            return emptyList()
        }
        val batch = (listOf(IndexedValue(headIndex, head)) + sameOrigin)
            .sortedBy { it.value.timestamp }
            .take(maxBatch.coerceAtLeast(1))
        return batch.map { (index, _) -> claimOne(current, index) }
    }

    /** 把第 [index] 条转 PROCESSING 并落盘,返回领取后的条目。调用方必须已持有 [Synchronized]。 */
    private fun claimOne(snapshot: List<ChannelInbox.Inbound>, index: Int): ChannelInbox.Inbound {
        val claimed = snapshot[index].copy(dispatchState = ChannelInbox.DispatchState.PROCESSING)
        check(persistItem(claimed)) { "Channel inbox could not persist dispatch claim" }
        messages.value = messages.value.toMutableList().also { it[index] = claimed }
        return claimed
    }

    @Synchronized
    fun savePreparedReply(dispatchId: String, reply: String): Boolean {
        if (reply.isBlank()) return false
        return updateProcessing(dispatchId) { it.copy(preparedReply = reply) }
    }

    @Synchronized
    fun completeDispatch(dispatchId: String, ignored: Boolean) {
        check(
            updateProcessing(dispatchId) {
                it.copy(
                    dispatchState = if (ignored) {
                        ChannelInbox.DispatchState.IGNORED
                    } else {
                        ChannelInbox.DispatchState.COMPLETED
                    },
                    retryAtMillis = 0L,
                    preparedReply = "",
                    encryptedReplyContextToken = "",
                    encryptedAgentCheckpoint = "",
                    dispatchErrorCode = "",
                )
            },
        ) { "Channel inbox dispatch disappeared before completion was recorded" }
    }

    /**
     * 把不可自动恢复的派发转为人工复核终态。
     *
     * Agent checkpoint 故障时不能走 [completeDispatch]：清除密文会丢失
     * 识别/排查副作用是否已经发生所需的证据。只清理一次性回发令牌,
     * 保留 checkpoint 与已准备回复供后续诊断。
     */
    @Synchronized
    fun blockDispatch(dispatchId: String, errorCode: String) {
        require(errorCode.isNotBlank()) { "A blocked dispatch must include an error code" }
        check(
            updateProcessing(dispatchId) {
                it.copy(
                    dispatchState = ChannelInbox.DispatchState.BLOCKED,
                    retryAtMillis = 0L,
                    encryptedReplyContextToken = "",
                    dispatchErrorCode = errorCode,
                )
            },
        ) { "Channel inbox dispatch disappeared before blocked state was recorded" }
    }

    @Synchronized
    fun retryDispatch(dispatchId: String): Long {
        val now = nowMillis()
        var retryAt = now
        check(
            updateProcessing(dispatchId) { current ->
                val attempts = current.attemptCount + 1
                retryAt = now + retryDelayMillis(attempts)
                current.copy(
                    dispatchState = ChannelInbox.DispatchState.PENDING,
                    attemptCount = attempts,
                    retryAtMillis = retryAt,
                    dispatchErrorCode = "",
                )
            },
        ) { "Channel inbox dispatch disappeared before retry was recorded" }
        return retryAt
    }

    @Synchronized
    fun releaseDispatch(dispatchId: String) {
        updateProcessing(dispatchId) {
            it.copy(
                dispatchState = ChannelInbox.DispatchState.PENDING,
                retryAtMillis = 0L,
                dispatchErrorCode = "",
            )
        }
    }

    @Synchronized
    fun nextRetryDelayMillis(): Long? {
        val pendingDelay = messages.value
            .asSequence()
            .filter { it.dispatchState == ChannelInbox.DispatchState.PENDING }
            .minOfOrNull { it.retryAtMillis }
            ?.let { dueAt -> (dueAt - nowMillis()).coerceAtLeast(0L) }
        val processingDelay = RETRY_BASE_DELAY_MS.takeIf {
            messages.value.any { item -> item.dispatchState == ChannelInbox.DispatchState.PROCESSING }
        }
        return listOfNotNull(pendingDelay, processingDelay).minOrNull()
    }

    @Synchronized
    fun pendingCount(): Int = pendingCountLocked()

    fun snapshot(): List<ChannelInbox.Inbound> = messages.value

    @Synchronized
    fun encryptedReplyContextToken(dispatchId: String): String? = messages.value
        .firstOrNull {
            it.dispatchId == dispatchId && it.dispatchState == ChannelInbox.DispatchState.PROCESSING
        }
        ?.encryptedReplyContextToken

    @Synchronized
    fun encryptedAgentCheckpoint(dispatchId: String): String? = messages.value
        .firstOrNull {
            it.dispatchId == dispatchId && it.dispatchState == ChannelInbox.DispatchState.PROCESSING
        }
        ?.encryptedAgentCheckpoint

    @Synchronized
    fun saveEncryptedAgentCheckpoint(dispatchId: String, checkpoint: String): Boolean {
        if (checkpoint.isBlank()) return false
        return updateProcessing(dispatchId) { it.copy(encryptedAgentCheckpoint = checkpoint) }
    }

    /** 清除已处理历史;未完成队列项不会因 UI 清理操作而丢失。 */
    @Synchronized
    fun clear() {
        val retained = messages.value.filter { it.isDispatchActive() }
        val keptSeqs = retained.mapTo(HashSet()) { it.seq }
        deleteDropped(messages.value.filterNot { it.seq in keptSeqs })
        messages.value = retained
    }

    private fun updateProcessing(dispatchId: String, transform: (ChannelInbox.Inbound) -> ChannelInbox.Inbound): Boolean {
        val current = messages.value
        val index = current.indexOfFirst {
            it.dispatchId == dispatchId && it.dispatchState == ChannelInbox.DispatchState.PROCESSING
        }
        if (index < 0) return false
        val transformed = transform(current[index])
        val updated = current.toMutableList().also { it[index] = transformed }
        val retained = retain(updated)
        // 落盘要用**规范化之后**的副本：transform 得出的条目可能还带着 mediaBase64，
        // 而 normalize 会把终态条目的那份字节清掉。写原始副本等于把要清的东西又写回磁盘。
        val normalized = retained.firstOrNull { it.seq == transformed.seq } ?: transformed
        check(persistItem(normalized)) { "Channel inbox could not persist dispatch state" }
        val keptSeqs = retained.mapTo(HashSet()) { it.seq }
        deleteDropped(updated.filterNot { it.seq in keptSeqs })
        messages.value = retained
        return true
    }

    private fun isEventClaimed(source: ChannelInbox.Source): Boolean {
        if (source.eventId.isBlank() || source.channelId.isBlank() || file == null) return false
        val deduplicator = webhookEventDeduplicator
            ?: error("Webhook event deduplication is unavailable: ${webhookEventDeduplicationFailure?.message}")
        return deduplicator.contains(source.platform, source.channelId, source.eventId)
    }

    private fun claimEvent(source: ChannelInbox.Source): Boolean {
        check(!ProcessWriteGate.restoring) { "备份恢复进行中，暂不接受渠道事件" }
        if (source.eventId.isBlank() || source.channelId.isBlank() || file == null) return true
        val deduplicator = webhookEventDeduplicator
            ?: error("Webhook event deduplication is unavailable: ${webhookEventDeduplicationFailure?.message}")
        return deduplicator.claim(source.platform, source.channelId, source.eventId)
    }

    private fun pendingCountLocked(): Int = messages.value.count { it.isDispatchActive() }

    private fun ChannelInbox.Inbound.isDispatchActive(): Boolean = dispatchState == ChannelInbox.DispatchState.PENDING ||
        dispatchState == ChannelInbox.DispatchState.PROCESSING

    private fun ChannelInbox.Inbound.matches(source: ChannelInbox.Source): Boolean = platform == source.platform &&
        sourceChannelId == source.channelId &&
        sourceEventId == source.eventId

    private fun normalize(items: List<ChannelInbox.Inbound>): List<ChannelInbox.Inbound> {
        // 分片文件名带 seq，缺失（seq==0，来自旧数据或异常输入）时按当前顺序补齐。
        items.forEach { if (it.seq > maxSeq) maxSeq = it.seq }
        val withSeq = items.map { item -> if (item.seq > 0L) item else item.copy(seq = ++maxSeq) }
        val assigned = withSeq
        val migrated = assigned.map { item ->
            when {
                item.isDispatchActive() && item.dispatchId.isBlank() ->
                    item.copy(dispatchId = UUID.randomUUID().toString())
                // 终态条目的图片 base64 必须清掉。
                //
                // 它在这个时刻已经随轮次写进了 ChannelConversationStore（自动回复构造上下文时
                // 就复制了一份），留在收件箱里没有任何消费者 —— 而收件箱是**整表落盘**的：
                // 一条历史图片会让之后每次 record / claim / 完成派发都重新搬运这份字节。
                // 带图的历史攒到十几条，单次落盘就是几 MB，全部持对象锁。
                //
                // BLOCKED 是「人工复核」终态（还留着 checkpoint 与已准备回复供诊断），图片同样保留。
                // 只清 base64、不动 mediaPath：后者是磁盘文件引用，ChannelMediaCleaner 靠它判断哪些文件仍被引用。
                !item.isDispatchActive() &&
                    item.dispatchState != ChannelInbox.DispatchState.BLOCKED &&
                    item.mediaBase64.isNotEmpty() ->
                    item.copy(mediaBase64 = "")
                else -> item
            }
        }
        val active = migrated.filter { it.isDispatchActive() }
        val historySlots = (MAX_ITEMS - active.size).coerceAtLeast(0)
        return active + migrated.filterNot { it.isDispatchActive() }.take(historySlots)
    }

    private fun retain(items: List<ChannelInbox.Inbound>): List<ChannelInbox.Inbound> = normalize(items)

    // ── 分片持久化（2026-11-01 性能整改）────────────────────────
    //
    // 旧形态是单个 channel_inbox.json：每次状态迁移都整表重写。渠道一条消息的生命周期里，
    // 领取 / 存草稿 / **每次工具调用的断点（执行前后各一次）** / 完成，全是整表重写 ——
    // 队列越大、工具调用越多，写放大越夸张，而且全部持对象锁。
    //
    // 现在一条队列项一个文件：channel_inbox/<seq 12 位>-<key 指纹>.json。
    // 文件名带序号 → 恢复时按文件名排序即还原入队顺序，不需要额外索引文件。
    //
    // 语义变化（有意、且更安全）：以前一次写覆盖整队，现在每个条目各自原子写。
    // 条目之间本就没有跨条目不变量，所以崩溃后的"部分写入"仍是合法状态：
    // 每条要么是旧状态、要么是新状态，不会出现半条。

    private fun restoreFromShards(dir: File) {
        val loaded = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            // 入队顺序：record 把新事件放在列表**最前**（listOf(item) + 旧列表），
            // 所以 seq 越大越靠前 —— 恢复时必须降序，否则重启后领取顺序会反过来。
            .sortedByDescending { it.name }
            .mapNotNull { shard ->
                runCatching {
                    AppJson.decodeFromString(ChannelInbox.Inbound.serializer(), shard.readText())
                }.onFailure { error ->
                    Logger.w(TAG, "分片读取失败,跳过 ${shard.name}: ${error.message}")
                    AtomicFileStore.quarantine(shard, "inbox_shard_parse")
                }.getOrNull()
            }
        maxSeq = loaded.maxOfOrNull { it.seq } ?: 0L
        val normalized = normalize(loaded)
        messages.value = normalized
        // 规范化可能裁掉条目（超上限），把对应分片删掉，避免下次又被读进来。
        val kept = normalized.mapTo(HashSet()) { shardKeyOf(it) }
        loaded.filterNot { shardKeyOf(it) in kept }.forEach { deleteItem(it) }
    }

    private fun migrateFromLegacyFile(inboxFile: File) {
        runCatching {
            AppJson.decodeFromString(
                ListSerializer(ChannelInbox.Inbound.serializer()),
                inboxFile.readText(),
            )
        }.onSuccess { restored ->
            // 旧数组**顺序即内存顺序**（新事件在前），所以下标 0 要拿最大的 seq，
            // 这样按 seq 降序恢复出来的顺序与旧实现完全一致。
            val withSeq = restored.mapIndexed { index, item -> item.copy(seq = (restored.size - index).toLong()) }
            maxSeq = withSeq.maxOfOrNull { it.seq } ?: 0L
            val normalized = normalize(withSeq)
            messages.value = normalized
            val startedAt = System.currentTimeMillis()
            val allWritten = normalized.all { persistItem(it) }
            val cost = System.currentTimeMillis() - startedAt
            if (allWritten && inboxFile.renameTo(File(inboxFile.parentFile, inboxFile.name + ".migrated"))) {
                Logger.i(TAG, "收件箱已迁移为分片存储(${normalized.size} 条, 耗时 ${cost}ms)")
            }
        }.onFailure { error ->
            Logger.w(TAG, "收件箱恢复失败,隔离坏文件: ${error.message}")
            AtomicFileStore.quarantine(inboxFile, "inbox_restore_failed")
        }
    }

    /** 分片名里的稳定部分：dispatchId 优先；历史条目可能为空 → 退回内容指纹。 */
    private fun shardKeyOf(item: ChannelInbox.Inbound): String =
        item.dispatchId.ifBlank { "${item.platform}|${item.from}|${item.timestamp}|${item.summary.take(64)}" }

    private fun shardFile(item: ChannelInbox.Inbound): File? {
        val dir = shardDir ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(shardKeyOf(item).toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(dir, "%012d-%s.json".format(item.seq, digest))
    }

    private fun persistItem(item: ChannelInbox.Inbound): Boolean {
        if (ProcessWriteGate.restoring) return false
        val target = shardFile(item) ?: return true
        return runCatching {
            AtomicFileStore.writeText(target, AppJson.encodeToString(ChannelInbox.Inbound.serializer(), item))
            true
        }.onFailure { error -> Logger.w(TAG, "收件箱分片写入失败: ${error.message}") }.getOrDefault(false)
    }

    private fun deleteItem(item: ChannelInbox.Inbound) {
        val target = shardFile(item) ?: return
        runCatching {
            if (target.exists() && !target.delete()) Logger.w(TAG, "分片删除失败: ${target.name}")
        }.onFailure { error -> Logger.w(TAG, "分片删除异常: ${error.message}") }
    }

    /** 删除 [dropped] 里所有条目的分片（裁剪 / 清空历史用）。 */
    private fun deleteDropped(dropped: List<ChannelInbox.Inbound>) {
        dropped.forEach { deleteItem(it) }
    }

    private fun notifyPendingListener() {
        runCatching { pendingListener?.invoke() }
            .onFailure { error -> Logger.w(TAG, "唤醒入站派发失败: ${error.message}") }
    }

    private fun retryDelayMillis(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 8)
        return (RETRY_BASE_DELAY_MS * multiplier).coerceAtMost(RETRY_MAX_DELAY_MS)
    }

    private companion object {
        const val TAG = "ChannelInbox"
        const val MAX_ITEMS = 100
        const val RETRY_BASE_DELAY_MS = 1_000L
        const val RETRY_MAX_DELAY_MS = 5 * 60 * 1000L
    }
}

internal class ChannelInboxCapacityException : IllegalStateException(
    "Channel inbox pending capacity reached; refusing event",
)
