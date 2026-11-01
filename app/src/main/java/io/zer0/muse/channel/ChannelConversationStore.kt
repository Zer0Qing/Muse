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
 * ## 存储形态(2026-11-01 性能整改)
 *
 * 旧形态是**单文件全量**:`filesDir/channel_conversations.json` 装全部会话。
 * 每追加一条轮次都要把整张表重新序列化 + 原子写 + fsync —— 50 个会话、每个最多
 * 200 轮时，一次 append 就要编码上兆字节，而实际变化的只有其中一条会话。
 * 渠道每来一条消息至少 append 两次（用户轮 + 助手轮），全部持同一把对象锁。
 *
 * 现形态是**按会话分片**:`filesDir/channel_conversations/<key 的 sha256>.json`，
 * 每个文件只装一条会话，形如 `{"key":"<channelId>|<from>","conversation":{...}}`。
 * 于是 append 的落盘量 = 这条会话的大小（几十 KB 级），与其它会话无关；
 * 原子写仍然逐文件进行，结论是「每个会话随时都是完整可读的」，比整表重写更不容易出问题。
 *
 * 旧文件保留兼容：分片目录为空且旧文件存在时，读旧文件 → 写分片 → 把旧文件改名成
 * `.migrated`，失败也不影响使用（分片优先）。
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

    /** 旧形态的单文件名（仅迁移时读取）。 */
    private const val LEGACY_FILE_NAME = "channel_conversations.json"

    /** 分片目录名。 */
    private const val SHARD_DIR_NAME = "channel_conversations"

    /** 最多保留的对话数(超量时淘汰最久未更新的)。 */
    private const val MAX_CONVERSATIONS = 50

    /** 单对话轮次硬上限(压缩逻辑之上再加一层安全带)。 */
    private const val MAX_TURNS = 200
    private const val MAX_DISPATCH_TURN_IDS = 1_000

    private val _conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = _conversations.asStateFlow()

    /** 旧形态的单文件（仅用于迁移）。 */
    private var legacyFile: File? = null

    /** 分片目录：一个会话一个文件。 */
    private var shardDir: File? = null

    /** 已绑定的 filesDir；null 表示还没绑定。 */
    private var boundFilesDir: File? = null

    /**
     * 绑定应用上下文并恢复历史(幂等)。
     *
     * 以 filesDir 判定幂等而不是一个布尔标记：同一个应用只会用同一个 filesDir，
     * 但单测里每个用例的临时目录不同 —— 用布尔会把状态留在上一个目录上，
     * 于是「写进去了吗」这类断言读到的是别的目录。
     */
    @Synchronized
    fun attach(context: Context) {
        val filesDir = context.applicationContext.filesDir
        if (boundFilesDir?.absolutePath == filesDir.absolutePath) return
        boundFilesDir = filesDir
        val legacy = File(filesDir, LEGACY_FILE_NAME)
        val dir = File(filesDir, SHARD_DIR_NAME)
        legacyFile = legacy
        shardDir = dir
        when {
            // 分片优先：只要目录里已经有文件，就不再碰旧文件。
            dir.isDirectory && !dir.listFiles().isNullOrEmpty() -> restoreFromShards(dir)
            legacy.exists() -> migrateFromLegacy(legacy, dir)
            else -> Unit
        }
    }

    private fun restoreFromShards(dir: File) {
        val restored = LinkedHashMap<String, Conversation>()
        dir.listFiles().orEmpty().forEach { shard ->
            if (!shard.isFile || shard.name.endsWith(".tmp", ignoreCase = true)) return@forEach
            runCatching { shard.readText() }
                .onSuccess { text ->
                    val decoded = ChannelConversationFiles.decode(text)
                    if (decoded != null) restored[decoded.first] = decoded.second
                }
                .onFailure { e -> Logger.w(TAG, "分片读取失败,跳过 ${shard.name}: ${e.message}") }
        }
        _conversations.value = restored
            .entries
            .sortedByDescending { it.value.updatedAt }
            .take(MAX_CONVERSATIONS)
            .associate { it.key to it.value }
    }

    private fun migrateFromLegacy(target: File, dir: File) {
        runCatching {
            AppJson.decodeFromString(
                MapSerializer(String.serializer(), Conversation.serializer()),
                target.readText(),
            )
        }.onSuccess { restored ->
            _conversations.value = restored
            // 迁移失败不影响使用：分片写不进去时下一次 append 还会再写分片，
            // 旧文件继续留作兜底读取源（外部读不到分片就会回落到它）。
            val startedAt = System.currentTimeMillis()
            val migrated = restored.all { (key, conversation) ->
                runCatching {
                    AtomicFileStore.writeText(
                        shardFile(dir, key),
                        ChannelConversationFiles.encode(key, conversation),
                    )
                }.isSuccess
            }
            val cost = System.currentTimeMillis() - startedAt
            if (migrated && target.renameTo(File(target.parentFile, LEGACY_FILE_NAME + ".migrated"))) {
                Logger.i(TAG, "旧单文件已迁移为分片存储(${restored.size} 个会话, 耗时 ${cost}ms)")
            }
        }.onFailure { e ->
            // v2.6.0 (AUD-CHANNEL-F4): 恢复失败时隔离坏文件,避免被后续写入覆盖。
            Logger.w(TAG, "对话历史恢复失败,隔离坏文件: ${e.message}")
            io.zer0.muse.data.AtomicFileStore.quarantine(target, "conversation_restore_failed")
        }
    }

    private fun shardFile(dir: File, key: String): File = File(dir, ChannelConversationFiles.shardName(key))

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
        val merged = _conversations.value + (key to updated)
        val trimmed = merged.entries
            .sortedByDescending { (_, conversation) -> conversation.updatedAt }
            .take(MAX_CONVERSATIONS)
            .associate { it.key to it.value }
        val dropped = merged.keys - trimmed.keys
        // 先落盘再改内存（既有恢复契约）：只写这一条会话的分片。
        check(persistConversation(key, updated)) { "Channel conversation could not persist an appended turn" }
        // 被淘汰的会话删掉对应分片；删失败只影响磁盘占用，内存里已不再引用它。
        dropped.forEach { deleteConversation(it) }
        _conversations.value = trimmed
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
            if (!persistConversation(key, updated)) return false
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

    /** 清空单个对话。删分片成功才更新内存,保证界面与磁盘一致。 */
    @Synchronized
    fun clear(channelId: String, from: String) {
        val key = key(channelId, from)
        if (!deleteConversation(key)) return
        _conversations.value = _conversations.value - key
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
        val updated = conversation.copy(turns = turns)
        if (persistConversation(key, updated)) _conversations.value = _conversations.value + (key to updated)
    }

    /** 写一条会话的分片；返回 false 表示没写成功（调用方据此决定是否更新内存）。 */
    private fun persistConversation(key: String, conversation: Conversation): Boolean {
        if (ProcessWriteGate.restoring) return false
        val dir = shardDir ?: return true
        return runCatching {
            AtomicFileStore.writeText(
                shardFile(dir, key),
                ChannelConversationFiles.encode(key, conversation),
            )
            true
        }.onFailure { e -> Logger.w(TAG, "对话历史持久化失败: ${e.message}") }
            .getOrDefault(false)
    }

    /** 删除一条会话的分片；文件本来就不存在也算成功。 */
    private fun deleteConversation(key: String): Boolean {
        val dir = shardDir ?: return true
        return runCatching {
            val target = shardFile(dir, key)
            !target.exists() || target.delete()
        }.onFailure { e -> Logger.w(TAG, "对话分片删除失败: ${e.message}") }
            .getOrDefault(false)
    }
}

