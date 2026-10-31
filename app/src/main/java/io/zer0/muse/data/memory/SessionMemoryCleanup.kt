package io.zer0.muse.data.memory

import io.zer0.common.Logger
import io.zer0.memory.compile.MemoryFileWriter
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.reflection.MemoryContradictionStore
import io.zer0.memory.summary.ContextCheckpointDao
import io.zer0.memory.summary.ScopedCompiledSectionDao
import io.zer0.memory.summary.SessionSummaryDao
import io.zer0.muse.data.groupchat.GroupChatMemoryDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 会话/助手被删除后的记忆清理入口。
 *
 * 记忆侧的数据不跟着 sessions 表走:摘要(`session_summaries`)、上下文检查点
 * (`context_checkpoints`)、事实(`facts`,按助手分库)、编译产物(`compiled_sections_scoped`、
 * `filesDir/memory/<scope>/`)、群聊记忆(`group_chat_memories`)、矛盾清单
 * (`filesDir/memory_contradictions.json`)各存一套。只删会话行的话,记忆页的记忆轴
 * 仍然按 `session_summaries` 与 `facts` 渲染,于是用户删掉对话后还能看到它的
 * `### 重要事实` / `Summary` 卡片。
 *
 * 两条硬约束:
 *  - **幂等**:重复调用不报错、不重复删(删过的行再删只是 0 行,墓碑用 IGNORE 写入);
 *  - **失败隔离**:每一步独立捕获,失败只记 warn,不影响后续步骤 —— 清理是收尾动作,
 *    不能因为某一环(比如某个分库被占用)失败就把整个删除流程拖死。
 *
 * 事实删除必须写墓碑:编译侧([io.zer0.memory.compile.MemoryCompiler])按墓碑过滤摘要里
 * 的旧句子,少了墓碑,下一轮编译会把刚删的内容当作"摘要孤儿"重新补录回表(复活)。
 */
