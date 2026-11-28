package io.zer0.muse.data.agentbot

import androidx.room.withTransaction
import io.zer0.ai.core.MessageRole
import io.zer0.common.Logger
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    sessionRepository: SessionRepository,
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
     * 一条发布事实的作用域。
     *
     * 不能只传 botId：审核后的发布要能回溯到具体任务与轮次，
     * 否则出问题时说不清"这句话是哪一次执行、哪一次审核的结果"。
     */
    data class PublicationScope(
        val botId: String,
        val originSessionId: String,
        /** 产生这条事实的任务；空表示与任务无关的对话发布。 */
        val taskId: String = "",
        val runId: String = "",
        val taskRevision: Int = 0,
    )

    /**
     * 审核允许之后才存在的发布内容。
     *
     * [text] 只能来自审核事实。子 Agent 的自述、工具回执、模型正文都不该出现在这里。
     */
    data class PublishedContent(
        val text: String,
        val kind: String = PublicationKind.TEXT,
    )

    /**
     * 幂等发布的结果。
     *
     * [created] 与 [messageIds] 分开：重复调用返回的是"已有事实"，
     * 调用方据此区分"这次真的发了"和"早就发过了"，而不是去数消息。
     */
    data class PublicationResult(
        val publicationKey: String,
        val messageIds: List<String>,
        /** true = 本次新建并落库；false = 复用了已有发布事实。 */
        val created: Boolean,
        val failed: Int = 0,
        val superseded: Int = 0,
        /**
         * 复核不通过的原因；空表示这次不是"被拒绝"。
         *
         * 与 [failed] 分开：`failed` 是"该发但没发成，值得重试"，
         * `refusedReason` 是"依据不足，重试也不会变" —— 消费方据此决定
         * 是留着重试还是确认消费，避免无依据的事实长期占住收件箱。
         */
        val refusedReason: String = "",
    ) {
        val committed: Int get() = messageIds.size
        val allCommitted: Boolean
            get() = failed == 0 && superseded == 0 && messageIds.isNotEmpty()
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
        reasoningByItem: List<String?> = emptyList(),
        reasoningDurationMsByItem: List<Long?> = emptyList(),
        moodByItem: List<String?> = emptyList(),
        citationsByItem: List<List<String>> = emptyList(),
    ): Result = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result(0, 0, 0, emptyList())
        if (originSessionId.isBlank()) {
            Logger.e("PublicationService", "拒绝发布：缺少 originSessionId")
            return@withContext Result(0, 0, items.size, emptyList())
        }
        db.withTransaction {
            val persistedRevision = if (requestRevision > 0L) {
                db.botEventDao().latestUserInputSequence(botId, originSessionId) ?: 0L
            } else {
                requestRevision
            }
            val latestRevision = maxOf(currentRequestRevision, persistedRevision)
            // 新输入已到达：整批放弃。已经发出的条目不受影响（这里还没发任何一条）。
            if (latestRevision != requestRevision) {
                Logger.i("PublicationService", "新输入已到达，放弃本批: rev=$requestRevision -> $latestRevision")
                return@withTransaction Result(0, items.size, 0, emptyList())
            }

            val publicationId = repository.enqueuePublication(
                botId = botId,
                originSessionId = originSessionId,
                causeEventIds = causeEventIds,
                requestRevision = requestRevision,
                taskRevision = taskRevision,
                items = items.map { it.kind to it.payload },
            )
            commitPending(
                publicationId = publicationId,
                sessionId = originSessionId,
                reasoningByItem = reasoningByItem,
                reasoningDurationMsByItem = reasoningDurationMsByItem,
                moodByItem = moodByItem,
                citationsByItem = citationsByItem,
            )
        }
    }

    /**
     * 按稳定 publicationKey 发布一条审核通过的内容。
     *
     * ## 为什么必须有这一条
     *
     * 发布链路是"写发件箱 + 写消息 + 确认事件"三步，进程可能在任意两步之间被杀。
     * 恢复后重投同一条事实时，[publish] 会再生成一个随机批次，于是用户看到两条
     * 一模一样的话。这里用 key 把"同一条事实"钉成一个固定批次：
     *
     *  1. `publicationId` 由 key 决定，不是随机 UUID；
     *  2. 批次里已经有行时不再创建（已 COMMITTED 的行绝不能被重置成 PENDING）；
     *  3. 提交复用 [commitPending]：事务内先 CAS 标记 COMMITTED 再写消息，
     *     并发下只有一个能成功，另一个回头读到已有 messageId。
     *
     * 因此重复调用返回的是**已有的 messageId**，消息与发件箱都只有一份。
     */
    suspend fun publishIdempotent(publicationKey: String, scope: PublicationScope, content: PublishedContent): PublicationResult {
        // 与 [publish] 一样切到 IO：写入的是 Room，不该占调用方的线程。
        return withContext(Dispatchers.IO) { publishLocked(publicationKey, scope, content) }
    }

    /**
     * 消费一条审核允许的发布事实。
     *
     * ## 为什么消费前还要复核一遍
     *
     * 发布事实是生成那一刻的快照。它在收件箱里排队时，任务可能已经被取消、
     * 被追加约束（revision 变了），或者审核又判了一次返工。
     * 只信事件自带的 `publicationAllowed`，等于允许一条过期事实以审核的名义发言。
     *
     * 所以这里重新读库核对审核结论、作用域、任务当前状态与轮次版本；
     * 任何一项对不上就不写任何东西，并带上 [PublicationResult.refusedReason]。
     */
    suspend fun publishReviewed(decision: ReviewDecision, scope: PublicationScope): PublicationResult = withContext(Dispatchers.IO) {
        db.withTransaction {
            val refusal = reviewRefusal(decision, scope)
            if (refusal != null) {
                Logger.e(
                    "PublicationService",
                    "拒绝发布: $refusal (task=${scope.taskId}, key=${decision.publicationKey})",
                )
                return@withTransaction PublicationResult(
                    publicationKey = decision.publicationKey,
                    messageIds = emptyList(),
                    created = false,
                    refusedReason = refusal,
                )
            }
            publishLocked(
                publicationKey = decision.publicationKey,
                scope = scope,
                content = PublishedContent(text = decision.userVisibleSummary),
            )
        }
    }

    /**
     * 发布前复核。返回 null 表示可以发，否则返回拒绝原因。
     *
     * 判据顺序按"最可能过期的在前"：审核结论 → 作用域 → 任务状态 → 轮次版本 → 落库的审核事实。
     */
    private suspend fun reviewRefusal(decision: ReviewDecision, scope: PublicationScope): String? {
        if (decision.disposition != ReviewDisposition.ACCEPT) {
            return "审核结论是 ${decision.disposition}，不是已完成"
        }
        if (!decision.publicationAllowed) return "审核没有允许发布"
        if (decision.publicationKey.isBlank()) return "缺少发布键"
        if (decision.userVisibleSummary.isBlank()) return "审核摘要为空"
        if (scope.taskId.isBlank() || scope.runId.isBlank()) return "发布事实缺少任务或轮次"
        if (scope.taskRevision != decision.taskRevision) return "发布作用域任务版本不一致"
        if (decision.finalState != BotTaskStatus.SUCCEEDED) return "审核终态不是成功"
        val expectedKey = publicationKeyFor(scope.taskId, scope.runId, decision.taskRevision)
        if (decision.publicationKey != expectedKey) return "发布键与任务、轮次或版本不一致"

        val task = repository.getTask(scope.taskId) ?: return "任务不存在"
        if (task.originSessionId != scope.originSessionId) {
            return "作用域不符：任务属于 ${task.originSessionId}，本次面向 ${scope.originSessionId}"
        }
        if (task.revision != decision.taskRevision) {
            return "任务版本已变（当前=${task.revision}, 审核=${decision.taskRevision}）"
        }
        // 取消/返工之后仍可能留着一条待消费的发布事实；用户已被告知"停了"，
        // 这时再说"已完成"是自相矛盾。
        if (task.status != BotTaskStatus.SUCCEEDED) {
            return "任务当前状态是 ${task.status}，不能对外声称已完成"
        }

        val run = repository.listRuns(scope.taskId).firstOrNull { it.runId == scope.runId }
            ?: return "轮次不属于该任务"
        if (run.taskRevision != decision.taskRevision) {
            return "轮次版本已变（run=${run.taskRevision}, 事实=${decision.taskRevision}）"
        }

        // 最后一条审核事实才是依据：发布事实之后若又落了别的结论，以新结论为准。
        val fact = db.botEventDao()
            .latestOfKind(scope.taskId, scope.runId, BotEventKind.REVIEW_READY)
            ?.let { ReviewDecision.decode(it.payloadRef) }
            ?: return "找不到审核事实"
        if (!fact.publicationAllowed || fact.disposition != ReviewDisposition.ACCEPT) {
            return "最后一条审核结论不允许发布（${fact.disposition}）"
        }
        if (fact.publicationKey != decision.publicationKey) return "发布键与审核事实不一致"
        if (fact != decision) return "审核事实与待发布结论不一致"
        if (fact.finalState != task.status) return "审核终态与任务状态不一致"
        return null
    }

    /** 幂等发布的实际写入步骤。公开入口只负责切线程，写入逻辑单独成函数便于阅读。 */
    private suspend fun publishLocked(publicationKey: String, scope: PublicationScope, content: PublishedContent): PublicationResult {
        require(publicationKey.isNotBlank()) { "发布键不能为空：没有键就分不清是不是同一条事实" }
        require(content.kind in PublicationKind.all) { "非法发布类型: ${content.kind}" }
        if (scope.originSessionId.isBlank()) {
            Logger.e("PublicationService", "拒绝发布：缺少 originSessionId（key=$publicationKey）")
            return PublicationResult(publicationKey, emptyList(), created = false, failed = 1)
        }
        val publicationId = publicationIdFor(publicationKey)

        // 先看有没有已结算的事实：有就原样返回，不再写任何东西。
        settledResult(publicationId, publicationKey)?.let { return it }

        // 只在批次为空时创建。用 upsert 重建会把已 COMMITTED 的行打回 PENDING，
        // 下次"恢复"就会再发一遍 —— 那正是幂等要防的。
        db.withTransaction {
            val existing = db.publicationOutboxDao().listByPublication(publicationId)
            if (existing.isEmpty()) {
                db.publicationOutboxDao().upsert(
                    PublicationOutboxEntity(
                        id = "$publicationId:$FIRST_ITEM_INDEX",
                        publicationId = publicationId,
                        botId = scope.botId,
                        originSessionId = scope.originSessionId,
                        // 触发这次发布的是审核事实，不是某条事件 id；留空而不是编一个。
                        causeEventIds = "",
                        // 后台结果不属于任何一次用户输入，不参与"新输入到达就放弃"的判定。
                        requestRevision = 0L,
                        taskRevision = scope.taskRevision,
                        itemIndex = FIRST_ITEM_INDEX,
                        kind = content.kind,
                        payloadRef = content.text,
                        status = PublicationStatus.PENDING,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

        val attempt = commitPending(publicationId, scope.originSessionId)
        val rows = db.publicationOutboxDao().listByPublication(publicationId)
        val committedIds = rows
            .filter { it.status == PublicationStatus.COMMITTED && it.messageId.isNotBlank() }
            .map { it.messageId }
        if (committedIds.isEmpty()) {
            Logger.w(
                "PublicationService",
                "发布未成功（key=$publicationKey, attempt=${attempt.committed}/${attempt.failed}）",
            )
            return PublicationResult(
                publicationKey = publicationKey,
                messageIds = emptyList(),
                created = false,
                failed = rows.count { it.status == PublicationStatus.PENDING },
                superseded = rows.count { it.status == PublicationStatus.SUPERSEDED },
            )
        }
        // attempt.committed == 0 但已有 messageId：并发下另一个 writer 先提交了，
        // 这里返回的是它的结果，不能算本次新建。
        return PublicationResult(
            publicationKey = publicationKey,
            messageIds = committedIds,
            created = attempt.committed > 0,
            failed = rows.count { it.status == PublicationStatus.PENDING },
            superseded = rows.count { it.status == PublicationStatus.SUPERSEDED },
        )
    }

    /** 已结算的发布事实：有 COMMITTED 返回其 messageId，被放弃则返回 superseded。 */
    private suspend fun settledResult(publicationId: String, publicationKey: String): PublicationResult? {
        val rows = db.publicationOutboxDao().listByPublication(publicationId)
        if (rows.isEmpty()) return null
        val committed = rows
            .filter { it.status == PublicationStatus.COMMITTED && it.messageId.isNotBlank() }
            .map { it.messageId }
        if (committed.isNotEmpty()) {
            return PublicationResult(publicationKey, committed, created = false)
        }
        if (rows.any { it.status == PublicationStatus.SUPERSEDED }) {
            return PublicationResult(publicationKey, emptyList(), created = false, superseded = 1)
        }
        return null
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
    private suspend fun commitPending(
        publicationId: String,
        sessionId: String,
        reasoningByItem: List<String?> = emptyList(),
        reasoningDurationMsByItem: List<Long?> = emptyList(),
        moodByItem: List<String?> = emptyList(),
        citationsByItem: List<List<String>> = emptyList(),
    ): Result {
        if (sessionRepository.getSessionById(sessionId) == null) {
            Logger.w(TAG, "发布目标会话不存在，保留待发布条目: $sessionId")
            return Result(0, 0, 0, emptyList())
        }
        val pending = db.publicationOutboxDao().listByPublication(publicationId)
            .filter { it.status == PublicationStatus.PENDING }
        var committed = 0
        var failed = 0
        val messageIds = mutableListOf<String>()

        for ((index, item) in pending.withIndex()) {
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
                            reasoning = reasoningByItem.getOrNull(index)?.takeIf { it.isNotBlank() },
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
                            citationUrlsJson = citationsByItem.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let {
                                io.zer0.common.AppJson.encodeToString(it)
                            } ?: "[]",
                            ragCitationsJson = "[]",
                            imageBase64Json = "[]",
                            mood = moodByItem.getOrNull(index)?.takeIf { it.isNotBlank() },
                            reasoningDurationMs = reasoningDurationMsByItem.getOrNull(index),
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

    companion object {
        /** 审核事实天然是一条消息，不拆成多个 item。 */
        private const val FIRST_ITEM_INDEX = 0

        /**
         * 由发布键推导固定批次 id。
         *
         * 带前缀是为了让幂等批次与普通 `bot_say` 批次（随机 UUID）在库里一眼可分 ——
         * 排查"这条为什么重复/为什么没发"时能立刻看出它走的是哪条链路。
         */
        private fun publicationIdFor(publicationKey: String): String = "pk:" + publicationKey

        /**
         * 审核事实的稳定发布键。
         *
         * 组成：任务 + 轮次 + 任务版本。同一轮同一版本的审核只对应一条对外消息；
         * 换轮次或换版本就是另一件事，允许各自拥有自己的消息。
         */
        fun publicationKeyFor(taskId: String, runId: String, taskRevision: Int): String =
            "task=" + taskId + ":run=" + runId + ":rev=" + taskRevision

        fun failurePublicationKeyFor(eventId: String): String = "event=" + eventId + ":failure"
    }
}
