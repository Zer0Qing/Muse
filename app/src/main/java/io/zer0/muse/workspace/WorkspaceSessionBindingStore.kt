package io.zer0.muse.workspace

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 会话与挂载的绑定关系。
 *
 * 不同会话该看到哪几块目录，是使用习惯问题而不是权限问题：写代码的会话不该
 * 顺手把写小说的目录搜进上下文。没登记的会话不做限制，全部挂载可用。
 *
 * @param context 应用 Context，用于定位 filesDir
 */
class WorkspaceSessionBindingStore(private val context: Context) {

    /** 绑定表文件：会话 id → 允许的挂载 id 列表。 */
    private val storeFile: File = File(context.filesDir, "workspace_session_mounts.json")

    private val mutex = Mutex()

    private val json: Json = Json { prettyPrint = true }

    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    /**
     * 某个会话可用的挂载标识。
     *
     * @param sessionId 会话 id；为空表示没有会话上下文（例如后台定时任务）
     * @return 允许的挂载集合；null 表示不限制
     */
    suspend fun allowedMountIds(sessionId: String?): Set<String>? {
        if (sessionId.isNullOrBlank()) return null
        val table = read()
        return table[sessionId]?.takeIf { it.isNotEmpty() }?.toSet()
    }

    /**
     * 设置某个会话可用的挂载。
     *
     * @param ids 为空集合时表示清除绑定，回到不限制
     */
    suspend fun setAllowed(sessionId: String, ids: Set<String>): Unit = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext
        mutex.withLock {
            val table = read().toMutableMap()
            if (ids.isEmpty()) table.remove(sessionId) else table[sessionId] = ids.sorted()
            write(table)
        }
    }

    /** 清除某个会话的绑定。 */
    suspend fun clear(sessionId: String): Unit = setAllowed(sessionId, emptySet())

    /** 把挂载从所有会话的绑定里摘掉，避免删挂载后留下悬空 id。 */
    suspend fun forgetMount(mountId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val table = read()
            var changed = false
            val next = table.mapValues { (_, ids) ->
                if (mountId in ids) {
                    changed = true
                    ids - mountId
                } else {
                    ids
                }
            }.filterValues { it.isNotEmpty() }
            if (changed) write(next)
        }
    }

    private fun read(): Map<String, List<String>> {
        if (!storeFile.exists()) return emptyMap()
        val table: Map<String, List<String>> = resultOf {
            val text = storeFile.readText(Charsets.UTF_8)
            if (text.isBlank()) emptyMap() else json.decodeFromString(serializer, text)
        }.onError { message, _ -> Logger.w(TAG, "读取会话绑定失败: $message") }
            .getOrNull() ?: return emptyMap()
        return table
    }

    private fun write(table: Map<String, List<String>>) {
        resultOf {
            val temp = File(storeFile.parentFile, "${storeFile.name}.tmp")
            temp.writeText(json.encodeToString(serializer, table), Charsets.UTF_8)
            if (!temp.renameTo(storeFile)) {
                storeFile.writeText(json.encodeToString(serializer, table), Charsets.UTF_8)
                temp.delete()
            }
        }.onError { message, _ -> Logger.w(TAG, "写入会话绑定失败: $message") }
    }

    companion object {
        private const val TAG = "WorkspaceSessionBindingStore"
    }
}
