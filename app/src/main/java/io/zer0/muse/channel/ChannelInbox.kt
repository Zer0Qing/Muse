package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * v1.0.92: 渠道入站收件箱 — 记录外部 IM 平台推送进来的消息(webhook 接收侧)。
 *
 * 全局单例(静态对象):WebServer 的 webhook 路由写入,UI 读取展示。
 * 仅保留最近 [MAX_ITEMS] 条;持久化到 filesDir/channel_inbox.json(原子写)。
 * 收件箱内容会经 PII 遮蔽后入库,降低敏感信息残留。
 */
object ChannelInbox {

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
    )

    private const val TAG = "ChannelInbox"
    private const val MAX_ITEMS = 100
    private const val MAX_RAW_LENGTH = 4000

    private val _messages = MutableStateFlow<List<Inbound>>(emptyList())
    val messages: StateFlow<List<Inbound>> = _messages.asStateFlow()

    private var file: File? = null

    /**
     * v2.0: 入站消息监听(自动回复等消费方注册)。
     * 在 [record] 主流程之后回调,实现方须自行切换到协程作用域,不得阻塞调用方。
     */
    @Volatile
    var onInbound: ((Inbound) -> Unit)? = null

    /** 绑定应用上下文并恢复历史记录(幂等;webhook 首次触发或 UI 进入时调用)。 */
    @Synchronized
    fun attach(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, "channel_inbox.json")
        file = target
        if (target.exists()) {
            runCatching {
                AppJson.decodeFromString(ListSerializer(Inbound.serializer()), target.readText())
            }.onSuccess { _messages.value = it.take(MAX_ITEMS) }
                .onFailure { e -> Logger.w(TAG, "收件箱恢复失败: ${e.message}") }
        }
    }

    /** 记录一条入站消息(summary 做 PII 遮蔽与截断)。 */
    fun record(
        platform: String,
        from: String,
        text: String?,
        rawPayload: String,
        mediaKind: String = "",
        mediaBase64: String = "",
    ) {
        val safeSummary = text
            ?.let { t ->
                runCatching { io.zer0.memory.pii.PiiGuard.scrub(t).cleaned }
                    .getOrDefault("")
            }
            ?.take(2000)
            .orEmpty()
        val item = Inbound(
            platform = platform,
            from = from,
            summary = safeSummary,
            raw = rawPayload.take(MAX_RAW_LENGTH),
            mediaKind = mediaKind,
            mediaBase64 = mediaBase64,
        )
        val updated = (listOf(item) + _messages.value).take(MAX_ITEMS)
        _messages.value = updated
        persist(updated)
        runCatching { onInbound?.invoke(item) }
            .onFailure { e -> Logger.w(TAG, "入站监听回调失败: ${e.message}") }
    }

    /** 清空收件箱。 */
    fun clear() {
        _messages.value = emptyList()
        persist(emptyList())
    }

    private fun persist(items: List<Inbound>) {
        val target = file ?: return
        runCatching {
            AtomicFileStore.writeText(
                target,
                AppJson.encodeToString(ListSerializer(Inbound.serializer()), items),
            )
        }.onFailure { e -> Logger.w(TAG, "收件箱持久化失败: ${e.message}") }
    }
}
