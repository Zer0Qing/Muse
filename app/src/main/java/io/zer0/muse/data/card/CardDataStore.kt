package io.zer0.muse.data.card

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * v2.0: 卡片数据存储 — 卡片数据绑定(cardId → JSON 数据)。
 *
 * 工具调用(show_card / update_card_data)写入结构化数据;
 * 卡片渲染侧通过一次性数据获取读取:
 * 卡片脚本调用 `window.muse.getData(cardId)` → [MuseCardBridge] → 本存储。
 *
 * 持久化到 filesDir/card_data.json(原子写;仅保留最近 [MAX_ITEMS] 条,
 * 单条数据超过 [MAX_DATA_LENGTH] 截断,防止异常大对象拖垮渲染)。
 */
class CardDataStore(context: Context) {

    private val file = File(context.filesDir, "card_data.json")
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    private val _data = MutableStateFlow<Map<String, String>>(emptyMap())

    /** cardId → JSON 文本(最新)。 */
    val data: StateFlow<Map<String, String>> = _data.asStateFlow()

    init {
        // 小文件同步读取(与 ChannelInbox.attach 同模式);损坏时隔离由 AtomicFileStore 语义兜底。
        runCatching {
            if (file.exists()) {
                _data.value = AppJson.decodeFromString(serializer, file.readText())
            }
        }.onFailure { e -> Logger.w(TAG, "卡片数据恢复失败: ${e.message}") }
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
        _data.value = bounded
        persist(bounded)
    }

    /** 读取一条卡片数据(JSON 文本);不存在返回 null。 */
    fun get(cardId: String): String? = _data.value[cardId]

    /** 删除一条卡片数据。 */
    fun remove(cardId: String) {
        if (!_data.value.containsKey(cardId)) return
        val updated = _data.value - cardId
        _data.value = updated
        persist(updated)
    }

    private fun persist(map: Map<String, String>) {
        runCatching {
            AtomicFileStore.writeText(file, AppJson.encodeToString(serializer, map))
        }.onFailure { e -> Logger.w(TAG, "卡片数据持久化失败: ${e.message}") }
    }

    companion object {
        private const val TAG = "CardDataStore"

        /** 最多保留的卡片数据条数。 */
        private const val MAX_ITEMS = 50

        /** 单条数据长度上限(字符)。 */
        private const val MAX_DATA_LENGTH = 64_000
    }
}
