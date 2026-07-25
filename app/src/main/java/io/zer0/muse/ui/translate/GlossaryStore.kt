package io.zer0.muse.ui.translate

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * v1.0.30 gap4.6: 翻译术语表存储。
 *
 * 数据以 JSON 文件保存在应用私有目录(`filesDir/translate/glossary.json`),
 * 存储原文 → 译文 的固定映射(如 "API" → "应用程序接口")。
 *
 * 在 [TranslateViewModel.translate] 中,如果术语表非空,会在 prompt 中
 * 附加"请参考以下术语表进行翻译: API→应用程序接口, GPU→图形处理器..."指令,
 * 让 LLM 在翻译时遵循统一的术语译法(避免同一术语在不同译文中译法不一致)。
 *
 * 线程安全:内部用 synchronized 保护读写(readAll/save)。
 */
class GlossaryStore(private val context: Context) {

    private val file by lazy {
        java.io.File(context.filesDir, "translate/glossary.json").apply {
            parentFile?.mkdirs()
        }
    }

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    private val lock = Any()

    /** 列出全部术语映射(原文 → 译文)。 */
    fun list(): Map<String, String> = synchronized(lock) { readAll() }

    /** 添加或更新一条术语映射(原文 → 译文)。 */
    fun add(original: String, translated: String) {
        if (original.isBlank()) return
        synchronized(lock) {
            val all = readAll().toMutableMap()
            all[original.trim()] = translated.trim()
            save(all)
        }
    }

    /** 删除一条术语映射;返回是否实际删除。 */
    fun remove(original: String): Boolean {
        val key = original.trim()
        if (key.isBlank()) return false
        return synchronized(lock) {
            val all = readAll().toMutableMap()
            val removed = all.remove(key) != null
            if (removed) save(all)
            removed
        }
    }

    /** 清空术语表。 */
    fun clear() {
        synchronized(lock) { save(emptyMap()) }
    }

    /** 术语表是否为空。 */
    fun isEmpty(): Boolean = synchronized(lock) { readAll().isEmpty() }

    /**
     * 将术语表格式化为 prompt 片段,如 "API→应用程序接口, GPU→图形处理器"。
     * 空表返回空字符串(调用方据此决定是否附加到 prompt)。
     */
    fun toPromptSnippet(): String = synchronized(lock) {
        val all = readAll()
        if (all.isEmpty()) return ""
        all.entries.joinToString(", ") { "${it.key}→${it.value}" }
    }

    private fun readAll(): Map<String, String> {
        return try {
            if (!file.exists()) return emptyMap()
            AppJson.decodeFromString(mapSerializer, file.readText())
        } catch (e: Exception) {
            Logger.w(TAG, "读取术语表失败: ${e.message}")
            emptyMap()
        }
    }

    private fun save(map: Map<String, String>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(AppJson.encodeToString(mapSerializer, map))
        } catch (e: Exception) {
            Logger.w(TAG, "保存术语表失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GlossaryStore"
    }
}
