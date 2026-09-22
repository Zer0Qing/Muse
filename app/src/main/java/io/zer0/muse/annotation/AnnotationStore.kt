package io.zer0.muse.annotation

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
 * v1.0.92: 消息批注存储 — 用户对单条消息的纯文本批注。
 *
 * 全局单例(静态对象):UI 写入与读取;持久化到 filesDir/annotations.json(原子写)。
 * 批注为本地数据,不参与任何上传;批注列表可供后续对话上下文引用(按消息 id 关联)。
 */
object AnnotationStore {

    /** 单条批注(按消息 id 关联)。 */
    @Serializable
    data class Annotation(
        val id: String,
        val messageId: String,
        val sessionId: String = "",
        val text: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private const val TAG = "AnnotationStore"
    private const val MAX_ITEMS = 500

    private val _all = MutableStateFlow<List<Annotation>>(emptyList())
    val all: StateFlow<List<Annotation>> = _all.asStateFlow()

    private var file: File? = null

    /** 绑定应用上下文并恢复历史批注(幂等)。 */
    @Synchronized
    fun attach(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, "annotations.json")
        file = target
        if (target.exists()) {
            runCatching {
                AppJson.decodeFromString(ListSerializer(Annotation.serializer()), target.readText())
            }.onSuccess { _all.value = it.take(MAX_ITEMS) }
                .onFailure { e -> Logger.w(TAG, "批注恢复失败: ${e.message}") }
        }
    }

    /** 某条消息的全部批注(按时间升序)。 */
    fun ofMessage(messageId: String): List<Annotation> =
        _all.value.filter { it.messageId == messageId }.sortedBy { it.createdAt }

    /** 新增一条批注。 */
    fun add(messageId: String, sessionId: String, text: String) {
        val trimmed = text.trim()
        if (messageId.isBlank() || trimmed.isEmpty()) return
        val item = Annotation(
            id = "an_" + System.currentTimeMillis(),
            messageId = messageId,
            sessionId = sessionId,
            text = trimmed.take(2000),
        )
        val updated = (listOf(item) + _all.value).take(MAX_ITEMS)
        _all.value = updated
        persist(updated)
    }

    /** 删除一条批注。 */
    fun remove(id: String) {
        val updated = _all.value.filterNot { it.id == id }
        _all.value = updated
        persist(updated)
    }

    private fun persist(items: List<Annotation>) {
        val target = file ?: return
        runCatching {
            AtomicFileStore.writeText(
                target,
                AppJson.encodeToString(ListSerializer(Annotation.serializer()), items),
            )
        }.onFailure { e -> Logger.w(TAG, "批注持久化失败: ${e.message}") }
    }
}
