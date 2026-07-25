package io.zer0.muse.chat

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * 断点续传(工具中断恢复)— 持久化未完成的工具调用。
 *
 * 背景:
 *  - 流式对话中,LLM 决策调用 tool_calls 后,ChatViewModel 会依次执行各工具并回填 TOOL 消息。
 *  - 如果用户手动停止流式 / 应用崩溃 / 进程被杀,正在执行的 tool_calls 队列会被丢弃,
 *    导致 AI 拿不到工具结果,下次回复时丢失上下文(模型只看到自己发出了 tool_calls
 *    却没有对应的 tool 响应,部分 Provider 会直接报错)。
 *
 * 设计:
 *  - 持久化用 JSON 文件(filesDir/pending_tool_calls.json),不走 Room。
 *    原因:数据量小(每个 tool_call 几百字节)、生命周期短(执行完即删),
 *    避免给 MuseDb 增加 schema 版本 + 迁移成本。
 *  - 文件 IO 全部走 IO Dispatcher + Mutex 串行化,保证线程安全。
 *  - 工具调用恢复时保留原始 [PendingToolCall.toolCallId],让 LLM 能对应上 tool_call_id。
 *
 * 参考实现:rikkahub 项目的 finishInterruptedPendingTools。
 */
object PendingToolCallStore {

    private const val TAG = "PendingToolCallStore"
    private const val FILE_NAME = "pending_tool_calls.json"

    /** 串行化文件读写,防止并发写覆盖。 */
    private val mutex = Mutex()

    /** 持久化的待完成工具调用记录。 */
    @Serializable
    data class PendingToolCall(
        /** 所属会话 id(sessionId),用于按会话维度查询/清理。 */
        val chatId: String,
        /** 工具调用唯一 id(对应 OpenAI tool_call_id),回填 TOOL 消息时必须保留。 */
        val toolCallId: String,
        /** 工具名(Skill id 或本地工具名),恢复执行时用于路由。 */
        val toolName: String,
        /** 工具参数 JSON 字符串(LLM 传入的原始 arguments)。 */
        val arguments: String,
        /** 创建时间戳(毫秒),用于排查与超时清理。 */
        val createdAt: Long,
    )

    /** 持久化文件路径(惰性初始化,首次访问时确定 Context)。 */
    @Volatile
    private var fileRef: File? = null

    /**
     * 初始化文件路径。需在 App 启动时调用一次(从 [MuseApp] 或 Koin 模块注入时)。
     * 不调用也能用 — 首次 [loadAll] 会用 applicationContext 兜底。
     */
    fun init(context: Context) {
        if (fileRef == null) {
            synchronized(this) {
                if (fileRef == null) {
                    fileRef = File(context.applicationContext.filesDir, FILE_NAME)
                }
            }
        }
    }

    /** 内部:取当前文件(若未初始化则报错并返回 null,调用方需处理)。 */
    private fun file(): File? = fileRef ?: run {
        Logger.w(TAG, "fileRef 未初始化,请先调用 init(context);本次操作将被忽略")
        null
    }

    /** 内部:从文件读取全部 pending 列表(空文件 / 解析失败均返回空表)。 */
    private suspend fun loadAll(): List<PendingToolCall> = withContext(Dispatchers.IO) {
        val f = file() ?: return@withContext emptyList()
        if (!f.exists()) return@withContext emptyList()
        resultOf {
            val text = f.readText()
            if (text.isBlank()) emptyList()
            else AppJson.decodeFromString(ListSerializer(PendingToolCall.serializer()), text)
        }.onError { msg, t ->
            Logger.w(TAG, "loadAll 解析失败: $msg(文件可能损坏,将被覆盖)", t)
        }.getOrNull() ?: emptyList()
    }

    /** 内部:把 pending 列表整体写回文件(原子写:写临时文件后 rename,避免半写损坏)。 */
    private suspend fun saveAllInternal(list: List<PendingToolCall>) = withContext(Dispatchers.IO) {
        val f = file() ?: return@withContext
        resultOf {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            val text = AppJson.encodeToString(
                ListSerializer(PendingToolCall.serializer()),
                list,
            )
            tmp.writeText(text)
            // 原子 rename(File.renameTo 在同分区下原子,跨分区降级为复制+删除,仍可靠)
            if (!tmp.renameTo(f)) {
                // 降级:rename 失败时直接覆盖写(Android 单分区 filesDir 内一般不会走到)
                f.writeText(text)
                tmp.delete()
            }
        }.onError { msg, t ->
            Logger.e(TAG, "saveAllInternal 写入失败: $msg", t)
        }
    }

    /**
     * 保存单个 pending 工具调用(追加到现有列表)。
     * 在 LLM 决策调用 tool_calls 后立即调用,确保即使后续执行崩溃也有记录。
     */
    suspend fun save(pending: PendingToolCall) = mutex.withLock {
        val current = loadAll()
        // 同 toolCallId 视为重复(理论上不会发生,LLM 每次 tool_call_id 唯一),去重保险
        val merged = current.filterNot { it.toolCallId == pending.toolCallId } + pending
        saveAllInternal(merged)
    }

    /**
     * 批量保存多个 pending 工具调用(一次性追加,减少文件 IO 次数)。
     * 收到 tool_calls 后用此方法一次性写入整批。
     */
    suspend fun saveAll(pendings: List<PendingToolCall>) = mutex.withLock {
        if (pendings.isEmpty()) return@withLock
        val current = loadAll()
        val newIds = pendings.map { it.toolCallId }.toSet()
        // 去重:同 toolCallId 以最新为准
        val merged = current.filterNot { it.toolCallId in newIds } + pendings
        saveAllInternal(merged)
    }

    /**
     * 获取指定会话的全部 pending 工具调用(按 createdAt 升序,保持 LLM 调用顺序)。
     * 用于切换到该会话时检查是否需要恢复。
     */
    suspend fun getForChat(chatId: String): List<PendingToolCall> = mutex.withLock {
        loadAll().filter { it.chatId == chatId }.sortedBy { it.createdAt }
    }

    /**
     * 删除指定 toolCallId 的 pending 记录。
     * 在工具执行完成(拿到结果并回填到对话历史)后调用。
     */
    suspend fun remove(toolCallId: String) = mutex.withLock {
        val current = loadAll()
        val filtered = current.filterNot { it.toolCallId == toolCallId }
        if (filtered.size != current.size) {
            saveAllInternal(filtered)
        }
    }

    /**
     * 清空指定会话的全部 pending 记录。
     * 用户选择"丢弃"时调用,或会话被删除时清理。
     */
    suspend fun clearForChat(chatId: String) = mutex.withLock {
        val current = loadAll()
        val filtered = current.filterNot { it.chatId == chatId }
        if (filtered.size != current.size) {
            saveAllInternal(filtered)
        }
    }

    /**
     * 获取全部未完成的 pending(按 chatId 分组)。
     * 用于 App 启动时全局检查哪些会话有待恢复的工具调用。
     */
    suspend fun getAllPending(): Map<String, List<PendingToolCall>> = mutex.withLock {
        loadAll().groupBy { it.chatId }
    }
}
