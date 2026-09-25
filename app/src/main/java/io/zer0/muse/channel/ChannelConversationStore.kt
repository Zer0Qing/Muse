package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * v2.0.1: 渠道对话存储 — 按 (渠道 id, 来源) 保存多轮对话,供自动回复构造上下文。
 *
 * 与 [ChannelInbox] 同构(全局单例 + 原子 JSON 持久化),但存的是"对话记录":
 * 用户与助手的往返轮次 + 滚动摘要(用于上下文自动压缩)。
 *
 * 结构: filesDir/channel_conversations.json
 *   { "<channelId>|<from>": { "summary": "...", "turns": [...], "updatedAt": 123 } }
 */
object ChannelConversationStore {

    /** 单条对话轮次。role: "user" / "assistant"。 */
    @Serializable
    data class Turn(
        val role: String,
        val text: String,
        val at: Long = System.currentTimeMillis(),
        /** v2.0.1: 媒体类型("image";空 = 纯文本)。 */
        val mediaKind: String = "",
        /** v2.0.1: 图片 base64(压缩后)。 */
        val mediaBase64: String = "",
        /** v2.0.1: 视觉降级描述缓存(模型不支持视觉时生成,避免重复分析)。 */
        val mediaDescription: String = "",
    )

    /** 单个对话:滚动摘要 + 最近轮次。 */
    @Serializable
    data class Conversation(
        val summary: String = "",
        val turns: List<Turn> = emptyList(),
        val updatedAt: Long = 0L,
    )

    private const val TAG = "ChannelConversations"

    /** 最多保留的对话数(超量时淘汰最久未更新的)。 */
    private const val MAX_CONVERSATIONS = 50

    /** 单对话轮次硬上限(压缩逻辑之上再加一层安全带)。 */
    private const val MAX_TURNS = 200

    private val _conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = _conversations.asStateFlow()

    private var file: File? = null

    /** 绑定应用上下文并恢复历史(幂等)。 */
    @Synchronized
    fun attach(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, "channel_conversations.json")
        file = target
        if (target.exists()) {
            runCatching {
                AppJson.decodeFromString(
                    MapSerializer(String.serializer(), Conversation.serializer()),
                    target.readText(),
                )
            }.onSuccess { _conversations.value = it }
                .onFailure { e -> Logger.w(TAG, "对话历史恢复失败: ${e.message}") }
        }
    }

    /** 对话键:渠道 id + 来源(联系人 id)。 */
    fun key(channelId: String, from: String): String = "$channelId|$from"

    /** 追加一条轮次(同步写内存 + 落盘;渠道消息低频,直接调用即可)。 */
    @Synchronized
    fun append(
        channelId: String,
        from: String,
        role: String,
        text: String,
        mediaKind: String = "",
        mediaBase64: String = "",
    ) {
        val key = key(channelId, from)
        val current = _conversations.value[key] ?: Conversation()
        val turns = (
            current.turns + Turn(
                role = role,
                text = text,
                mediaKind = mediaKind,
                mediaBase64 = mediaBase64,
            )
            ).takeLast(MAX_TURNS)
        val updated = current.copy(turns = turns, updatedAt = System.currentTimeMillis())
        val map = (_conversations.value + (key to updated))
            .toList()
            .sortedByDescending { (_, conversation) -> conversation.updatedAt }
            .take(MAX_CONVERSATIONS)
            .toMap()
        _conversations.value = map
        persist(map)
    }

    /** 读取对话(不存在时返回 null)。 */
    fun conversation(channelId: String, from: String): Conversation? =
        _conversations.value[key(channelId, from)]

    /** 覆盖写入(压缩后回写摘要 + 剩余轮次)。 */
    @Synchronized
    fun replace(channelId: String, from: String, conversation: Conversation) {
        val key = key(channelId, from)
        val map = _conversations.value + (key to conversation)
        _conversations.value = map
        persist(map)
    }

    /** 清空单个对话。 */
    @Synchronized
    fun clear(channelId: String, from: String) {
        val map = _conversations.value - key(channelId, from)
        _conversations.value = map
        persist(map)
    }

    /** v2.0.1: 写入图片轮次的视觉降级描述缓存(按时间戳定位,最多命中一条)。 */
    @Synchronized
    fun updateTurnDescription(channelId: String, from: String, turnAt: Long, description: String) {
        val key = key(channelId, from)
        val conversation = _conversations.value[key] ?: return
        val index = conversation.turns.indexOfFirst { it.at == turnAt && it.mediaKind == "image" }
        if (index < 0) return
        val turns = conversation.turns.toMutableList().also {
            it[index] = it[index].copy(mediaDescription = description)
        }
        val map = _conversations.value + (key to conversation.copy(turns = turns))
        _conversations.value = map
        persist(map)
    }

    private fun persist(items: Map<String, Conversation>) {
        val target = file ?: return
        runCatching {
            AtomicFileStore.writeText(
                target,
                AppJson.encodeToString(
                    MapSerializer(String.serializer(), Conversation.serializer()),
                    items,
                ),
            )
        }.onFailure { e -> Logger.w(TAG, "对话历史持久化失败: ${e.message}") }
    }
}
