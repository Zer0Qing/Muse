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
        /** v2.x (B4): 非图片媒体(视频/文件)落盘的本地路径。 */
        val mediaPath: String = "",
        /** v2.0.1: 视觉降级描述缓存(模型不支持视觉时生成,避免重复分析)。 */
        val mediaDescription: String = "",
        /** 自动回复派发 ID;重试时用于避免重复追加同一用户/助手轮次。 */
        val dispatchId: String = "",
    )

    /** 单个对话:滚动摘要 + 最近轮次。 */
    @Serializable
    data class Conversation(
        val summary: String = "",
        val turns: List<Turn> = emptyList(),
        val updatedAt: Long = 0L,
        /** 保留近期派发轮次身份,即使摘要压缩已经从 turns 中移除原始轮次。 */
        val recentDispatchTurnIds: List<String> = emptyList(),
    )

    private const val TAG = "ChannelConversations"

    /** 最多保留的对话数(超量时淘汰最久未更新的)。 */
    private const val MAX_CONVERSATIONS = 50

    /** 单对话轮次硬上限(压缩逻辑之上再加一层安全带)。 */
    private const val MAX_TURNS = 200
    private const val MAX_DISPATCH_TURN_IDS = 1_000

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
                .onFailure { e ->
                    // v2.6.0 (AUD-CHANNEL-F4): 恢复失败时隔离坏文件,避免被后续写入覆盖。
                    Logger.w(TAG, "对话历史恢复失败,隔离坏文件: ${e.message}")
                    io.zer0.muse.data.AtomicFileStore.quarantine(target, "conversation_restore_failed")
                }
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
        // v2.x (B4): 视频/文件落盘路径;默认空保持向后兼容。
        mediaPath: String = "",
    ) {
        appendTurn(
            channelId,
            from,
            Turn(
                role = role,
                text = text,
                mediaKind = mediaKind,
                mediaBase64 = mediaBase64,
                mediaPath = mediaPath,
            ),
        )
    }

    @Synchronized
    fun appendTurn(channelId: String, from: String, turn: Turn) {
        val key = key(channelId, from)
        val current = _conversations.value[key] ?: Conversation()
        val dispatchTurnKey = channelDispatchTurnIdentity(turn.dispatchId, turn.role)
        if (dispatchTurnKey != null && dispatchTurnKey in current.recentDispatchTurnIds) return
        val turns = (current.turns + turn).takeLast(MAX_TURNS)
        val dispatchTurnIds = dispatchTurnKey
            ?.let { (current.recentDispatchTurnIds + it).takeLast(MAX_DISPATCH_TURN_IDS) }
            ?: current.recentDispatchTurnIds
        val updated = current.copy(
            turns = turns,
            updatedAt = System.currentTimeMillis(),
            recentDispatchTurnIds = dispatchTurnIds,
        )
        val map = (_conversations.value + (key to updated))
            .toList()
            .sortedByDescending { (_, conversation) -> conversation.updatedAt }
            .take(MAX_CONVERSATIONS)
            .toMap()
        check(persist(map)) { "Channel conversation could not persist an appended turn" }
        _conversations.value = map
    }

    /** 读取对话(不存在时返回 null)。 */
    fun conversation(channelId: String, from: String): Conversation? = _conversations.value[key(channelId, from)]

    /**
     * v2.3.2: 压缩结果**条件回写** —— 取代原来的 `replace()` 盲覆盖。
     *
     * 原实现的问题:`maybeCompress` 是"读快照 → 调 LLM(最长数十秒)→ 整份覆盖回写",
     * 期间 [append] 进来的新轮次会被整份覆盖直接丢掉(用户刚说的话从渠道上下文里消失,
     * 表现为机器人"忘了上一句")。
     *
     * 本方法的语义:
     *  - 仅当对话摘要仍是压缩前读到的那份([snapshotSummary])才写入 —— 否则说明另一次压缩
     *    已抢先更新摘要,本次结果作废(下次触发时重试);
     *  - 仅摘除 [drainedTurns] 这一段(按边界轮次身份校验),压缩期间新 append 的轮次原样保留;
     *  - 边界轮次对不上(列表被 clear/裁剪重建)时放弃写入,宁可下轮重压也不误删新轮次。
     *
     * @return true = 已写入;false = 放弃(调用方仅记日志)
     */
    @Synchronized
    fun applyCompression(channelId: String, from: String, snapshotSummary: String, drainedTurns: List<Turn>, summary: String): Boolean {
        val key = key(channelId, from)
        val current = _conversations.value[key]
        val applicable = compressionApplicable(current, snapshotSummary, drainedTurns)
        if (applicable && current != null) {
            val updated =
                current.copy(
                    summary = summary,
                    turns = current.turns.drop(drainedTurns.size),
                    updatedAt = System.currentTimeMillis(),
                )
            val map = _conversations.value + (key to updated)
            if (!persist(map)) return false
            _conversations.value = map
        }
        return applicable
    }

    /**
     * 压缩结果是否仍可写回。三条前提(任一不满足即放弃,宁可下轮重压也不误删新轮次):
     *  1. 对话还在;
     *  2. 摘要仍是压缩前读到的那份 —— 否则说明另一次压缩已抢先写入;
     *  3. 边界轮次仍是同一条(时间戳 + 文本)—— 否则列表被 clear/裁剪重建过。
     */
    private fun compressionApplicable(current: Conversation?, snapshotSummary: String, drainedTurns: List<Turn>): Boolean {
        val boundary = drainedTurns.lastOrNull()
        val currentBoundary = current?.turns?.getOrNull(drainedTurns.size - 1)
        val sameSummary = current != null && current.summary == snapshotSummary
        val sameBoundary =
            boundary != null && currentBoundary != null &&
                currentBoundary.at == boundary.at && currentBoundary.text == boundary.text
        return sameSummary && sameBoundary
    }

    /** 清空单个对话。 */
    @Synchronized
    fun clear(channelId: String, from: String) {
        val map = _conversations.value - key(channelId, from)
        if (persist(map)) _conversations.value = map
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
        if (persist(map)) _conversations.value = map
    }

    private fun persist(items: Map<String, Conversation>): Boolean {
        if (ProcessWriteGate.restoring) return false
        val target = file ?: return true
        return runCatching {
            AtomicFileStore.writeText(
                target,
                AppJson.encodeToString(
                    MapSerializer(String.serializer(), Conversation.serializer()),
                    items,
                ),
            )
            true
        }.onFailure { e -> Logger.w(TAG, "对话历史持久化失败: ${e.message}") }
            .getOrDefault(false)
    }
}

private fun channelDispatchTurnIdentity(dispatchId: String, role: String): String? =
    dispatchId.takeIf { it.isNotBlank() }?.let { "$it\u0000$role" }
