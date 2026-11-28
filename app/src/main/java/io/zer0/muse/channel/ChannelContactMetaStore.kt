package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * 渠道联系人元数据存储：备注名、平台资料昵称、单会话 AI 接管暂停状态、单会话助手覆盖。
 *
 * 与 [ChannelConversationStore] 保持一致的 `"channelId|contactId"` 主键及 `AtomicFileStore` 落盘，
 * 让渠道中心、会话列表与人工接管聊天页共享同一份联系人信息，不再全屏展示原始 ID。
 */
object ChannelContactMetaStore {

    @Serializable
    data class ContactMeta(
        val alias: String = "",
        val displayName: String = "",
        val aiPaused: Boolean = false,
        val assistantIdOverride: String = "",
        val isGroup: Boolean = false,
        val updatedAt: Long = 0L,
    )

    private const val TAG = "ChannelContactMeta"
    private const val FILE_NAME = "channel_contact_meta.json"

    private val _metas = MutableStateFlow<Map<String, ContactMeta>>(emptyMap())
    val metas: StateFlow<Map<String, ContactMeta>> = _metas.asStateFlow()

    private var storeFile: File? = null
    private var boundFilesDir: File? = null

    @Synchronized
    fun attach(context: Context) {
        val filesDir = context.applicationContext.filesDir
        if (boundFilesDir?.absolutePath == filesDir.absolutePath) return
        boundFilesDir = filesDir
        val target = File(filesDir, FILE_NAME)
        storeFile = target
        _metas.value = loadFromFile(target)
    }

    fun key(channelId: String, contactId: String): String = "$channelId|$contactId"

    fun get(channelId: String, contactId: String): ContactMeta? = _metas.value[key(channelId, contactId)]

    fun isAiPaused(channelId: String, contactId: String): Boolean = get(channelId, contactId)?.aiPaused == true

    /**
     * 解析适合 UI 展示的联系人名称：优先本地备注 → 平台观察到的昵称 → 截断后的可读 ID。
     */
    fun resolvedName(channelId: String, contactId: String, groupPrefix: String = ""): String {
        val meta = get(channelId, contactId)
        val explicit = meta?.alias?.trim().orEmpty().ifBlank { meta?.displayName?.trim().orEmpty() }
        if (explicit.isNotBlank()) return explicit
        val isGroup = contactId.startsWith("group:") || meta?.isGroup == true
        val core = contactId.removePrefix("group:")
        val compact = if (core.length <= 20) core else core.take(8) + "…" + core.takeLast(6)
        return if (isGroup && groupPrefix.isNotBlank()) "$groupPrefix $compact" else compact
    }

    @Synchronized
    fun setAlias(channelId: String, contactId: String, alias: String) {
        updateEntry(channelId, contactId) { current ->
            current.copy(alias = alias.trim().take(48), updatedAt = System.currentTimeMillis())
        }
    }

    @Synchronized
    fun setAiPaused(channelId: String, contactId: String, paused: Boolean) {
        updateEntry(channelId, contactId) { current ->
            current.copy(aiPaused = paused, updatedAt = System.currentTimeMillis())
        }
    }

    @Synchronized
    fun setAssistantOverride(channelId: String, contactId: String, assistantId: String) {
        updateEntry(channelId, contactId) { current ->
            current.copy(assistantIdOverride = assistantId.trim(), updatedAt = System.currentTimeMillis())
        }
    }

    @Synchronized
    fun recordObservedProfile(channelId: String, contactId: String, displayName: String, isGroup: Boolean) {
        val cleaned = displayName.trim().take(64)
        val compositeKey = key(channelId, contactId)
        val prev = _metas.value[compositeKey]
        val nextName = cleaned.ifBlank { prev?.displayName.orEmpty() }
        val nextGroup = isGroup || (prev?.isGroup == true) || contactId.startsWith("group:")
        if (prev != null && prev.displayName == nextName && prev.isGroup == nextGroup) return
        updateEntry(channelId, contactId) { current ->
            current.copy(
                displayName = nextName,
                isGroup = nextGroup,
                updatedAt = if (current.updatedAt > 0L) current.updatedAt else System.currentTimeMillis(),
            )
        }
    }

    @Synchronized
    fun removeForChannel(channelId: String) {
        val prefix = "$channelId|"
        val filtered = _metas.value.filterKeys { !it.startsWith(prefix) }
        if (filtered.size == _metas.value.size) return
        _metas.value = filtered
        persistLocked()
    }

    private fun updateEntry(channelId: String, contactId: String, transform: (ContactMeta) -> ContactMeta) {
        if (channelId.isBlank() || contactId.isBlank()) return
        val k = key(channelId, contactId)
        val prev = _metas.value[k] ?: ContactMeta(isGroup = contactId.startsWith("group:"))
        val next = transform(prev)
        _metas.value = _metas.value + (k to next)
        persistLocked()
    }

    private fun loadFromFile(target: File): Map<String, ContactMeta> {
        if (!target.exists()) return emptyMap()
        return runCatching {
            AppJson.decodeFromString<Map<String, ContactMeta>>(target.readText())
        }.onFailure { e ->
            Logger.w(TAG, "读取联系人元数据失败: ${e.message}")
        }.getOrDefault(emptyMap())
    }

    private fun persistLocked() {
        val target = storeFile ?: return
        runCatching {
            AtomicFileStore.writeText(target, AppJson.encodeToString(_metas.value))
        }.onFailure { e ->
            Logger.w(TAG, "持久化联系人元数据失败: ${e.message}")
        }
    }

    @Synchronized
    internal fun resetForTest() {
        _metas.value = emptyMap()
        storeFile = null
        boundFilesDir = null
    }
}
