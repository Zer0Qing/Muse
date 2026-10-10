package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.transformer.ContextCompressTransformer
import kotlin.uuid.Uuid

/**
 * v2.x 重构 S2: [ChatCompressionCoordinator] 访问 ChatViewModel 的窄桥接口。
 *
 * 压缩编排需要访问 VM 的检查点读取器、压缩器、状态与少量回调。用显式接口
 * 声明这些访问,而不是把 ViewModel 整体传入 —— 依赖面可见、可测、可替身。
 *
 * 迁移性质:纯抽取。VM 侧以匿名对象实现,全部为**直接转发**,无逻辑。
 */
internal interface ChatCompressionHostBridge {

    // ── 依赖 ───────────────────────────────────────────────────────────

    val stateStore: ChatStateStore
    val appContext: android.content.Context
    val settings: SettingsRepository
    val sessionRepository: SessionRepository
    val checkpointReader: io.zer0.memory.summary.ContextCheckpointReader
    val contextCompressTransformer: ContextCompressTransformer
    val memoryTicker: io.zer0.memory.ticker.MemoryTicker
    val coroutineScope: kotlinx.coroutines.CoroutineScope
    val accessor: ChatStateAccessor

    // ── 状态(原 VM 的可变水位线)───────────────────────────────────────

    /** 原 `checkpointCoveredSessionId`(读写)。 */
    var checkpointCoveredSessionId: String?

    /** 原 `checkpointCoveredIds`(读写)。 */
    var checkpointCoveredIds: Set<String>

    // ── 原 VM 方法转发 ─────────────────────────────────────────────────

    fun displayedSessionId(): String?
    fun reportError(message: String)
    fun shouldAutoCompress(currentTokens: Int, maxTokens: Int): Boolean
    suspend fun updateContextTokenCount()
    suspend fun refreshCheckpointCoveredIds(sessionId: String)
    suspend fun refreshContextInfo()

    /** 原 `newlyCoveredCount`(已下沉 companion 的纯函数)。 */
    fun newlyCoveredCount(messages: List<UIMessage>, coveredIds: Set<String>, previousBoundaryId: String?): Int

    /** 原 `warmupActive` 判定。 */
    fun isWarmupActive(): Boolean

    /** 原 `UIMessage.id` → Uuid 的会话消息查询(取 seq)。 */
    suspend fun messageSeqOrZero(messageId: String): Long

    /** 压缩期间的会话守卫:当前展示的会话是否仍是 [sessionId]。 */
    fun isSessionDisplayed(sessionId: String): Boolean
}

/** 桥接用的消息 id 类型别名,避免各处重复写全限定名。 */
internal typealias CompMessageId = Uuid
