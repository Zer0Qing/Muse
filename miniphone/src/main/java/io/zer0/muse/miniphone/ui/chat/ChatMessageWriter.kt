package io.zer0.muse.miniphone.ui.chat

import androidx.compose.runtime.Immutable
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 消息落库的唯一入口:写库、刷新会话预览、把新消息交回界面。
 * 用户文本、图片、角色回复三处写入都走这条路,先后由 [writeMutex] 兜住。
 * 翻页读游标也借用这把闸:倒序窗口的游标是「已加载条数」,
 * 读游标与查询之间插进一条新消息,那一页就会和前页重一条、再漏一条更早的。
 *
 * @param now 时间源,默认取系统时钟;测试用它钉死 `createdAt`
 * @param newId 主键生成器,默认 UUID;测试用它让消息 id 可预期
 * @param onStored 属于本会话的消息落地后回调,参数是界面要追加的那一行
 * @param onStatusChanged 发送状态改写后回调:库里与界面上那一行必须同时变
 * @param onDeleted 本机删掉一条后回调
 */
internal class ChatMessageWriter(
    private val threadId: String,
    private val messageDao: MpMessageDao,
    private val threadDao: MpThreadDao,
    private val now: () -> Long,
    private val newId: () -> String,
    private val onStored: (ChatMessageUi) -> Unit,
    private val onStatusChanged: (messageId: String, status: MpMessageStatus) -> Unit = { _, _ -> },
    private val onDeleted: (messageId: String) -> Unit = {},
) {

    /** 落库与翻页共用的闸。 */
    private val writeMutex = Mutex()

    /**
     * 落一条消息:库里、会话预览、界面列表三处一起更新。
     *
     * 只有属于本会话的才交回界面 —— 图片种子方法允许写别的会话,那边由别的 ViewModel 列。
     *
     * @param message 待落的整条消息;用户发出的话先以「发送中」落库,拿到结果后再改
     * @return 新消息的 id,调用方用它跟踪状态
     */
    suspend fun append(message: ChatOutgoingMessage): String {
        val entity = message.toEntity(id = newId(), createdAt = now())
        writeMutex.withLock {
            messageDao.insert(entity)
            threadDao.updateLastMessage(message.threadId, message.content, entity.createdAt)
            if (message.threadId == this@ChatMessageWriter.threadId) {
                onStored(entity.toChatMessageUi())
            }
        }
        return entity.id
    }

    /** 改一条消息的发送状态:库里与界面上那一行一起改,不存在一边新一边旧。 */
    suspend fun updateStatus(messageId: String, status: MpMessageStatus) {
        writeMutex.withLock {
            messageDao.updateStatus(messageId, status)
            onStatusChanged(messageId, status)
        }
    }

    /** 本机删一条:只动消息表,会话行的预览与时间保持在删除前的样子。 */
    suspend fun delete(messageId: String) {
        writeMutex.withLock {
            messageDao.deleteMessage(messageId)
            onDeleted(messageId)
        }
    }

    /** 在同一把闸里读一段消息:游标、查询、写回界面必须是连续的一步。 */
    suspend fun <R> withWriteLock(block: suspend () -> R): R = writeMutex.withLock { block() }
}

/**
 * 一条待落库的消息。
 *
 * 把「写一条消息需要知道的全部字段」合成一个值:字段与表一一对应,
 * 各个调用方只管把手里那份填满,不用记住八参数调用的顺序。
 */
@Immutable
internal data class ChatOutgoingMessage(
    val threadId: String,
    val characterId: String,
    val sender: MpActorType,
    val type: MpMessageType,
    val content: String,
    val mediaUri: String? = null,
    val voiceDurationMs: Long? = null,
    val status: MpMessageStatus = MpMessageStatus.SENT,
) {
    /** 添上主键与时间,变成要写进表的整行。 */
    fun toEntity(id: String, createdAt: Long): MpMessageEntity = MpMessageEntity(
        id = id,
        threadId = threadId,
        characterId = characterId,
        sender = sender,
        type = type,
        content = content,
        mediaUri = mediaUri,
        voiceDurationMs = voiceDurationMs,
        createdAt = createdAt,
        status = status,
    )
}
