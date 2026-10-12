package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.ProcessWriteGate
import io.zer0.muse.data.AtomicFileStore
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
    )

    private const val MAX_ITEMS = 100
    internal const val MAX_PENDING_ITEMS = MAX_ITEMS
    private const val MAX_RAW_LENGTH = 4000
    private const val INBOX_FILE = "channel_inbox.json"
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
            ?.take(2000)
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
    private var file: File? = null
    private var webhookEventDeduplicator: WebhookEventDeduplicator? = null
    private var webhookEventDeduplicationFailure: Throwable? = null

    @Volatile
    var onInbound: ((ChannelInbox.Inbound) -> Unit)? = null

    @Volatile
    private var pendingListener: (() -> Unit)? = null

    @Synchronized
    fun attach(inboxFile: File, ledgerFile: File) {
        if (file != null) return
        file = inboxFile
        if (inboxFile.exists()) {
            runCatching {
                AppJson.decodeFromString(
                    ListSerializer(ChannelInbox.Inbound.serializer()),
                    inboxFile.readText(),
                )
            }.onSuccess { restored ->
                val normalized = normalize(restored)
                messages.value = normalized
                if (normalized != restored) {
                    check(persist(normalized)) { "Channel inbox could not persist restored state" }
                }
            }.onFailure { error ->
                // v2.6.0 (AUD-CHANNEL-F4): 恢复失败时隔离坏文件,而不是继续用空列表 ——
                // 否则后续 record 会用新内容覆盖坏文件,原始现场丢失、无法诊断。
                // 复用项目已有的 AtomicFileStore.quarantine 惯例(同 DataStore 损坏备份)。
                Logger.w(TAG, "收件箱恢复失败,隔离坏文件: ${error.message}")
                io.zer0.muse.data.AtomicFileStore.quarantine(inboxFile, "inbox_restore_failed")
            }
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
                    check(persist(updated)) { "Channel inbox could not refresh the inbound reply context" }
                    messages.value = updated
                }
                ledgerFailure = runCatching { claimEvent(source) }.exceptionOrNull()
                false
            } else if (isEventClaimed(source)) {
                false
            } else {
                if (pendingCountLocked() >= ChannelInbox.MAX_PENDING_ITEMS) throw ChannelInboxCapacityException()
                val retained = retain(listOf(item) + messages.value)
                check(persist(retained)) { "Channel inbox could not persist the inbound event" }
                messages.value = retained
                observerItem = item
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
            check(persist(recovered)) { "Channel inbox could not recover interrupted dispatches" }
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
        check(persist(updated)) { "Channel inbox could not persist dispatch claim" }
        messages.value = updated
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
        if (persist(retained)) messages.value = retained
    }

    private fun updateProcessing(dispatchId: String, transform: (ChannelInbox.Inbound) -> ChannelInbox.Inbound): Boolean {
        val current = messages.value
        val index = current.indexOfFirst {
            it.dispatchId == dispatchId && it.dispatchState == ChannelInbox.DispatchState.PROCESSING
        }
        if (index < 0) return false
        val updated = current.toMutableList().also { it[index] = transform(it[index]) }
        val retained = retain(updated)
        check(persist(retained)) { "Channel inbox could not persist dispatch state" }
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
        val migrated = items.map { item ->
            if (item.isDispatchActive() && item.dispatchId.isBlank()) {
                item.copy(dispatchId = UUID.randomUUID().toString())
            } else {
                item
            }
        }
        val active = migrated.filter { it.isDispatchActive() }
        val historySlots = (MAX_ITEMS - active.size).coerceAtLeast(0)
        return active + migrated.filterNot { it.isDispatchActive() }.take(historySlots)
    }

    private fun retain(items: List<ChannelInbox.Inbound>): List<ChannelInbox.Inbound> = normalize(items)

    private fun persist(items: List<ChannelInbox.Inbound>): Boolean {
        if (ProcessWriteGate.restoring) return false
        val target = file ?: return true
        return runCatching {
            AtomicFileStore.writeText(
                target,
                AppJson.encodeToString(ListSerializer(ChannelInbox.Inbound.serializer()), items),
            )
            true
        }.onFailure { error ->
            Logger.w(TAG, "收件箱持久化失败: ${error.message}")
        }.getOrDefault(false)
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
