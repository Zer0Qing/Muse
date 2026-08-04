package io.zer0.muse.data.session

import android.content.Context
import androidx.room.withTransaction
import io.zer0.ai.core.MessageRole
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.ai.core.RagCitation
import io.zer0.ai.core.UIMessage
import io.zer0.common.ErrorMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid
import io.zer0.muse.R
import io.zer0.muse.transformer.MoodSkinParser

/**
 * 会话仓库:封装 SessionDao + MessageDao,提供领域模型 API。
 *
 * - 会话列表 / 创建 / 删除 / 切换
 * - 消息持久化(发送时写库,流式增量更新 assistant,编辑/重生成截断)
 * - [MessageEntity] ↔ [UIMessage] 双向转换
 *
 * H-SESS1: 跨表操作(messages + messages_fts + sessions)统一用 [database.withTransaction]
 * 包裹,保证崩溃后数据一致;[database] 由 Koin 注入(见 AppKoinModule)。
 *
 * ── i18n 改造示范(v1.90 收口,已完成 5 处)──────────────────────────────
 * 本类作为 i18n 架构骨架的示范改造点,展示如何把硬编码日志 / catch 消息替换为
 * [ErrorMessage] 子类。示范改造点 1..5 已全部落地(见各 `// i18n 示范改造点 N` 标注),
 * 后续其余待迁移点可按本模式逐步推进:
 *   1. catch / resultOf.onError 中:用 `Logger.w(TAG, ErrorMessage.X.toLogString(), t)`
 *      替换裸中英文消息([toLogString] 输出 [code] + 英文兜底,可日志可埋点)
 *   2. 上抛给 UI 的错误:返回 `io.zer0.common.Result.Error` 包裹 [ErrorMessage]
 *   3. UI 层用 [io.zer0.muse.ui.toLocalizedString] 翻译为当前 locale 字符串
 * ──────────────────────────────────────────────────────────────────────
 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val database: MuseDb,
    private val context: Context,
    private val messageImageStore: MessageImageStore,
    /** v1.135-A: 视觉辅助缓存清理(会话删除时清理 sidecar)。 */
    private val visionCache: io.zer0.muse.vision.VisionCache? = null,
    /** P3: 会话权限模式清理(会话删除时清理持久化设置)。 */
    private val sessionPermissionStore: SessionPermissionStore? = null,
    /** P2-4: 审计日志记录器(会话删除等用户操作)。 */
    private val auditLogger: AuditLogger? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val urlListSerializer = ListSerializer(String.serializer())
    /** v1.133: RAG 引用列表序列化器(持久化到 messages.ragCitationsJson)。 */
    private val ragCitationListSerializer = ListSerializer(RagCitation.serializer())

    companion object {
        private const val TAG = "SessionRepo"
        /** H-SESS3: observeBySession 默认加载条数,与分页加载配合避免 OOM。 */
        private const val OBSERVE_LIMIT = 200
        /** 会话预览(lastMessagePreview)最大长度。 */
        private const val MESSAGE_PREVIEW_LENGTH = 50
        /** 首条 user 消息自动命名时取内容前缀长度。 */
        private const val AUTO_TITLE_LENGTH = 20
        /** 搜索片段匹配位置前后保留字数。 */
        private const val SNIPPET_RADIUS = 30
        /** 搜索片段无匹配时回退取前缀长度。 */
        private const val SNIPPET_FALLBACK_LENGTH = 60
    }

    /**
     * 观察未归档会话(置顶优先,再按 updatedAt 降序)。主列表用。
     * v1.28: 排除 Agent Tab 会话(isAgentSession=0),Agent 日常聊天不污染任务列表。
     */
    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeTaskSessions()

    /** v0.45: 观察已归档会话(按 updatedAt 降序)。归档列表用。 */
    fun observeArchived(): Flow<List<SessionEntity>> = sessionDao.observeArchived()

    /** v0.45: 切换会话归档状态。 */
    suspend fun setArchived(sessionId: String, archived: Boolean) {
        sessionDao.setArchived(sessionId, archived)
    }
    /** B7-03: 更新会话已读位置。 */
    suspend fun updateLastReadMessage(sessionId: String, messageId: String, readCount: Int) {
        sessionDao.updateLastReadMessage(sessionId, messageId, readCount)
    }

    /** B7-03: 获取最早一条未读消息 id(lastReadMessageId=null 时取会话首条消息)。 */
    suspend fun findFirstUnreadMessageId(sessionId: String, lastReadMessageId: String?): String? {
        return if (lastReadMessageId == null) {
            sessionDao.findFirstMessageId(sessionId)
        } else {
            sessionDao.findFirstUnreadMessageId(sessionId, lastReadMessageId)
        }
    }

    /** B7-05: 置顶会话拖拽排序持久化。 */
    suspend fun reorderPinnedSessions(ids: List<String>) {
        ids.forEachIndexed { index, id -> sessionDao.updateSortOrder(id, index) }
    }

    /** B8-01: 更新会话级主动消息排期(null=清除排期)。 */
    suspend fun updateProactiveNextTriggerAt(id: String, nextTriggerAt: Long?) {
        sessionDao.updateProactiveNextTriggerAt(id, nextTriggerAt)
    }

    // ── v2.0: 软删除 ──

    /** v2.0: 软删除会话。 */
    suspend fun softDeleteSession(id: String) {
        sessionDao.softDelete(id, System.currentTimeMillis())
        auditLogger?.log(
            category = "user_action",
            action = "soft_delete_session",
            target = id,
        )
    }

    /** v2.0: 恢复软删除的会话。 */
    suspend fun restoreSession(id: String) {
        sessionDao.restore(id)
    }

    /** v2.0: 观察 7 天内软删除的会话。 */
    fun observeDeletedSessions(): Flow<List<SessionEntity>> {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        return sessionDao.observeDeleted(cutoff)
    }

    /** v2.0: 永久过期清理(7 天前的软删除会话)。 */
    suspend fun purgeOldDeletedSessions() {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                messageDao.clearFts()
                sessionDao.permanentlyDeleteOldSessions(cutoff)
            }
        }
    }

    /** v2.0: 清空全部会话(硬删除,替代原 deleteAll 用于数据管理页)。 */
    suspend fun hardDeleteAllSessions() {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                messageDao.clearFts()
                sessionDao.deleteAll()
            }
            auditLogger?.log(
                category = "user_action",
                action = "delete_all_sessions",
            )
        }
    }

    /** 观察会话总数(统计面板用)。 */
    fun observeSessionCount(): Flow<Int> = sessionDao.observeCount()

    /** 观察消息总数(统计面板用)。 */
    fun observeMessageCount(): Flow<Int> = messageDao.observeCount()

    /** v2.0: 一次性获取会话总数。 */
    suspend fun getSessionCount(): Int = sessionDao.count()

    /** v2.0: 一次性获取消息总数。 */
    suspend fun getTotalMessageCount(): Int = messageDao.countMessages()

    /** 创建新会话。返回会话 id。标题默认"新会话"。 */
    suspend fun createSession(assistantId: String = "default"): String {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        sessionDao.insert(
            SessionEntity(
                id = id,
                title = context.getString(R.string.session_repo_default_title),
                createdAt = now,
                updatedAt = now,
                assistantId = assistantId,
            )
        )
        return id
    }

    /** v1.28: 创建 Agent Tab 专用会话(独立于任务会话,不在任务列表显示)。 */
    suspend fun createAgentSession(assistantId: String = "default"): String {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        sessionDao.insert(
            SessionEntity(
                id = id,
                title = "Agent",
                createdAt = now,
                updatedAt = now,
                assistantId = assistantId,
                isAgentSession = true,
            )
        )
        return id
    }

    /** v1.28: 获取最近的 Agent 会话(用于自动恢复)。 */
    suspend fun getLatestAgentSession(): SessionEntity? = sessionDao.getLatestAgentSession()

    /** v1.0.54: 按助手查最近 Agent 会话。 */
    suspend fun getRecentAgentByAssistant(assistantId: String, limit: Int): List<SessionEntity> =
        sessionDao.getRecentAgentByAssistant(assistantId, limit)

    /**
     * v1.58: 从源会话的某条消息处分叉,创建新会话并复制到该消息为止的全部历史。
     *
     * - 复用源会话的 assistantId / folderId
     * - 消息重新生成 id(避免主键冲突),保留原 createdAt 顺序
     * - 标题改为"分叉自 {源标题}"
     *
     * @return 新会话 id;源会话或锚点消息不存在时返回 null。
     */
    suspend fun forkSession(sourceSessionId: String, untilMessageId: String): String? {
        // H-SESS1: 跨表(sessions + messages + messages_fts)复制,用事务包裹,
        // 崩溃时整体回滚,避免分叉会话消息/FTS 索引不完整。
        return withContext(Dispatchers.IO) {
            database.withTransaction {
                val sourceSession = sessionDao.getById(sourceSessionId) ?: return@withTransaction null
                val anchor = messageDao.getById(sourceSessionId, untilMessageId) ?: return@withTransaction null
                val newId = Uuid.random().toString()
                val now = System.currentTimeMillis()
                sessionDao.insert(
                    SessionEntity(
                        id = newId,
                        title = context.getString(R.string.session_repo_fork_title, sourceSession.title.ifBlank { context.getString(R.string.session_repo_default_title) }),
                        createdAt = now,
                        updatedAt = now,
                        assistantId = sourceSession.assistantId,
                        folderId = sourceSession.folderId,
                        parentSessionId = sourceSessionId,
                    )
                )
                sessionDao.incrementChildCount(sourceSessionId)
                // 复制到锚点为止的全部消息(含锚点),重新生成 id 避免主键冲突
                // 调 appendMessageInternal 复用 FTS 同步 + 预览更新,且不另开事务(已在事务内)
                val messages = messageDao.getUpToBySession(sourceSessionId, anchor.createdAt)
                for (msg in messages) {
                    appendMessageInternal(newId, msg.toUIMessage().copy(id = Uuid.random()))
                }
                newId
            }
        }
    }

    /** v1.28: 观察任务会话(排除 Agent Tab 会话)。 */
    fun observeTaskSessions(): Flow<List<SessionEntity>> = sessionDao.observeTaskSessions()

    /** 删除会话(级联删消息 + FTS 索引)。 */
    suspend fun deleteSession(id: String) {
        // Phase 10.3: 先删 FTS 索引(依赖 messages 表子查询,必须在 messages 被级联删之前)
        // H-SESS1: 跨表(FTS + sessions)用事务包裹,保证一致性
        withContext(Dispatchers.IO) {
            database.withTransaction {
                messageDao.deleteFtsBySession(id)
                sessionDao.deleteById(id)
            }
            // v1.135-A: 清理该会话的视觉辅助缓存 sidecar,避免孤儿文件
            // i18n 示范改造点 1:原硬编码中文 "清理视觉缓存失败: $id" 改为
            // ErrorMessage.StorageError.IO_ERROR + sessionId 作为日志 metadata。
            // Locale 无关,toLogString() 输出 "[ERR:storage_io_error] Local storage read/write failed"
            resultOf { visionCache?.clearSession(id) }
                .onError { _, t ->
                    Logger.w(
                        TAG,
                        "${ErrorMessage.StorageError.IO_ERROR.toLogString()} [sessionId=$id]",
                        t,
                    )
                }
            // P3: 清理该会话的权限模式设置
            // i18n 示范改造点 2:原硬编码中文 "清理会话权限模式失败: $id" 改为
            // ErrorMessage.StorageError.IO_ERROR,与上条同 code 不同 metadata 区分场景。
            resultOf { sessionPermissionStore?.clearMode(id) }
                .onError { _, t ->
                    Logger.w(
                        TAG,
                        "${ErrorMessage.StorageError.IO_ERROR.toLogString()} [sessionId=$id, scope=permission]",
                        t,
                    )
                }
            // P2-4: 审计日志 — 删除会话
            auditLogger?.log(
                category = "user_action",
                action = "delete_session",
                target = id,
            )
        }
    }

    /** 重命名会话。 */
    suspend fun renameSession(id: String, title: String) {
        // H-SESS2: 原子 UPDATE,避免读-改-写并发丢失更新
        sessionDao.updateTitle(id, title, System.currentTimeMillis())
    }

    /** Phase 8.2: 切换会话绑定的 Assistant。 */
    suspend fun setSessionAssistant(sessionId: String, assistantId: String) {
        sessionDao.setAssistantId(sessionId, assistantId)
    }

    /** Phase 8.2: 切换会话置顶状态。 */
    suspend fun setSessionPinned(sessionId: String, pinned: Boolean) {
        sessionDao.setPinned(sessionId, pinned)
    }

    /** Phase 9.1 (M13): 切换会话所属文件夹(null = 移到未分组)。 */
    suspend fun setSessionFolder(sessionId: String, folderId: String?) {
        sessionDao.setFolderId(sessionId, folderId)
    }

    /** Phase 8.2: 取会话绑定的 Assistant id(无则默认 'default')。 */
    suspend fun getAssistantId(sessionId: String): String {
        return sessionDao.getById(sessionId)?.assistantId ?: "default"
    }

    /** Phase 8.3: 切换消息收藏状态(跨会话收藏面板通过 observeAllFavorites 聚合)。 */
    suspend fun setMessageFavorite(messageId: String, favorite: Boolean) {
        messageDao.setFavorite(messageId, favorite)
    }

    /** 功能1: 设置消息表情回应(null = 取消回应)。 */
    suspend fun setMessageReaction(messageId: String, reaction: String?) {
        messageDao.setReaction(messageId, reaction)
    }

    /** v1.104 U7: 设置收藏分组标签(null = 移到未分组)。 */
    suspend fun setMessageFavoriteTag(messageId: String, tag: String?) {
        messageDao.setMessageFavoriteTag(messageId, tag)
    }

    /** v1.104 U7: 观察所有已命名的收藏分组标签(去 NULL / 去重 / 升序)。 */
    fun observeAllFavoriteTags(): Flow<List<String>> {
        return messageDao.observeAllFavoriteTags()
    }

    /** v1.48: 按 id 删除单条消息(长按菜单"删除消息")。同时清理 FTS 索引。 */
    suspend fun deleteMessage(messageId: String) {
        // H-SESS1: 跨表(FTS + messages)用事务包裹,保证 FTS 与消息同步删除
        withContext(Dispatchers.IO) {
            database.withTransaction {
                // v1.107 冗余: 先查出 sessionId,用于维护冗余计数
                val entity = messageDao.getByMessageId(messageId)
                messageDao.deleteFts(messageId)
                messageDao.deleteById(messageId)
                // v1.107 冗余: 递减 sessions.messageCount
                if (entity != null) {
                    resultOf { sessionDao.incrementMessageCount(entity.sessionId, -1) }
                        .onError { _, t -> Logger.w(TAG, "decrementMessageCount failed: ${t?.message ?: ""}") }
                }
            }
            // v1.134 P1-2: 清理该消息的图片文件,避免孤儿文件占用存储
            // i18n 示范改造点 3:原硬编码英文 "deleteMessage images cleanup failed: ${...}"
            // 改为 ErrorMessage.StorageError.IO_ERROR.toLogString(),消除裸字符串。
            resultOf { messageImageStore.deleteByMessageId(messageId) }
                .onError { _, t ->
                    Logger.w(
                        TAG,
                        "${ErrorMessage.StorageError.IO_ERROR.toLogString()} [messageId=$messageId, scope=images]",
                        t,
                    )
                }
        }
    }

    /** Phase 8.3: 观察跨会话的全部收藏消息(收藏面板用)。 */
    fun observeAllFavorites(): Flow<List<UIMessage>> {
        return messageDao.observeAllFavorites()
            .map { entities -> entities.map { it.toUIMessage() } }
            .distinctUntilChanged()
    }

    /** 观察指定会话的消息列表(转 UIMessage)。 */
    fun observeMessages(sessionId: String): Flow<List<UIMessage>> {
        // H-SESS3: 限最近 OBSERVE_LIMIT 条,避免长会话全量加载 OOM;更早历史走分页加载
        return messageDao.observeRecentBySession(sessionId, OBSERVE_LIMIT)
            .map { entities -> entities.map { it.toUIMessage() } }
            .distinctUntilChanged()
    }

    /**
     * v1.53-A1: 分页加载 — 取指定会话最近 limit 条消息(按 createdAt 升序)。
     *
     * DAO 返回降序(最新在前),这里 reversed() 转为升序,便于直接渲染。
     * 用于初始加载,避免一次性加载全部消息导致卡顿/OOM。
     */
    suspend fun getRecentMessages(sessionId: String, limit: Int): List<UIMessage> {
        return messageDao.getRecentBySession(sessionId, limit).reversed().map { it.toUIMessage() }
    }

    /**
     * v1.53-A1: 分页加载 — 取早于 beforeCreatedAt 的 limit 条消息(按 createdAt 升序)。
     *
     * 用于上滑加载更多:以当前列表最早一条消息的 createdAt 为锚点,取更早的历史。
     * 返回升序,可直接前置拼接到现有 messages 列表。
     */
    suspend fun getOlderMessages(sessionId: String, beforeCreatedAt: Long, limit: Int): List<UIMessage> {
        return messageDao.getOlderBySession(sessionId, beforeCreatedAt, limit).reversed().map { it.toUIMessage() }
    }

    /**
     * v1.0.51: 一次性获取指定会话的【全量】消息(按 createdAt 升序),供存量记忆迁移 backfill 用。
     *
     * 与 [observeMessages] / [getRecentMessages] 的差异:不走分页 limit,加载完整历史。
     * backfill 需要全量 messages 喂给 SessionSummaryManager.rollingSummary 重建摘要。
     * 用 Flow.first() 拿一次性快照,不持续观察。
     */
    suspend fun getAllMessagesForBackfill(sessionId: String): List<UIMessage> = withContext(Dispatchers.IO) {
        messageDao.observeBySession(sessionId).first().map { it.toUIMessage() }
    }

    /**
     * v1.0.51: 一次性获取所有未归档未删除的会话(含 Agent 会话),供存量记忆迁移遍历用。
     *
     * 用 Flow.first() 拿一次性快照,不持续观察。backfill 只关心启动时刻的 session 列表。
     */
    suspend fun getAllActiveSessionsForBackfill(): List<SessionEntity> = withContext(Dispatchers.IO) {
        sessionDao.observeActive().first()
    }

    /**
     * v1.0.52: 获取指定助手最近 N 条会话(供 Recent Chats Reference 注入 system prompt)。
     *
     * 排除已归档/软删除/Agent Tab 会话,按 updatedAt DESC 限制条数。
     * 调用方(SystemPromptAssembler.buildRecentChatsSection)用标题 + lastMessagePreview
     * 构造简洁的最近对话列表,让 LLM 感知用户近期上下文,但不作为指令执行。
     */
    suspend fun getRecentByAssistant(assistantId: String, limit: Int): List<SessionEntity> = withContext(Dispatchers.IO) {
        sessionDao.getRecentByAssistant(assistantId, limit)
    }

    /** v1.53-A1: 会话消息总数(分页判断 hasMoreHistory 用)。 */
    suspend fun getMessageCount(sessionId: String): Int = messageDao.countBySession(sessionId)

    /** 持久化一条消息,返回其 id。同时更新会话的 updatedAt + lastMessagePreview + FTS 索引。 */
    suspend fun appendMessage(sessionId: String, message: UIMessage): String {
        // H-SESS1: 跨表(messages + FTS + sessions)用事务包裹,保证一致性
        return withContext(Dispatchers.IO) {
            database.withTransaction {
                appendMessageInternal(sessionId, message)
            }
        }
    }

    /**
     * appendMessage 的非事务版本,供已在事务内的调用方(如 [forkSession])复用,
     * 避免嵌套事务的 savepoint 开销。
     */
    private suspend fun appendMessageInternal(sessionId: String, message: UIMessage): String {
        val entity = message.toEntity(sessionId)
        messageDao.upsert(entity)
        // Phase 10.3: 同步 FTS 索引(toNgram 预处理中文 2-gram)
        // v1.63: 加前置 deleteFts 防御,避免未来误用 appendMessage 更新已存在 id 时 FTS 重复
        // i18n 示范改造点 4:原硬编码英文 "FTS insert failed: ..." 改为 ErrorMessage。
        // 注:此处不上抛 ErrorMessage(FTS 是派生数据,失败可被 ensureFtsIndexConsistent 自愈),
        // 仅用 ErrorMessage 标准化日志 code,便于跨模块统计 FTS 故障率。
        resultOf {
            messageDao.deleteFts(entity.id)
            messageDao.insertFts(entity.id, MessageFtsManager.toNgram(entity.content))
        }.onError { _, t ->
            Logger.w(
                TAG,
                "${ErrorMessage.StorageError.IO_ERROR.toLogString()} [messageId=${entity.id}, scope=fts_insert]",
                t,
            )
        }
        updateSessionPreview(sessionId, message)
        // v1.107 冗余: 维护 sessions.messageCount(避免列表页 COUNT)
        resultOf { sessionDao.incrementMessageCount(sessionId, 1) }
            .onError { _, t -> Logger.w(TAG, "incrementMessageCount failed: ${t?.message ?: ""}") }
        return entity.id
    }

    // ── v1.0.15: 消息发送 outbox(持久化发送队列)──────────────────────────
    // 解决"用户刚点击发送就退出 App,进程被系统杀死导致消息丢失"的问题。
    // outbox 记录在 enqueueSend 时同步写入,消费端启动 launchStream 后删除;
    // App 启动时扫描残留记录重新投递。

    /** 同步写入 outbox(供 enqueueSend 在主线程 runBlocking 调用,保证落盘)。 */
    suspend fun insertOutbox(entity: MessageOutboxEntity) {
        database.messageOutboxDao().upsert(entity)
    }

    /** 消费端成功启动 launchStream 后删除 outbox(消息已投递,不再需要恢复)。 */
    suspend fun deleteOutbox(id: String) {
        database.messageOutboxDao().deleteById(id)
    }

    /** App 启动时扫描所有未完成 outbox 记录(按创建时间升序)。 */
    suspend fun getPendingOutbox(): List<MessageOutboxEntity> {
        return database.messageOutboxDao().getAll()
    }

    /** 检查指定消息 id 是否已持久化到 messages 表(恢复时避免重复 appendMessage)。 */
    suspend fun messageExists(sessionId: String, messageId: String): Boolean {
        return messageDao.getById(sessionId, messageId) != null
    }

    // ── B5-01: 流式生成检查点(生成 outbox)───────────────────────────────

    /** 写入/更新流式生成检查点。 */
    suspend fun upsertGenerationCheckpoint(
        sessionId: String,
        userMessageId: String,
        assistantMessageId: String,
        content: String,
        createdAt: Long,
    ) {
        withContext(Dispatchers.IO) {
            database.generationCheckpointDao().upsert(
                GenerationCheckpointEntity(
                    assistantMessageId = assistantMessageId,
                    sessionId = sessionId,
                    userMessageId = userMessageId,
                    content = content,
                    createdAt = createdAt,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** 生成正常结束后删除检查点。 */
    suspend fun deleteGenerationCheckpoint(assistantMessageId: String) {
        withContext(Dispatchers.IO) {
            database.generationCheckpointDao().deleteByAssistantMessageId(assistantMessageId)
        }
    }

    /** 截断/重新生成时删除该时间点之后的检查点,避免恢复出已废弃的回复。 */
    suspend fun deleteGenerationCheckpointsBefore(sessionId: String, fromCreatedAt: Long) {
        withContext(Dispatchers.IO) {
            database.generationCheckpointDao().deleteBySessionAndCreatedAtFrom(sessionId, fromCreatedAt)
        }
    }

    /** 读取全部未完成生成检查点。 */
    suspend fun getPendingGenerationCheckpoints(): List<GenerationCheckpointEntity> =
        withContext(Dispatchers.IO) { database.generationCheckpointDao().getAllPending() }

    /**
     * B5-01: 应用启动时恢复被强杀的中断生成。
     *
     * 每条残留检查点:
     * - 消息已存在:补齐最新内容分片并追加 [已中断] 标记
     * - 消息不存在且无更新助手消息:重建 assistant 消息
     * - 消息不存在但有更新助手消息(已被重新生成):视为过期检查点并删除
     */
    suspend fun recoverInterruptedGenerations() {
        withContext(Dispatchers.IO) {
            val pending = runCatching { database.generationCheckpointDao().getAllPending() }
                .getOrElse { e ->
                    Logger.w(TAG, "读取生成检查点失败", e)
                    return@withContext
                }
            for (cp in pending) {
                try {
                    val existing = messageDao.getByMessageId(cp.assistantMessageId)
                    val marker = "[已中断]"
                    val base = when {
                        existing != null && existing.content.length >= cp.content.length -> existing.content
                        else -> cp.content
                    }
                    val content = when {
                        base.contains(marker) -> base
                        base.isBlank() -> marker
                        else -> base + "\n\n" + marker
                    }
                    val ui: UIMessage? = when {
                        existing != null -> existing.toUIMessage().copy(content = content)
                        database.generationCheckpointDao().countNewerAssistantMessages(cp.sessionId, cp.updatedAt) > 0 -> {
                            database.generationCheckpointDao().deleteByAssistantMessageId(cp.assistantMessageId)
                            null
                        }
                        else -> {
                            val id = runCatching { Uuid.parse(cp.assistantMessageId) }.getOrElse {
                                Logger.w(TAG, "generation checkpoint assistantMessageId 非 UUID: " + cp.assistantMessageId)
                                Uuid.random()
                            }
                            UIMessage(
                                id = id,
                                role = MessageRole.ASSISTANT,
                                content = content,
                                createdAt = cp.createdAt,
                            )
                        }
                    }
                    if (ui != null) {
                        upsertMessage(cp.sessionId, ui)
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "恢复中断生成失败: " + cp.assistantMessageId, e)
                }
            }
            if (pending.isNotEmpty()) {
                Logger.i(TAG, "中断生成恢复完成: " + pending.size + " 条检查点")
            }
        }
    }

    /**
     * 流式更新 assistant 消息(多次 upsert 同一 id)。每次先删后插保证 FTS 索引幂等。
     *
     * v1.97 (P1-2): 新增 [skipFts] 参数。流式周期性落盘(builder 还在增长)时传 true,
     * 跳过 toNgram + deleteFts + insertFts 的 CPU/IO 开销(长文本 toNgram 可达数十毫秒)。
     * FTS 是派生数据,流式结束后最终落盘或下次启动 ensureFtsIndexConsistent 会补齐。
     * 非流式路径(最终落盘 / 中断落盘 / 工具消息 / 引用消息)用默认 false,保证 FTS 同步。
     */
    suspend fun upsertMessage(sessionId: String, message: UIMessage, skipFts: Boolean = false) {
        // H-SESS1: 跨表(messages + FTS + sessions)用事务包裹,保证流式更新一致性
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val entity = message.toEntity(sessionId)
                messageDao.upsert(entity)
                // Phase 10.3: 同步 FTS(删后插,避免重复索引项)
                // v1.97 (P1-2): skipFts=true 时跳过,避免流式周期性落盘反复重建 FTS
                if (!skipFts) {
                    resultOf {
                        messageDao.deleteFts(entity.id)
                        messageDao.insertFts(entity.id, MessageFtsManager.toNgram(entity.content))
                    }.onError { _, t -> Logger.w(TAG, "FTS upsert sync failed: ${t?.message ?: ""}") }
                }
                if (message.role == MessageRole.ASSISTANT && message.content.isNotEmpty()) {
                    updateSessionPreview(sessionId, message)
                }
            }
        }
    }

    /**
     * 编辑助手消息:直接更新指定消息的内容,并同步 FTS 索引。
     */
    suspend fun updateMessageContent(sessionId: String, messageId: Uuid, content: String) {
        // H-SESS1: 跨表(messages + FTS + sessions)用事务包裹
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val message = messageDao.getById(sessionId, messageId.toString()) ?: return@withTransaction
                val updated = message.copy(content = content, reasoning = null)
                messageDao.upsert(updated)
                // Phase 10.3: 同步 FTS 索引(删后插,避免重复索引项)
                resultOf {
                    messageDao.deleteFts(message.id)
                    messageDao.insertFts(message.id, MessageFtsManager.toNgram(content))
                }.onError { _, t -> Logger.w(TAG, "FTS update failed: ${t?.message ?: ""}") }
                // 更新会话预览为最新的消息内容
                updateSessionPreview(sessionId, updated.toUIMessage())
            }
        }
    }

    /** 截断会话:删除 createdAt >= fromCreatedAt 的消息(编辑/重生成用)。同步删 FTS 索引。 */
    suspend fun truncateFrom(sessionId: String, fromCreatedAt: Long) {
        // H-SESS1: 跨表(FTS + messages)用事务包裹
        withContext(Dispatchers.IO) {
            database.withTransaction {
                truncateFromInternal(sessionId, fromCreatedAt)
            }
        }
    }

    /** truncateFrom 的非事务版本,供已在事务内的调用方(如 [deleteLastMessage])复用。 */
    private suspend fun truncateFromInternal(sessionId: String, fromCreatedAt: Long) {
        // Phase 10.3: 先删 FTS(依赖 messages 子查询,必须在 messages 删除之前)
        messageDao.deleteFtsBySessionAndCreatedAt(sessionId, fromCreatedAt)
        messageDao.deleteFromCreatedAt(sessionId, fromCreatedAt)
        database.generationCheckpointDao().deleteBySessionAndCreatedAtFrom(sessionId, fromCreatedAt)
    }

    /** 删除会话内最后一条 assistant 消息(重生成用)。同步删 FTS 索引。 */
    suspend fun deleteLastMessage(sessionId: String) {
        // H-SESS1/H-SESS2: 跨表(messages + FTS + sessions)读-改-写,用事务包裹保证一致;
        // 预览更新改用原子 UPDATE(无读-改-写)。
        withContext(Dispatchers.IO) {
            database.withTransaction {
                messageDao.getLastBySession(sessionId)?.let { last ->
                    // 复用 truncateFromInternal(含 FTS 同步),避免漏删索引
                    truncateFromInternal(sessionId, last.createdAt)
                }
                // 更新 preview 为新的最后一条(原子 UPDATE,无读-改-写)
                val newLast = messageDao.getLastBySession(sessionId)
                sessionDao.updatePreview(
                    id = sessionId,
                    preview = newLast?.content?.take(MESSAGE_PREVIEW_LENGTH) ?: "",
                    now = System.currentTimeMillis(),
                )
            }
        }
    }

    /** 取会话最后一条消息(一次性,非 Flow)。 */
    suspend fun getLastMessage(sessionId: String): UIMessage? {
        return messageDao.getLastBySession(sessionId)?.toUIMessage()
    }

    /**
     * 全文搜索消息(跨会话)。返回搜索结果列表(含会话标题 + 内容片段)。
     *
     * Phase 10.3 改造:
     * - 优先走 FTS4 MATCH 查询([MessageFtsManager.toMatchQuery] 转 ngram + 引号转义)
     * - JOIN messages + sessions 一次查出(修复 P5-B 的 N+1:原实现循环 sessionDao.getById)
     * - ngram 转换后为空(纯符号)或 FTS 查询异常时回退 LIKE([searchLike])
     * - 片段仍取匹配位置前后 30 字(基于原文 content,不是 ngram)
     */
    suspend fun searchMessages(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        // 注意:流式 upsertMessage(skipFts=true)周期性落盘后 FTS 可能滞后,
        // 最终落盘(skipFts=false)或下次启动 ensureFtsIndexConsistent 会补齐。
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val matchQuery = MessageFtsManager.toMatchQuery(trimmed)
        val joins: List<MessageSearchJoin> = if (matchQuery.isBlank()) {
            // ngram 转换后为空(纯符号/纯空白),直接走 LIKE
            searchLikeAndJoin(trimmed)
        } else {
            // H-SESS1: FTS 查询异常时回退 LIKE;用 resultOf 正确重抛 CancellationException
            // i18n 示范改造点 5:原硬编码英文 "FTS search failed, fallback to LIKE: ..."
            // 改为 ErrorMessage.StorageError.IO_ERROR + raw message 作为补充 debug 信息。
            // FTS 失败已有 LIKE 兜底,不上抛 ErrorMessage,仅标准化日志 code 便于监控告警聚合。
            when (val r = resultOf { messageDao.searchFts(matchQuery) }) {
                is io.zer0.common.Result.Success -> r.data
                is io.zer0.common.Result.Error -> {
                    Logger.w(
                        TAG,
                        "${ErrorMessage.StorageError.IO_ERROR.toLogString()} [scope=fts_search, fallback=LIKE, raw=${r.message}]",
                    )
                    searchLikeAndJoin(trimmed)
                }
            }
        }

        joins.map { join ->
            SearchResult(
                messageId = join.messageId,
                sessionId = join.sessionId,
                sessionTitle = join.sessionTitle,
                contentSnippet = buildSnippet(join.content, trimmed),
                role = join.role,
                createdAt = join.createdAt,
                // 任务 2:携带原文供 UI 提取前后 2 句上下文 + 关键词高亮
                content = join.content,
            )
        }
    }

    /**
     * LIKE 回退路径:查 messages 后补查 session 标题(FTS 不可用时的兜底)。
     *
     * TODO: LIKE 回退路径 N+1 查询(每条 msg 单独查 session),性能优化时改为 IN 批查或 JOIN。
     * FTS 正常时本路径几乎不触发,暂保留现状。
     */
    private suspend fun searchLikeAndJoin(query: String): List<MessageSearchJoin> {
        // B4-01: 直接用 JOIN sessions 的批查,消除原逐条查 session 的 N+1
        val pattern = buildLikePattern(query)
        return messageDao.searchMessageContentLike(pattern, 50)
    }

    /**
     * v2.x: 消息内容搜索 Flow 版本(供 SearchViewModel 监听)。
     *
     * 与 [searchMessages] 区别:
     *  - 返回 [Flow](便于 ViewModel 用 collectAsStateWithLifecycle 监听)
     *  - 走 [MessageDao.searchMessageContent](FTS4 snippet 直接生成片段),而非 [searchFts] + buildSnippet
     *  - FTS4 snippet 作用于 content_ngram,片段为 ngram 串,效果有限(详见 DAO 注释 TODO);
     *    FTS 异常或 ngram 转换为空时回退 [searchMessageContentLike] + 原文 buildSnippet
     *
     * 调用方:SearchViewModel 的 searchMessageContent() 一次性 collect 后存入 StateFlow。
     *
     * TODO: FTS4 snippet 作用于 content_ngram,片段为 ngram 串,效果不理想。
     *       后续迁移到 FTS5 + 外部内容表后,本方法可直接返回 SQL snippet 结果。
     *
     * @param query 用户输入的搜索关键词(未转义)
     * @return [SearchResult] 的 Flow(单次 emit,空查询返回空列表)
     */
    fun searchMessageContentFlow(query: String): Flow<List<SearchResult>> = flow {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val matchQuery = MessageFtsManager.toMatchQuery(trimmed)
        val results: List<SearchResult> = if (matchQuery.isBlank()) {
            // ngram 转换后为空(纯符号/纯空白),直接走 LIKE 兜底
            searchMessageContentLikeInternal(trimmed)
        } else {
            // H-SESS1: FTS 查询异常时回退 LIKE;用 resultOf 正确重抛 CancellationException
            // i18n 示范改造点 5(同类延伸):与 searchMessages 一致,FTS 失败兜底日志
            // 改为 ErrorMessage.StorageError.IO_ERROR,保持 code 一致便于监控聚合。
            when (val r = resultOf { messageDao.searchMessageContent(matchQuery, 50) }) {
                is io.zer0.common.Result.Success -> r.data
                is io.zer0.common.Result.Error -> {
                    Logger.w(
                        TAG,
                        "${ErrorMessage.StorageError.IO_ERROR.toLogString()} [scope=fts_search_content, fallback=LIKE, raw=${r.message}]",
                    )
                    searchMessageContentLikeInternal(trimmed)
                }
            }
        }
        // B4-01: DAO 只返回原文,片段由 Repository 基于原文构建,避免 ngram 串片段
        emit(results.map { it.copy(contentSnippet = buildSnippet(it.content, trimmed)) })
    }.flowOn(Dispatchers.IO)

    /**
     * v2.x: LIKE 兜底路径(消息内容搜索专用)。
     *
     * 调 [MessageDao.searchMessageContentLike](JOIN sessions 一次查出,避免 N+1),
     * 再用 [buildSnippet] 基于原文 content 构建 [SearchResult.contentSnippet]。
     */
    private suspend fun searchMessageContentLikeInternal(query: String): List<SearchResult> {
        val pattern = buildLikePattern(query)
        val joins = messageDao.searchMessageContentLike(pattern, 50)
        return joins.map { join ->
            SearchResult(
                messageId = join.messageId,
                sessionId = join.sessionId,
                sessionTitle = join.sessionTitle,
                contentSnippet = buildSnippet(join.content, query),
                role = join.role,
                createdAt = join.createdAt,
                // 任务 2:携带原文供 UI 提取前后 2 句上下文 + 关键词高亮
                content = join.content,
            )
        }
    }

    /** M-SESS2: 构造 LIKE 模式串,转义通配符 \ % _ 后包成 %...%。 */
    private fun buildLikePattern(query: String): String {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    /**
     * Phase 10.3: 检查 FTS 索引一致性,不一致则全量 rebuild。
     *
     * 触发场景:
     * - v8→v9 迁移后(messages_fts 空表,需首次全量索引)
     * - 外部修改 messages 表(备份导入后)
     * - 索引损坏(FTS 查询异常后下次启动自愈)
     *
     * 由 [ChatViewModel] init 在 app 启动时异步调用,IO 线程执行。
     * 万级消息 rebuild 约数百毫秒,不阻塞 UI。
     */
    suspend fun ensureFtsIndexConsistent() = withContext(Dispatchers.IO) {
        val msgCount = resultOf { messageDao.countMessages() }.getOrNull() ?: -1
        val ftsCount = resultOf { messageDao.countFts() }.getOrNull() ?: -1
        if (msgCount < 0 || ftsCount < 0) {
            Logger.w(TAG, "FTS count check failed: msg=$msgCount fts=$ftsCount")
            return@withContext
        }
        if (msgCount == ftsCount) {
            Logger.d(TAG, "FTS index consistent: $ftsCount rows")
            return@withContext
        }
        Logger.i(TAG, "FTS index inconsistent: msg=$msgCount fts=$ftsCount, rebuilding...")
        rebuildFtsIndex()
    }

    /**
     * Phase 10.3: 全量重建 FTS 索引。清空后遍历 messages 重新插入(toNgram 转换)。
     *
     * v1.114: 将 clearFts + 逐条 insert 包裹在 `database.withTransaction` 中,避免重建期间
     * FTS 查询返回不一致结果(清空后、插入完成前查询会返回空或不完整数据)。每条 insert 仍用
     * resultOf 容错,单条失败不影响其他条目,事务正常提交。
     * 调用方应在 IO 线程。
     */
    suspend fun rebuildFtsIndex() = withContext(Dispatchers.IO) {
        // v1.114: 事务包裹,保证 clearFts + insert 原子可见性,避免重建期间查询不一致
        database.withTransaction {
            messageDao.clearFts()
            val rows = messageDao.getAllForFtsRebuild()
            var ok = 0
            rows.forEach { row ->
                resultOf {
                    messageDao.insertFts(row.id, MessageFtsManager.toNgram(row.content))
                    ok++
                }.onError { _, t ->
                    Logger.w(TAG, "FTS rebuild insert failed for ${row.id}: ${t?.message ?: ""}")
                }
            }
            Logger.i(TAG, "FTS rebuild done: $ok/${rows.size} messages indexed")
        }
    }

    /** 构建搜索片段:取匹配位置前后 [SNIPPET_RADIUS] 字。 */
    private fun buildSnippet(content: String, query: String): String {
        // TODO: ngram 匹配与 snippet 高亮语义不一致(FTS 用 2-gram 匹配,这里用原 query indexOf),
        // 后续改用 FTS4 snippet() 函数在 SQL 层生成高亮片段,保证匹配/高亮语义一致。
        val idx = content.indexOf(query, ignoreCase = true)
        if (idx < 0) return content.take(SNIPPET_FALLBACK_LENGTH)
        val start = (idx - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (idx + query.length + SNIPPET_RADIUS).coerceAtMost(content.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length) "…" else ""
        return prefix + content.substring(start, end) + suffix
    }

    /**
     * 更新会话预览 + updatedAt。
     *
     * 不再用首条消息前缀自动命名标题,标题保持默认值"新会话",
     * 改由 UI 退出对话时调用 [io.zer0.muse.ui.ChatViewModel.autoTitleOnExit] 触发 AI 摘要命名。
     */
    private suspend fun updateSessionPreview(sessionId: String, message: UIMessage) {
        val preview = message.content.take(MESSAGE_PREVIEW_LENGTH).ifBlank { "…" }
        sessionDao.updatePreviewOnly(
            id = sessionId,
            preview = preview,
            now = System.currentTimeMillis(),
        )
    }

    // ── 转换工具 ──────────────────────────────────────────────────────────

    /** MessageEntity → UIMessage。 */
    private fun MessageEntity.toUIMessage(): UIMessage {
        // B6-02: 历史消息清洗 — 兼容旧数据里尚未剥离的 <moodfx> 标签
        val parsedMoodSkin = MoodSkinParser.extract(content, moodSkin)
        return UIMessage(
            // Phase 8.5 修复: 非 UUID 格式的 id 不崩溃(数据导入/旧版本兼容),回退到随机 UUID
            id = runCatching { Uuid.parse(id) }.getOrElse {
                Logger.w(TAG, "invalid message id (not UUID): $id")
                Uuid.random()
            },
            role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.USER),
            content = parsedMoodSkin.second,
            reasoning = reasoning,
            thinkingSignature = thinkingSignature,
            thinkingEncryptedContent = thinkingEncryptedContent,
            modelId = modelId,
            createdAt = createdAt,
            imageUrls = parseImageUrls(imageUrlsJson),
            favorite = favorite,
            favoriteTag = favoriteTag,
            citationUrls = parseImageUrls(citationUrlsJson),
            ragCitations = parseRagCitations(ragCitationsJson),
            imageBase64List = messageImageStore.toBase64List(parseImageUrls(imageBase64Json)),
            artifactIds = parseImageUrls(artifactIdsJson),
            moodSkin = parsedMoodSkin.first ?: moodSkin,
            mood = mood,
            reflection = reflection,
            reaction = reaction,
            variantGroupId = variantGroupId,
            variantIndex = variantIndex,
            variantCount = variantCount,
        )
    }

    /** UIMessage → MessageEntity。 */
    private fun UIMessage.toEntity(sessionId: String): MessageEntity = MessageEntity(
        id = id.toString(),
        sessionId = sessionId,
        role = role.name,
        content = content,
        reasoning = reasoning,
        thinkingSignature = thinkingSignature,
        thinkingEncryptedContent = thinkingEncryptedContent,
        modelId = modelId,
        createdAt = createdAt,
        imageUrlsJson = encodeImageUrls(imageUrls),
        favorite = favorite,
        // v1.104 U7: 收藏分组标签往返(默认 NULL,未分组)
        favoriteTag = favoriteTag,
        citationUrlsJson = encodeImageUrls(citationUrls),
        // v1.133: RAG 引用列表持久化往返
        ragCitationsJson = encodeRagCitations(ragCitations),
        // Phase 8.6: 多模态图片 base64 列表
        // v1.134 P1-2: MessageImageStore 把大图片 base64 落盘到 filesDir/muse_images/,
        // DB 只存 "file://xxx" 路径,避免 messages 表行体积膨胀;
        // 短数据(< 1024 字符)仍存 base64,旧数据读取时无 "file://" 前缀直接返回
        imageBase64Json = encodeImageUrls(messageImageStore.toPersistable(id.toString(), imageBase64List)),
        // v1.43: Artifact id 列表
        artifactIdsJson = encodeImageUrls(artifactIds),
        // v1.103: MOOD / reflection 持久化(之前 toEntity 丢弃导致切页后消失)
        moodSkin = moodSkin,
        mood = mood,
        reflection = reflection,
        // 功能1: 消息表情回应往返
        reaction = reaction,
        // v1.0.30: 变体信息持久化
        variantGroupId = variantGroupId,
        variantIndex = variantIndex,
        variantCount = variantCount,
    )

    /** v1.0.30: 按 id 获取消息（变体查询用）。 */
    suspend fun getMessageById(messageId: String): MessageEntity? =
        withContext(Dispatchers.IO) { messageDao.getByMessageId(messageId) }

    /** v1.0.30: 获取同变体组所有消息。 */
    suspend fun getVariants(groupId: String): List<MessageEntity> =
        withContext(Dispatchers.IO) { messageDao.getVariants(groupId) }

    /** v1.0.30: 更新变体组计数。 */
    suspend fun updateVariantCount(groupId: String, count: Int) {
        withContext(Dispatchers.IO) { messageDao.updateVariantCount(groupId, count) }
    }

    /** v1.0.30: 直接 upsert MessageEntity（变体字段更新用）。 */
    suspend fun upsertMessageEntity(entity: MessageEntity) {
        withContext(Dispatchers.IO) { messageDao.upsert(entity) }
    }

    private fun encodeImageUrls(urls: List<String>): String =
        // v1.100: 空列表短路,避免周期性落盘(每 300 字符)时对 4 个空字段
        // 各做一次 JSON 序列化(虽然开销小,但高频累积)
        if (urls.isEmpty()) "[]" else runCatching { json.encodeToString(urlListSerializer, urls) }.getOrDefault("[]")

    private fun parseImageUrls(s: String): List<String> =
        runCatching { json.decodeFromString(urlListSerializer, s) }.getOrDefault(emptyList())

    /** v1.133: 序列化 RAG 引用列表(空列表短路,避免高频落盘开销)。 */
    private fun encodeRagCitations(citations: List<RagCitation>): String =
        if (citations.isEmpty()) "[]" else runCatching { json.encodeToString(ragCitationListSerializer, citations) }.getOrDefault("[]")

    private fun parseRagCitations(s: String): List<RagCitation> =
        runCatching { json.decodeFromString(ragCitationListSerializer, s) }.getOrDefault(emptyList())
}
