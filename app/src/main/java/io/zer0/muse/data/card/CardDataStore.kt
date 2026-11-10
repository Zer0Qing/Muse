package io.zer0.muse.data.card

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
import java.security.MessageDigest

/**
 * v2.0: 卡片数据存储 — 卡片数据绑定(cardId → JSON 数据)。
 *
 * 工具调用(show_card / update_card_data)写入结构化数据;
 * 卡片渲染侧通过一次性数据获取读取:
 * 卡片脚本调用 `window.muse.getData(cardId)` → [MuseCardBridge] → 本存储。
 *
 * ## 存储形态(2026-11-01 性能整改)
 *
 * 旧形态是单文件全量 `filesDir/card_data.json`：每次 `put`/`remove` 都把**整张表**
 * （最多 [MAX_ITEMS] 条、单条最多 [MAX_DATA_LENGTH] 字符）重新序列化 + 原子写 + fsync。
 * 模型连续更新卡片时（`update_card_data` 多次调用），每次都在重写其它卡片的数据，
 * 单次落盘量可到数 MB。
 *
 * 现形态是按卡片分片：`filesDir/card_data/<cardId 的 sha256>.json`，
 * 每个文件只装一条卡片数据，形如 `{"cardId":"...","data":"..."}`。
 * 于是单次写入量 = 这张卡自己的大小（≤ [MAX_DATA_LENGTH]），与库里有多少张卡片无关。
 *
 * 旧文件保留兼容：分片目录为空且旧 `card_data.json` 存在时，读旧文件 → 写分片 →
 * 旧文件改名 `.migrated`。旧文件仍在备份白名单里，迁移失败的机器不会丢数据。
 */
class CardDataStore(context: Context) {

    private val filesDir: File = context.filesDir
    private val legacyFile = File(filesDir, LEGACY_FILE_NAME)
    private val shardDir = File(filesDir, SHARD_DIR_NAME)

    private val serializer = MapSerializer(String.serializer(), String.serializer())

    private val _data = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 恢复窗口内被删除的 cardId（P28 引入异步恢复后的补丁）。
     *
     * 异步恢复是「先读盘拿到快照，再合并进内存」。如果这中间用户删了一张卡，
     * `remove()` 已经把分片删了、内存也移除了，但恢复线程手里的快照还带着它 ——
     * 直接 `restored + current` 会把它**复活**。这里记下窗口内删掉的 id，合并时剔除。
     *
     * 恢复结束后清空，不会无限增长。
     */
    private val removedDuringRestore = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var restoreFinished = false

    /** cardId → JSON 文本(最新)。 */
    val data: StateFlow<Map<String, String>> = _data.asStateFlow()

    init {
        // 恢复/迁移放后台线程（2026-11-01 性能整改 P28）。
        //
        // 原来在构造函数里同步做。设备实测：旧格式 **50 张卡片迁移耗时 216 ms**，
        // 而 CardDataStore 是 Koin 单例、由 ChatViewModel 首次解析时创建 —— 也就是落在**主线程**。
        // 卡片越多越久（每张一次原子写，各带一次 fsync），重度用户在升级后首启动会有可见卡顿。
        //
        // 恢复完成前 `data` 为空：卡片会先渲染占位、随后随 StateFlow 填充。
        // 这是**一次性升级路径**，可接受。写入以内存中已有的为准（见 restored + current）。
        runCatching {
            Thread({ restoreInBackground() }, "card-data-restore").apply {
                isDaemon = true
                start()
            }
        }.onFailure { e -> Logger.w(TAG, "卡片数据恢复线程启动失败: ${e.message}") }
    }

    private fun restoreInBackground() {
        val restored = runCatching {
            when {
                shardDir.isDirectory && !shardDir.listFiles().isNullOrEmpty() -> restoreFromShards()
                legacyFile.exists() -> migrateFromLegacy()
                else -> emptyMap()
            }
        }.onFailure { e: Throwable ->
            Logger.w(TAG, "卡片数据恢复失败: ${e.message}")
        }.getOrElse { emptyMap() }
        if (restored.isEmpty()) {
            restoreFinished = true
            removedDuringRestore.clear()
            return
        }
        // 合并而不是覆盖：恢复期间写入的卡片优先；恢复期间被删除的卡片不复活。
        val survivors = restored.filterKeys { it !in removedDuringRestore }
        _data.value = survivors + _data.value
        restoreFinished = true
        removedDuringRestore.clear()
    }

    /** 写入/更新一条卡片数据(JSON 文本;超长截断,超出条数上限时淘汰最旧)。 */
    fun put(cardId: String, jsonText: String) {
        if (cardId.isBlank()) return
        val trimmed = jsonText.trim().take(MAX_DATA_LENGTH)
        val merged = _data.value + (cardId to trimmed)
        val bounded = if (merged.size <= MAX_ITEMS) {
            merged
        } else {
            merged.entries.drop(merged.size - MAX_ITEMS).associate { it.toPair() }
        }
        // 先落盘再改内存：只有这条卡片的数据被写，其它分片不动。
        if (!persistShard(cardId, trimmed)) return
        (merged.keys - bounded.keys).forEach { dropped -> deleteShard(dropped) }
        _data.value = bounded
    }

    /** 读取一条卡片数据(JSON 文本);不存在返回 null。 */
    fun get(cardId: String): String? = _data.value[cardId]

