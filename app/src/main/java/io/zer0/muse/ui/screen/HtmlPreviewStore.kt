package io.zer0.muse.ui.screen

import android.content.Context
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * HTML 预览内容的轻量中转缓存：
 * 导航参数仅传递短 `previewId`，避免大体积 HTML 源码塞进 NavBackStack SavedState
 * 触发 `TransactionTooLargeException`。落盘与 LRU 清理异步执行，不阻塞主线程。
 */
object HtmlPreviewStore {
    private const val MAX_ENTRIES = 24
    private const val MAX_DISK_FILES = 20
    private val lock = Any()
    private val diskScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)
    private val memoryCache = object : LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_ENTRIES
    }

    fun put(context: Context, html: String): String {
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            memoryCache[id] = html
        }
        val appCtx = context.applicationContext
        diskScope.launch {
            runCatching {
                val dir = File(appCtx.cacheDir, "html_preview").apply { mkdirs() }
                File(dir, "$id.html").writeText(html, Charsets.UTF_8)
                val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
                if (files.size > MAX_DISK_FILES) {
                    files.drop(MAX_DISK_FILES).forEach { old -> runCatching { old.delete() } }
                }
            }.onFailure { error ->
                Logger.w("HtmlPreviewStore", "落盘 HTML 预览缓存失败: ${error.message}", error)
            }
        }
        return id
    }

    fun getCached(id: String): String? = synchronized(lock) { memoryCache[id] }

    suspend fun loadOrNull(context: Context, id: String): String? {
        getCached(id)?.let { return it }
        return withContext(AppDispatchers.io) {
            runCatching {
                val file = File(File(context.cacheDir, "html_preview"), "$id.html")
                if (file.isFile) file.readText(Charsets.UTF_8) else null
            }.onFailure { error ->
                Logger.w("HtmlPreviewStore", "读取 HTML 预览缓存失败: ${error.message}", error)
            }.getOrNull()?.also { loaded ->
                synchronized(lock) { memoryCache[id] = loaded }
            }
        }
    }
}
