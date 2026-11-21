package io.zer0.muse.data.agentbot

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * 对外发布的唯一出口。
 *
 * ## 输出边界
 *
 * 主 Bot 的模型文本只有经这里提交才可见。普通模型正文、工具结果、内部事件、
 * 审核草稿**没有任何路径**进入公共消息 —— 这不是靠调用方自觉，而是因为
 * 只有本类的 [publish] 会写 messages 表，而它的入参只接受 [PublicationKind] 的封闭集合。
 *
 * ## 为什么 outbox 与 messages 必须同事务
 *
 * 只写 outbox：用户看不到消息，恢复逻辑以为还没发，永远重试。
 * 只写 messages：恢复逻辑以为还没发，同一条消息被发两次。
 * 两者必须在同一个 Room 事务里提交。
 *
 * ## 部分发布恢复
 *
 * 一批多条 item 时，进程可能在中间被杀。恢复时只重投 `PENDING` 的条目，
 * 已 `COMMITTED` 的不动 —— 用户看到的消息不能凭空消失。
 */
class PublicationService(
    private val db: MuseDb,
    private val repository: AgentBotRepository,
    private val sessionRepository: SessionRepository,
) {

    /** 一条待发布 item。 */
    data class Item(
        val kind: String,
        /** 文本内容或资源引用。资源类由调用方先落地再传引用。 */
        val payload: String,
    )

    /** 发布结果。 */
    data class Result(
        /** 实际落进公共消息的条数。 */
        val committed: Int,
        /** 因新输入到达而被放弃的条数。 */
        val superseded: Int,
        /** 失败的条数；非 0 时界面应给重试入口，不用私有文本兜底。 */
        val failed: Int,
        /** 已提交的消息 id，按 item 顺序。 */
        val messageIds: List<String>,
    ) {
        val allCommitted: Boolean get() = failed == 0 && superseded == 0
    }

    /**
     * 提交一批对外 item。
     *
     * [requestRevision] 用于判断"这批是否已过期"：如果用户在这批生成期间又说了话，
     * 旧批次不该继续发布 —— 主 Bot 会自相矛盾。
     *
     * @param currentRequestRevision 当前用户输入版本；传入时与 [requestRevision] 比对
     */
    suspend fun publish(
        botId: String,
        originSessionId: String,
        causeEventIds: String,
        requestRevision: Long,
        taskRevision: Int,
        items: List<Item>,
        currentRequestRevision: Long = requestRevision,
    ): Result = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result(0, 0, 0, emptyList())
        if (originSessionId.isBlank()) {
            Logger.e("PublicationService", "拒绝发布：缺少 originSessionId")
            return@withContext Result(0, 0, items.size, emptyList())
        }

        // 新输入已到达：整批放弃。已经发出的条目不受影响（这里还没发任何一条）。
        if (currentRequestRevision != requestRevision) {
            Logger.i("PublicationService", "新输入已到达，放弃本批: rev=$requestRevision -> $currentRequestRevision")
            return@withContext Result(0, items.size, 0, emptyList())
        }

        val publicationId = repository.enqueuePublication(
            botId = botId,
            originSessionId = originSessionId,
            causeEventIds = causeEventIds,
            requestRevision = requestRevision,
            taskRevision = taskRevision,
            items = items.map { it.kind to it.payload },
        )

        commitPending(publicationId, originSessionId)
    }

    /**
     * 恢复：把某批次里还没发出的条目补发。
     *
     * 只重投 `PENDING`。
     *
     * `COMMITTED` 与 `SUPERSEDED` 天然不动 —— 这里的 filter 只挑 `PENDING`，
     * 另外两种状态不会被选中。不需要额外的排除逻辑，但**依赖 filter 是唯一入口**。
     */
    suspend fun resumePublication(publicationId: String): Result = withContext(Dispatchers.IO) {
        val pending = db.publicationOutboxDao().listByPublication(publicationId)
            .filter { it.status == PublicationStatus.PENDING }
        if (pending.isEmpty()) return@withContext Result(0, 0, 0, emptyList())
        commitPending(publicationId, pending.first().originSessionId)
    }

    /**
     * 把 PENDING 条目逐条提交。
     *
     * 每条 item 一个事务：一条失败不影响已成功的那些 —— 用户至少能看到前半句，
     * 而不是整批消失。
     */
    private suspend fun commitPending(publicationId: String, sessionId: String): Result {
        val pending = db.publicationOutboxDao().listByPublication(publicationId)
            .filter { it.status == PublicationStatus.PENDING }
        var committed = 0
        var failed = 0
        val messageIds = mutableListOf<String>()

        for (item in pending) {
            // 用 kotlin.uuid.Uuid 生成，与 UIMessage.id 同一套类型。
            //
            // 之前用 java.util.UUID.randomUUID().toString()：格式上兼容，
            // 但 SessionRepository 读回时走 Uuid.parse，解析失败会**静默回退成随机 id**
            // —— 那样重建出来的 messageId 与库里的对不上，去重失效、消息可能重复。
            // 用同一个生成器就从源头消除了这个风险。
            val messageId = kotlin.uuid.Uuid.random().toString()
            val ok = runCatching {
                db.withTransaction {
                    // 事务内先核对该 item 仍是 PENDING：并发恢复时另一个 worker 可能已提交。
                    val still = db.publicationOutboxDao().markCommitted(
                        id = item.id,
                        status = PublicationStatus.COMMITTED,
                        messageId = messageId,
                    )
                    if (still == 0) return@withTransaction false
                    // 直接用 DAO 而不是 sessionRepository.appendMessage：
                    // 后者内部会再开一次 withTransaction，与外层事务嵌套后
                    // 写入不随外层提交 —— 表现为 outbox 已标 COMMITTED 但消息没落库。
                    //
                    // ## seq / commitSeq 必须自己算，不能传 0
                    //
                    // 审查 P1-7：这里原本传 `seq = 0L`，注释写"交给 DAO 侧自增语义"。
                    // 但 `messageDao().upsert` 是 `@Insert(REPLACE)`，**没有自增语义**；
                    // 补 seq 的是 `SessionRepository.appendMessage`，而这里绕过了它。
                    //
                    // 结果：bot 消息 seq=0，而所有正常消息 seq>=1。
                    // 排序键是 `CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END`，
                    // seq=0 把它排到最前；`getRecentBySession` 是 DESC + LIMIT，
                    // 长会话里 bot 消息被挤出窗口 → 用户「消息丢了」。
                    val nextSeq = db.messageDao().getMaxSeq(sessionId) + 1
                    db.messageDao().upsert(
                        io.zer0.muse.data.session.MessageEntity(
                            id = messageId,
                            sessionId = sessionId,
                            role = MessageRole.ASSISTANT.name,
                            content = item.payloadRef,
                            reasoning = null,
                            thinkingSignature = null,
                            thinkingEncryptedContent = null,
                            modelId = null,
                            createdAt = System.currentTimeMillis(),
                            seq = nextSeq,
                            commitSeq = nextSeq,
                            imageUrlsJson = "[]",
                            voiceUri = null,
                            voiceDurationMs = 0L,
                            favorite = false,
                            favoriteTag = null,
                            citationUrlsJson = "[]",
                            ragCitationsJson = "[]",
                            imageBase64Json = "[]",
                        ),
                    )
                    true
                }
            }.onFailure {
                Logger.w("PublicationService", "发布 item 失败: ${item.id}", it)
            }.getOrDefault(false)

            if (ok) {
                committed++
                messageIds += messageId
            } else {
                failed++
            }
        }

        return Result(committed = committed, superseded = 0, failed = failed, messageIds = messageIds)
    }

    /**
     * 新输入到达时放弃未发布的旧批次。
     *
     * 已提交的条目不动 —— 用户看到的消息不能凭空消失。
     */
    suspend fun supersede(publicationId: String): Int = withContext(Dispatchers.IO) {
        repository.supersedePublication(publicationId)
    }

    /** 待发布的条目（诊断与恢复用）。 */
    suspend fun pendingItems(): List<PublicationOutboxEntity> = withContext(Dispatchers.IO) {
        repository.pendingPublications()
    }
}