    /** 删除一条卡片数据。 */
    fun remove(cardId: String) {
        // 必须在改内存**之前**登记：恢复线程的过滤读的是这个集合，先登记才能保证
        // 「删了又被快照复活」不会发生（两种交错顺序下结果都正确）。
        if (!restoreFinished) removedDuringRestore.add(cardId)
        if (!_data.value.containsKey(cardId)) return
        deleteShard(cardId)
        _data.value = _data.value - cardId
    }

    // ── 分片读写 ────────────────────────────────────────────────

    private fun restoreFromShards(): Map<String, String> {
        val restored = LinkedHashMap<String, String>()
        shardDir.listFiles().orEmpty().forEach { shard ->
            if (!shard.isFile || shard.name.endsWith(".tmp", ignoreCase = true)) return@forEach
            runCatching { shard.readText() }
                .onSuccess { text ->
                    val decoded = decodeShard(text)
                    if (decoded != null) {
                        restored[decoded.first] = decoded.second
                    } else {
                        // 解析失败必须留痕：静默跳过等于"这张卡片的数据无声消失"，
                        // 出问题时连是哪一片坏的都查不到。
                        Logger.w(TAG, "卡片分片解析失败,跳过 ${shard.name}")
                        io.zer0.muse.data.AtomicFileStore.quarantine(shard, "card_shard_parse")
                    }
                }
                .onFailure { e -> Logger.w(TAG, "卡片分片读取失败,跳过 ${shard.name}: ${e.message}") }
        }
        val bounded = restored.entries.toList().takeLast(MAX_ITEMS)
        return bounded.associate { entry -> entry.key to entry.value }
    }

    /** @return 迁移出的数据（供后台线程合并进 `_data`）；失败返回空表。 */
    /** @return 迁移出的数据（供后台线程合并进 `_data`）；失败返回空表。 */
    private fun migrateFromLegacy(): Map<String, String> {
        val restored = runCatching {
            AppJson.decodeFromString(serializer, legacyFile.readText())
        }.onFailure { e ->
            Logger.w(TAG, "卡片数据恢复失败: ${e.message}")
        }.getOrNull() ?: return emptyMap()
        val startedAt = System.currentTimeMillis()
        val migrated = restored.all { (cardId, json) -> persistShard(cardId, json) }
        val cost = System.currentTimeMillis() - startedAt
        // 迁移耗时记进日志：设备实测旧格式 50 张卡片约 216ms（每张一次原子写 + fsync），
        // 重装/升级后"首启动变慢"能不能归因到这一项，看这一行就够。
        if (migrated && legacyFile.renameTo(File(filesDir, LEGACY_FILE_NAME + ".migrated"))) {
            Logger.i(TAG, "旧单文件已迁移为分片存储(${restored.size} 条卡片数据, 耗时 ${cost}ms)")
        } else if (migrated) {
            Logger.w(TAG, "分片已写入但旧文件改名失败(${restored.size} 条, 耗时 ${cost}ms)")
        }
        return restored
    }
    private fun persistShard(cardId: String, jsonText: String): Boolean = runCatching {
        AtomicFileStore.writeText(shardFile(cardId), CardDataFiles.encode(cardId, jsonText))
        true
    }.onFailure { e -> Logger.w(TAG, "卡片数据持久化失败: ${e.message}") }.getOrDefault(false)

    private fun deleteShard(cardId: String) {
        runCatching {
            val target = shardFile(cardId)
            if (target.exists() && !target.delete()) {
                Logger.w(TAG, "卡片分片删除失败: ${target.name}")
            }
        }.onFailure { e -> Logger.w(TAG, "卡片分片删除异常: ${e.message}") }
    }

    private fun shardFile(cardId: String): File = File(shardDir, CardDataFiles.shardName(cardId))

    private fun decodeShard(text: String): Pair<String, String>? = CardDataFiles.decode(text)

    companion object {
        private const val TAG = "CardDataStore"

        /** 最多保留的卡片数据条数。 */
        private const val MAX_ITEMS = 50

        /** 单条数据长度上限(字符)。 */
        private const val MAX_DATA_LENGTH = 64_000

        /** 旧形态的单文件名（仅迁移时读取）。 */
        private const val LEGACY_FILE_NAME = "card_data.json"

        /** 分片目录名（备份白名单里也要有它，见 BackupBinaryFiles.allowedPrefixes）。 */
        private const val SHARD_DIR_NAME = "card_data"
    }
}

/**
 * 卡片数据分片的文件名与编解码。
 *
 * 抽出来是为了能在不构造 [CardDataStore]（不需要 Context）的前提下单测
 * 「同一 cardId 稳定同名」「往返一致」「非法内容不被当成空数据」。
 */
internal object CardDataFiles {

    /** 分片内容：cardId 一起存，文件名只是哈希，读回来才知道属于谁。 */
    @Serializable
    internal data class Envelope(
        val cardId: String,
        val data: String,
    )

    /** 文件名 = cardId 的 SHA-256 十六进制（cardId 是消息 id 或 `c_xxx`，含分隔符不奇怪）。 */
    fun shardName(cardId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cardId.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) } + ".json"
    }

    fun encode(cardId: String, data: String): String = AppJson.encodeToString(Envelope.serializer(), Envelope(cardId = cardId, data = data))

    /** 解码失败返回 null（调用方跳过该分片，不当成空数据写进内存）。 */
    fun decode(text: String): Pair<String, String>? = runCatching { AppJson.decodeFromString(Envelope.serializer(), text) }
        .getOrNull()
        ?.let { it.cardId to it.data }
}