class SessionMemoryCleanup(
    private val summaryDao: SessionSummaryDao,
    private val checkpointDao: ContextCheckpointDao,
    private val scopedSectionDao: ScopedCompiledSectionDao,
    private val groupChatMemoryDao: GroupChatMemoryDao,
    private val factDbProvider: FactDbProvider,
    private val fileWriter: MemoryFileWriter,
    private val contradictionStore: MemoryContradictionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 清理一批会话的记忆数据(摘要、检查点、事实及其派生行)。
     *
     * 软删除路径也走这里。取舍是明确的:用户按删除的预期是"删了就没了",
     * 所以即使 7 天内从"最近删除"里恢复会话,记忆也不会回来 ——
     * 恢复只还原消息,不重建摘要与事实。
     *
     * @param sessionIds 会话 id;空白 id 与重复 id 会被忽略,空集合直接返回
     */
    suspend fun cleanSessions(sessionIds: Collection<String>) {
        val ids = sessionIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return
        withContext(ioDispatcher) {
            var summaries = 0
            var checkpoints = 0
            var facts = 0
            ids.forEach { sessionId ->
                summaries += step("session_summaries[$sessionId]") { deleteSummary(sessionId) }
                checkpoints += step("context_checkpoints[$sessionId]") { deleteCheckpoint(sessionId) }
                facts += deleteFactsOfSession(sessionId)
            }
            Logger.i(TAG, "会话记忆清理完成: sessions=${ids.size} 摘要=$summaries 检查点=$checkpoints 事实=$facts")
        }
    }

    /**
     * 清理某个助手的记忆数据(摘要、编译段落、群聊记忆、产物目录、矛盾清单、事实)。
     *
     * 只清记忆,不删会话行:已属于该助手的对话保留下来,只是失去绑定。
     *
     * @param assistantId 助手 id;空白 id 直接返回
     */
    suspend fun cleanAssistant(assistantId: String) {
        val id = assistantId.trim()
        if (id.isBlank()) return
        val scope = scopeOf(id)
        withContext(ioDispatcher) {
            val summaries = step("session_summaries[assistant=$id]") { summaryDao.deleteByAssistant(id) }
            val sections = step("compiled_sections_scoped[scope=$scope]") { scopedSectionDao.deleteByScope(scope) }
            val groupChat = step("group_chat_memories[$id]") { groupChatMemoryDao.deleteByAssistant(id) }
            val files = step("memory/$scope") { fileWriter.deleteScopeDir(scope) }
            val contradictions = step("memory_contradictions[$scope]") { contradictionStore.clearScope(scope) }
            val facts = deleteFactsOfScope(scope)
            Logger.i(
                TAG,
                "助手记忆清理完成: assistant=$id scope=$scope 摘要=$summaries 段落=$sections " +
                    "群聊记忆=$groupChat 矛盾=$contradictions 事实=$facts 文件=$files",
            )
        }
    }

    /**
     * 助手的记忆作用域。
     *
     * 主助手(`default`)的主记忆池用 `main` 作 scope,其余助手的 scope 就是自己的 id ——
     * 与编译侧([io.zer0.memory.compile.MemoryCompileTarget])的取值口径保持一致。
     */
    private fun scopeOf(assistantId: String): String = if (assistantId == MAIN_ASSISTANT_ID) MAIN_SCOPE else assistantId

    /** 摘要表按 session_id 单行删除;先读一次只为统计"实际删了几条"。 */
    private suspend fun deleteSummary(sessionId: String): Int {
        val existed = summaryDao.get(sessionId) != null
        summaryDao.deleteById(sessionId)
        return if (existed) 1 else 0
    }

    /** 检查点同样一个会话至多一条。 */
    private suspend fun deleteCheckpoint(sessionId: String): Int {
        val existed = checkpointDao.get(sessionId) != null
        checkpointDao.deleteById(sessionId)
        return if (existed) 1 else 0
    }

    /**
     * 遍历主库与每个助手分库删该会话的事实。
     *
     * 事实按助手分库存放,只清主库 `facts.db` 会漏掉 `facts_<assistantId>.db` 里的行,
     * 而记忆页的"重要事实"卡片正是从这些行读出来的。
     */
    private suspend fun deleteFactsOfSession(sessionId: String): Int {
        var removed = 0
        factStores().forEach { (assistantId, store) ->
            removed += step("facts[session=$sessionId, db=$assistantId]") { store.deleteBySession(sessionId) }
        }
        return removed
    }

    /** 遍历主库与每个助手分库删该作用域的事实(主库可能残留已删助手的历史行)。 */
    private suspend fun deleteFactsOfScope(scope: String): Int {
        var removed = 0
        factStores().forEach { (assistantId, store) ->
            removed += step("facts[scope=$scope, db=$assistantId]") { store.deleteAllByScope(scope) }
        }
        return removed
    }

    /**
     * 主库 + 磁盘上真实存在的分库。
     *
     * 分库 id 从文件名枚举而不是从助手表推:已删除的助手不再有库文件,
     * 按 id 猜一遍会凭空 `Room.databaseBuilder(...).build()` 出空库并留在磁盘上。
     */
    private fun factStores(): List<Pair<String, FactStore>> =
        (listOf(MAIN_ASSISTANT_ID) + factDbProvider.listAssistantDbIds()).map { it to factDbProvider.getFactStore(it) }

    /**
     * 单步执行:失败记 warn 并返回 0,不影响调用方的后续步骤。
     *
     * 只吞普通异常 —— 协程取消必须继续上抛,否则删除流程被取消后还会继续跑完。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun step(label: String, block: suspend () -> Int): Int = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Logger.w(TAG, "记忆清理失败[$label]: ${t.message}", t)
        0
    }

    private companion object {
        private const val TAG = "SessionMemoryCleanup"

        /** 主助手 id(其事实落在 `facts.db`)。 */
        private const val MAIN_ASSISTANT_ID = "default"

        /** 主助手事实的作用域名。 */
        private const val MAIN_SCOPE = "main"
    }
}
