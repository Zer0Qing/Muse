package io.zer0.muse.data.groupchat

import androidx.room.withTransaction
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID

/**
 * 群聊仓库 — 封装 DAO,暴露 Flow + suspend 方法。
 *
 * 职责:
 *  - 群聊 CRUD(createChat / deleteChat / updateChat / observeChats)
 *  - 消息发送(sendMessage)与观察(observeMessages 已 @Deprecated / getPagedMessages 分页版)
 *  - 消息分页加载(getRecentMessagesPaged 首屏 + getOlderMessages 上滑加载更多)
 *  - 最近消息读取(getRecentMessages)供 Agent 工具 / 调度器读取上下文
 *  - memberIds JSON 序列化/反序列化辅助
 *
 * 所有 DB 操作包裹在 [Dispatchers.IO] 中。
 * 跨表操作(发送消息 + 更新时间戳、删除群聊 + 级联消息)用 [MuseDb.withTransaction] 保证原子性。
 *
 * @param db Room 数据库(用于跨表事务)
 * @param groupChatDao 群聊 DAO
 * @param groupChatMessageDao 群聊消息 DAO
 * @param assistantRepository Assistant 仓库(用于反查成员名,可选)
 */
class GroupChatRepository(
    private val db: MuseDb,
    private val groupChatDao: GroupChatDao,
    private val groupChatMessageDao: GroupChatMessageDao,
    private val assistantRepository: AssistantRepository,
) {

    private val TAG = "GroupChatRepo"

    companion object {
        /** v1.53-GC: 群聊消息分页 — 首屏页大小(初始加载条数)。 */
        const val INITIAL_PAGE_SIZE = 30
        /** v1.53-GC: 群聊消息分页 — 上滑加载更多页大小。 */
        const val LOAD_MORE_PAGE_SIZE = 20
    }

    /** 观察全部群聊(按 updatedAt 降序)。 */
    fun observeChats(): Flow<List<GroupChatEntity>> = groupChatDao.observeAll()

    /**
     * 观察指定群聊的消息(按 timestamp 升序,全量上限 200)。
     *
     * v1.53-GC: 全量观察长群聊有 OOM 风险(图片 base64 直存 DB 叠加长消息列表)。
     * 新代码改用 [getPagedMessages](首屏 Flow) + [getRecentMessagesPaged](初始加载)
     * + [getOlderMessages](上滑加载更多)分页方案。本方法保留向后兼容,不在新代码中使用。
     */
    @Deprecated(
        "全量观察长群聊有 OOM 风险,改用 getPagedMessages / getRecentMessagesPaged + getOlderMessages 分页加载。",
        ReplaceWith("getPagedMessages(chatId)"),
    )
    fun observeMessages(chatId: String): Flow<List<GroupChatMessageEntity>> = groupChatMessageDao.observeMessages(chatId)

    /**
     * v1.53-GC: 观察指定群聊最近 initialPageSize 条消息(按 timestamp 升序,Flow 实时更新)。
     *
     * 替代全量 [observeMessages]:聊天界面首屏只观察最近一页,新消息到达时 Flow 自动重发,
     * ViewModel 据此增量追加到已加载列表(已加载的更早历史保留不动)。
     *
     * @param chatId 群聊 id
     * @param initialPageSize 首屏页大小,默认 [INITIAL_PAGE_SIZE]
     */
    fun getPagedMessages(
        chatId: String,
        initialPageSize: Int = INITIAL_PAGE_SIZE,
    ): Flow<List<GroupChatMessageEntity>> = groupChatMessageDao.observeRecentMessages(chatId, initialPageSize)

    /**
     * v1.53-GC: 分页初始加载 — 取指定群聊最近 limit 条消息(按 timestamp 升序)。
     *
     * DAO 返回降序(最新在前),这里 reversed() 转为升序,便于直接渲染。
     * 用于 ViewModel 首屏加载,避免一次性加载全部消息导致卡顿/OOM。
     *
     * @param chatId 群聊 id
     * @param limit 页大小,默认 [INITIAL_PAGE_SIZE]
     * @return 消息列表(按时间升序)
     */
    suspend fun getRecentMessagesPaged(
        chatId: String,
        limit: Int = INITIAL_PAGE_SIZE,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        groupChatMessageDao.getRecentMessages(chatId, limit).reversed()
    }

    /**
     * v1.53-GC: 分页加载更多 — 取早于锚点(beforeTimestamp, beforeId)的 limit 条消息(按 timestamp 升序)。
     *
     * 用于上滑加载更多:以当前列表最早一条消息的 (timestamp, id) 为双锚点,取更早的历史。
     * DAO 返回降序(最新在前),这里 reversed() 转为升序,可直接前置拼接到现有列表。
     * 双锚点(timestamp + id)避免同毫秒消息被漏取或重复。
     *
     * @param chatId 群聊 id
     * @param beforeTimestamp 锚点时间戳(当前列表最早一条的 timestamp)
     * @param beforeId 锚点消息 id(当前列表最早一条的 id,同毫秒去重用)
     * @param limit 页大小,默认 [LOAD_MORE_PAGE_SIZE]
     * @return 消息列表(按时间升序),空列表表示已无更早历史
     */
    suspend fun getOlderMessages(
        chatId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int = LOAD_MORE_PAGE_SIZE,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        groupChatMessageDao.getOlderMessages(chatId, beforeTimestamp, beforeId, limit).reversed()
    }

    /** 观察指定群聊的元数据(用于详情页标题刷新)。 */
    fun observeChat(id: String): Flow<GroupChatEntity?> = groupChatDao.observeById(id)

    /** 按 id 取单个群聊(一次性)。 */
    suspend fun getChat(id: String): GroupChatEntity? = withContext(Dispatchers.IO) {
        groupChatDao.getById(id)
    }

    /**
     * 创建群聊。
     *
     * @param name 群聊名称
     * @param memberIds 成员 assistantId 列表
     * @param teamId 可选关联团队 id
     * @return 新创建的群聊 id
     */
    suspend fun createChat(name: String, memberIds: List<String>, teamId: String? = null): String = withContext(Dispatchers.IO) {
        val chatId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        groupChatDao.upsert(
            GroupChatEntity(
                id = chatId,
                name = name,
                memberIdsJson = serializeMemberIds(memberIds),
                teamId = teamId,
                createdAt = now,
                updatedAt = now,
            )
        )
        chatId
    }

    /**
     * 更新群聊信息(名称/描述/成员/讨论模式)。
     *
     * @param chatId 群聊 id
     * @param name 新名称(null 表示不更新)
     * @param description 新描述(null 表示不更新)
     * @param memberIds 新成员列表(null 表示不更新)
     * @param discussionMode 讨论模式(null 表示不更新)
     * @param autoMaxRounds Auto 模式最大轮数(null 表示不更新)
     * @param hostId 主持人 AI id(null 表示不更新;传 "" 清空)
     */
    suspend fun updateChat(
        chatId: String,
        name: String? = null,
        description: String? = null,
        memberIds: List<String>? = null,
        discussionMode: String? = null,
        autoMaxRounds: Int? = null,
        hostId: String? = null,
    ) = withContext(Dispatchers.IO) {
        // M2: 用事务包裹读-改-写,防止并发更新丢失
        db.withTransaction {
            val existing = groupChatDao.getById(chatId) ?: return@withTransaction
            val updated = existing.copy(
                name = name ?: existing.name,
                description = description ?: existing.description,
                memberIdsJson = memberIds?.let { serializeMemberIds(it) } ?: existing.memberIdsJson,
                discussionMode = discussionMode ?: existing.discussionMode,
                autoMaxRounds = autoMaxRounds ?: existing.autoMaxRounds,
                hostId = if (hostId != null) hostId.ifBlank { null } else existing.hostId,
                updatedAt = System.currentTimeMillis(),
            )
            groupChatDao.upsert(updated)
        }
    }

    /**
     * 发送消息到群聊。
     *
     * @param chatId 群聊 id
     * @param senderType 发送者类型: "user" / "assistant"
     * @param senderId 发送者 id(userId 或 assistantId)
     * @param senderName 发送者显示名(缓存)
     * @param body 消息正文
     * @param imageBase64Json 图片附件 base64 列表(JSON 字符串,默认 "[]")
     * @param mood Agent 情绪(可选)
     * @param reasoning Agent 思考过程(可选)
     * @return 新消息 id
     */
    suspend fun sendMessage(
        chatId: String,
        senderType: String,
        senderId: String,
        senderName: String,
        body: String,
        imageBase64Json: String = "[]",
        fileAttachmentsJson: String = "[]",
        mood: String? = null,
        reasoning: String? = null,
        whisperTargetId: String? = null,
        replyToId: String? = null,
        messageType: String = "normal",
    ): String = withContext(Dispatchers.IO) {
        val msgId = UUID.randomUUID().toString()
        // H-GC2: 插入消息 + 更新会话时间戳必须原子,避免崩溃后消息已写但时间戳未更新
        db.withTransaction {
            // M6: 校验群聊存在,防止孤儿消息(在事务内检查防止竞态)
            if (groupChatDao.getById(chatId) == null) return@withTransaction
            groupChatMessageDao.upsert(
                GroupChatMessageEntity(
                    id = msgId,
                    chatId = chatId,
                    senderType = senderType,
                    senderId = senderId,
                    senderName = senderName,
                    body = body,
                    imageBase64Json = imageBase64Json,
                    fileAttachmentsJson = fileAttachmentsJson,
                    timestamp = System.currentTimeMillis(),
                    mood = mood,
                    reasoning = reasoning,
                    whisperTargetId = whisperTargetId,
                    replyToId = replyToId,
                    messageType = messageType,
                )
            )
            // 更新群聊的 updatedAt(列表页排序用)
            groupChatDao.touchUpdatedAt(chatId)
            // v1.107 冗余: 更新群聊冗余字段(最后消息预览 + 计数 + 最后活动时间)
            val preview = body.take(50).ifBlank { "…" }
            resultOf {
                groupChatDao.updateLastMessageAndCount(chatId, preview, 1, System.currentTimeMillis())
            }.onError { msg, _ -> Logger.w(TAG, "updateLastMessageAndCount failed: $msg") }
        }
        msgId
    }

    /**
     * 删除群聊(级联删除其全部消息)。
     *
     * H-GC1: 跨表删除(消息 + 群聊)用 @Transaction 包裹,保证原子性。
     *
     * @param chatId 群聊 id
     */
    suspend fun deleteChat(chatId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            groupChatMessageDao.deleteByChat(chatId)
            groupChatDao.deleteById(chatId)
        }
    }

    /**
     * v1.77: 删除群聊中的单条消息。
     *
     * @param messageId 消息 id
     */
    suspend fun deleteMessage(messageId: String) = withContext(Dispatchers.IO) {
        groupChatMessageDao.deleteById(messageId)
    }

    /**
     * 切换群聊置顶状态。
     *
     * @param chatId 群聊 id
     */
    suspend fun togglePin(chatId: String) = withContext(Dispatchers.IO) {
        // M2: 用事务包裹读-改-写,防止并发更新丢失
        db.withTransaction {
            val existing = groupChatDao.getById(chatId) ?: return@withTransaction
            groupChatDao.upsert(existing.copy(pinned = !existing.pinned))
        }
    }

    /**
     * 取指定群聊的最近 N 条消息(按 timestamp 升序返回,供 Agent 读取上下文)。
     *
     * @param chatId 群聊 id
     * @param limit 条数上限,默认 20
     * @return 消息列表(按时间升序)
     */
    suspend fun getRecentMessages(chatId: String, limit: Int = 20): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        // DAO 按 DESC 取最近 N 条,反转后按升序返回(便于顺序阅读)
        groupChatMessageDao.getRecentMessages(chatId, limit).reversed()
    }

    /** v1.0.72: 群聊内搜索消息(关键词匹配正文,按时间倒序)。 */
    suspend fun searchMessages(chatId: String, query: String, limit: Int = 100): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        groupChatMessageDao.searchMessages(chatId, query.trim(), limit)
    }

    /** 取指定群聊的最新一条消息(用于列表页预览)。 */
    suspend fun getLatestMessage(chatId: String): GroupChatMessageEntity? = withContext(Dispatchers.IO) {
        groupChatDao.getLatestMessage(chatId)
    }

    /** 统计指定群聊的消息数。 */
    suspend fun countMessages(chatId: String): Int = withContext(Dispatchers.IO) {
        groupChatDao.countMessages(chatId)
    }

    // ── memberIds JSON 序列化辅助 ──

    /** 把 memberIds 列表序列化为 JSON 字符串。 */
    fun serializeMemberIds(memberIds: List<String>): String {
        return AppJson.encodeToString(ListSerializer(String.serializer()), memberIds)
    }

    /** 把 memberIdsJson 反序列化为列表。 */
    fun parseMemberIds(chat: GroupChatEntity): List<String> {
        if (chat.memberIdsJson.isBlank() || chat.memberIdsJson == "[]") return emptyList()
        // L3: 用 resultOf 替代 runCatching,避免吞异常
        return resultOf {
            AppJson.decodeFromString(ListSerializer(String.serializer()), chat.memberIdsJson)
        }.getOrNull() ?: emptyList()
    }
    // ── B5-02: 群聊生成账本(进程被杀后按断点重放) ──

    suspend fun upsertGenerationLedger(entity: GroupChatGenerationLedgerEntity) {
        withContext(Dispatchers.IO) { db.groupChatGenerationLedgerDao().upsert(entity) }
    }


    suspend fun deleteGenerationLedger(id: String) {
        withContext(Dispatchers.IO) { db.groupChatGenerationLedgerDao().deleteById(id) }
    }

    suspend fun deleteGenerationLedgersByChatId(chatId: String) {
        withContext(Dispatchers.IO) { db.groupChatGenerationLedgerDao().deleteByChatId(chatId) }
    }

    suspend fun getGenerationLedger(id: String): GroupChatGenerationLedgerEntity? =
        withContext(Dispatchers.IO) { db.groupChatGenerationLedgerDao().getById(id) }

    suspend fun getPendingGenerationLedgers(): List<GroupChatGenerationLedgerEntity> =
        withContext(Dispatchers.IO) { db.groupChatGenerationLedgerDao().getPending() }

    // ── v2.x 群聊上下文管理:群共享文档 ──

    /** 把群共享文档列表序列化为 JSON 字符串。 */
    fun serializeSharedDocs(docs: List<GroupSharedDoc>): String {
        return AppJson.encodeToString(GroupSharedDoc.serializer().let { kotlinx.serialization.builtins.ListSerializer(it) }, docs)
    }

    /** 把 sharedDocsJson 反序列化为列表。 */
    fun parseSharedDocs(chat: GroupChatEntity): List<GroupSharedDoc> {
        if (chat.sharedDocsJson.isBlank() || chat.sharedDocsJson == "[]") return emptyList()
        return resultOf {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(GroupSharedDoc.serializer()),
                chat.sharedDocsJson,
            )
        }.getOrNull() ?: emptyList()
    }

    /**
     * v2.x: 添加一个群共享文档。
     *
     * @param chatId 群聊 id
     * @param title 文档标题
     * @param content 文档正文
     * @return 新文档 id(失败返回 null)
     */
    suspend fun addSharedDoc(chatId: String, title: String, content: String): String? = withContext(Dispatchers.IO) {
        db.withTransaction {
            val chat = groupChatDao.getById(chatId) ?: return@withTransaction null
            val docs = parseSharedDocs(chat).toMutableList()
            val docId = UUID.randomUUID().toString()
            docs.add(
                GroupSharedDoc(
                    id = docId,
                    title = title.take(200),
                    content = content,
                    addedAt = System.currentTimeMillis(),
                )
            )
            val updated = chat.copy(
                sharedDocsJson = serializeSharedDocs(docs),
                updatedAt = System.currentTimeMillis(),
            )
            groupChatDao.upsert(updated)
            docId
        }
    }

    /**
     * v2.x: 删除一个群共享文档。
     *
     * @param chatId 群聊 id
     * @param docId 文档 id
     */
    suspend fun removeSharedDoc(chatId: String, docId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val chat = groupChatDao.getById(chatId) ?: return@withTransaction
            val docs = parseSharedDocs(chat).filterNot { it.id == docId }
            val updated = chat.copy(
                sharedDocsJson = serializeSharedDocs(docs),
                updatedAt = System.currentTimeMillis(),
            )
            groupChatDao.upsert(updated)
        }
    }

    // ── v2.x 群聊上下文管理:AI 专属上下文 ──

    /** 把成员专属上下文 Map 序列化为 JSON 字符串。 */
    fun serializeMemberPrivateContext(map: Map<String, String>): String {
        return AppJson.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(
                String.serializer(),
                String.serializer(),
            ),
            map,
        )
    }

    /** 把 memberPrivateContextJson 反序列化为 Map。 */
    fun parseMemberPrivateContext(chat: GroupChatEntity): Map<String, String> {
        if (chat.memberPrivateContextJson.isBlank() || chat.memberPrivateContextJson == "{}") return emptyMap()
        return resultOf {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.MapSerializer(
                    String.serializer(),
                    String.serializer(),
                ),
                chat.memberPrivateContextJson,
            )
        }.getOrNull() ?: emptyMap()
    }

    /**
     * v2.x: 设置某个群成员的专属上下文。
     *
     * @param chatId 群聊 id
     * @param assistantId 成员 AI id
     * @param contextText 专属上下文文本(传空字符串则清除该成员的专属上下文)
     */
    suspend fun setMemberPrivateContext(chatId: String, assistantId: String, contextText: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val chat = groupChatDao.getById(chatId) ?: return@withTransaction
            val map = parseMemberPrivateContext(chat).toMutableMap()
            if (contextText.isBlank()) {
                map.remove(assistantId)
            } else {
                map[assistantId] = contextText
            }
            val updated = chat.copy(
                memberPrivateContextJson = serializeMemberPrivateContext(map),
                updatedAt = System.currentTimeMillis(),
            )
            groupChatDao.upsert(updated)
        }
    }
}