/**
 * 分片的文件名与编码。抽出来是为了能在不启动单例、不碰真实 filesDir 的前提下单测
 * 「同一 key 稳定同名」「编码/解码往返」「非法内容不被当成空会话」这三件事。
 */
internal object ChannelConversationFiles {

    /** 分片内容：key 一起存进去，文件名只是哈希，读回来才知道它属于谁。 */
    @Serializable
    internal data class Envelope(
        val key: String,
        val conversation: ChannelConversationStore.Conversation,
    )

    /** 文件名 = key 的 SHA-256 十六进制。key 里有 `|`、可能含路径分隔符与控制字符。 */
    fun shardName(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) } + ".json"
    }

    fun encode(key: String, conversation: ChannelConversationStore.Conversation): String =
        AppJson.encodeToString(Envelope.serializer(), Envelope(key, conversation))

    /** 解码失败返回 null（调用方跳过该分片，不当成空会话写入内存）。 */
    fun decode(text: String): Pair<String, ChannelConversationStore.Conversation>? =
        runCatching { AppJson.decodeFromString(Envelope.serializer(), text) }
            .getOrNull()
            ?.let { it.key to it.conversation }
}

private fun channelDispatchTurnIdentity(dispatchId: String, role: String): String? =
    dispatchId.takeIf { it.isNotBlank() }?.let { "$it\u0000$role" }
