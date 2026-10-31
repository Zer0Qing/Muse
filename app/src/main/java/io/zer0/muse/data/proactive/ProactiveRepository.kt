package io.zer0.muse.data.proactive

/**
 * 牵挂账本的记账与维护。
 *
 * 只关心「记什么、记到哪、什么时候该清掉」，不关心「该不该说」——
 * 那是判定引擎的事。这条边界让两边都能单独测：
 * 记账逻辑不需要构造一个可发送的场景，判定逻辑也不需要真的写库。
 */
class ProactiveThoughtRepository(
    private val readDao: PendingThoughtDao,
    private val writeDao: PendingThoughtWriteDao,
) {

    companion object {
        /** 同一件事说过这么多次还没反应，就放弃，不再念叨。 */
        const val MAX_SURFACE_COUNT = 4

        /** 已结束的牵挂保留天数。 */
        private const val RETENTION_DAYS = 30
    }

    /**
     * 记一条牵挂。
     *
     * [PendingThoughtEntity.sourceRef] 非空时做去重：同一来源只允许存在一条 OPEN 的牵挂。
     * 没有这道去重，同一条记忆会每轮被重新推导一次，账本一小时就能膨胀几十条。
     *
     * @return true = 真的记下了；false = 已存在同源 OPEN 项，跳过
     */
    suspend fun note(thought: PendingThoughtEntity): Boolean {
        val ref = thought.sourceRef
        if (!ref.isNullOrBlank() && readDao.countOpenBySourceRef(ref) > 0) return false
        readDao.upsert(thought)
        return true
    }

    /** 标记某条牵挂已说出口。 */
    suspend fun markSurfaced(id: String, at: Long) {
        writeDao.markSurfaced(id, at)
    }

    /** 用户已经把这件事解决了（由对话或用户手动标记）。 */
    suspend fun complete(id: String) {
        writeDao.updateStatus(id, ThoughtStatus.DONE.name)
    }

    /** 用户不关心这件事了。 */
    suspend fun drop(id: String) {
        writeDao.updateStatus(id, ThoughtStatus.DROPPED.name)
    }

    suspend fun getOpen(assistantId: String): List<PendingThoughtEntity> = readDao.getOpen(assistantId)

    suspend fun getAllOpen(): List<PendingThoughtEntity> = readDao.getAllOpen()

    suspend fun getRecent(limit: Int = 30): List<PendingThoughtEntity> = readDao.getRecent(limit)

    suspend fun countOpen(assistantId: String): Int = readDao.countOpen(assistantId)

    /** 已记账的来源 id 集合，供推导时批量去重。 */
    suspend fun getOpenSourceRefs(assistantId: String): Set<String> =
        readDao.getOpenSourceRefs(assistantId).toSet()

    /**
     * 例行清理：过期、念叨太多次、太久远的已结束记录。
     *
     * 应该在每次调度前跑一遍，而不是单独定时 —— 它很便宜（两条 UPDATE），
     * 而「过期了但状态还是 OPEN」会让判定引擎反复处理同一批死数据。
     */
    suspend fun maintenance(now: Long, outcomeDao: ProactiveOutcomeDao? = null): MaintenanceResult {
        val expired = writeDao.expireOverdue(now)
        val dropped = writeDao.dropOverSurfaced(MAX_SURFACE_COUNT)
        val deletedThoughts = writeDao.deleteClosedBefore(now - RETENTION_DAYS * 86_400_000L)
        val staleOutcomes = outcomeDao?.expireStale(now - RETENTION_DAYS * 86_400_000L) ?: 0
        val deletedOutcomes = outcomeDao?.deleteBefore(now - RETENTION_DAYS * 86_400_000L) ?: 0
        return MaintenanceResult(expired, dropped, staleOutcomes, deletedThoughts, deletedOutcomes)
    }

    data class MaintenanceResult(
        val expired: Int,
        val dropped: Int,
        val staleOutcomes: Int,
        val deletedThoughts: Int,
        val deletedOutcomes: Int,
    ) {
        val hasAny: Boolean
            get() = (expired + dropped + staleOutcomes + deletedThoughts + deletedOutcomes) > 0
    }
}

/**
 * 关系亲密度与当日预算。
 *
 * 跨日重置放在这一层而不是 DAO：「今天是哪天」是业务语义，
 * 存储层不该知道。放在这里也让「重置」和「读取」天然成对，
 * 不会出现读到了昨天的计数却忘了清零的情况。
 */
class ProactiveRelationRepository(
    private val dao: ProactiveRelationDao,
) {

    companion object {
        private const val DAY_MS = 86_400_000L

        private fun dateKey(now: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(now))
    }

    /**
     * 取关系状态，不存在则按默认值创建。
     * 顺带做跨日预算重置：新的一天，额度和回补都从零开始。
     */
    suspend fun getOrCreate(assistantId: String, now: Long): ProactiveRelationEntity {
        val today = dateKey(now)
        val existing = dao.get(assistantId)
        val needsReset = existing != null && ProactiveTimingEngine.needsBudgetReset(existing, today)
        if (needsReset) dao.resetBudget(assistantId, today)
        val result = when {
            existing == null -> ProactiveRelationEntity(assistantId = assistantId, budgetDate = today)
            needsReset -> existing.copy(budgetDate = today, spentToday = 0, earnedToday = 0f)
            else -> existing
        }
        if (existing == null) dao.upsert(result)
        return result
    }

    /** 发出一条主动消息后消耗额度。 */
    suspend fun consume(assistantId: String) {
        dao.incrementSpent(assistantId)
    }

    /**
     * 用户说「最近别烦我」。
     *
     * 同时把亲密度打一记重扣：这不是一次普通的划掉，是显式的拒绝。
     * 冷却结束后额度也不会立刻回到原样，得靠后续互动慢慢挣回来。
     */
    suspend fun startCooldown(assistantId: String, now: Long, days: Int = 7) {
        dao.updateCooldown(assistantId, now + days * DAY_MS)
        val relation = getOrCreate(assistantId, now)
        dao.updateIntimacy(assistantId, (relation.intimacy - 0.3f).coerceIn(0f, 1f))
    }

    /**
     * 取消冷却。
     *
     * 冷却本来是个「说一次就管七天」的强动作，但它写在界面上是个单程按钮。
     * 点了想反悔没有出口 —— 用户只能等，或者去关掉整个主动消息开关。
     * 这两种代价都远大于「点错了」本身。
     */
    suspend fun clearCooldown(assistantId: String) {
        dao.updateCooldown(assistantId, 0L)
    }

    suspend fun setIntensity(assistantId: String, level: ProactiveIntensity) {
        dao.updateIntensity(assistantId, level.name)
    }

    /**
     * 重置主动等级：档位回默认，同时把今天的额度记账清零。
     *
     * 只回档位不清账的话，用户从「黏人」切回「标准」会发现今天依旧没额度 ——
     * 上一档已经花掉的名额会继续占着新档位的额度，看起来就像「重置没生效」。
     */
    suspend fun resetIntensity(assistantId: String, today: String) {
        dao.updateIntensity(assistantId, ProactiveIntensity.DEFAULT.name)
        dao.resetBudget(assistantId, today)
    }

    /** 直接写亲密度。只给结果回写用，界面上不暴露。 */
    suspend fun setIntimacy(assistantId: String, intimacy: Float) {
        dao.updateIntimacy(assistantId, intimacy.coerceIn(0f, 1f))
    }

    suspend fun get(assistantId: String): ProactiveRelationEntity? = dao.get(assistantId)
}

/**
 * 主动消息的结果账。
 *
 * 结果与亲密度必须一起变：只记结果不改亲密度，学习等于没发生；
 * 只改亲密度不记结果，就回溯不了「它为什么变得这么克制」。
 */
class ProactiveOutcomeRepository(
    private val dao: ProactiveOutcomeDao,
    private val relations: ProactiveRelationRepository,
) {

    companion object {
        /**
         * 发出多久还没任何反应就算过期。
         *
         * 一天的尺度是「今天没看到就是没看到」：一条主动消息挂上两三天再收尾，
         * 学习结果是滞后两三天才拿到的；而太早收尾会把「明天早上才看到」当成没兴趣。
         */
        const val EXPIRE_AFTER_HOURS = 24

        /** 同上，毫秒。 */
        const val EXPIRE_AFTER_MS = EXPIRE_AFTER_HOURS * 3_600_000L
    }

    /**
     * 记一条刚发出去的消息，状态为 PENDING。
     *
     * @return 新建记录的 id。通知上的「这次不用」按钮靠它回到同一条记录，
     *         所以不能只写不管 —— 内部生成的主键必须回传给调用方。
     */
    suspend fun recordSent(
        thought: PendingThoughtEntity?,
        sessionId: String,
        now: Long,
        assistantId: String = "",
        messageId: String? = null,
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        dao.upsert(
            ProactiveOutcomeEntity(
                id = id,
                // 牵挂是可选的：现在大多数主动消息来自「接着对话说」，
                // 并没有对应的账本条目。
                thoughtId = thought?.id,
                assistantId = thought?.assistantId ?: assistantId,
                sessionId = sessionId,
                kind = thought?.kind ?: ThoughtKind.FOLLOW_UP.name,
                sentAt = now,
                state = OutcomeState.PENDING.name,
                // 存下来才能在下一轮反查出「上次说了什么」，用于防重复。
                messageId = messageId,
            ),
        )
        return id
    }

    /**
     * 更新一条结果的去向，并同步调整亲密度。
     *
     * 已经终结的状态不会被降级覆盖（回复之后再划掉通知仍是 REPLIED）——
     * 否则用户点开读过又顺手清通知，会被记成一次负反馈。
     */
    suspend fun resolve(outcomeId: String, state: OutcomeState, now: Long) {
        val current = dao.getById(outcomeId) ?: return
        val isTerminal = current.stateEnum() == state ||
            current.stateEnum() == OutcomeState.REPLIED ||
            state == OutcomeState.PENDING
        if (!isTerminal) applyTransition(current, state, now)
    }

    private suspend fun applyTransition(current: ProactiveOutcomeEntity, state: OutcomeState, now: Long) {
        dao.upsert(current.stamped(state, now))
        val relation = relations.getOrCreate(current.assistantId, now)
        val next = ProactiveLearning.applyOutcome(relation.intimacy, state)
        if (next != relation.intimacy) relations.setIntimacy(current.assistantId, next)
    }

    /** 把时间戳写到对应的列上。状态列总是更新，时间列按状态分。 */
    private fun ProactiveOutcomeEntity.stamped(state: OutcomeState, now: Long): ProactiveOutcomeEntity =
        when (state) {
            OutcomeState.OPENED -> copy(state = state.name, openedAt = now)
            OutcomeState.REPLIED -> copy(state = state.name, repliedAt = now)
            OutcomeState.DISMISSED -> copy(state = state.name, dismissedAt = now)
            OutcomeState.EXPIRED, OutcomeState.PENDING -> copy(state = state.name)
        }

    /** 找某会话里最近一条还在等反应的主动消息，用于回复检测与「打开即已读」。 */
    suspend fun findPendingInSession(sessionId: String): ProactiveOutcomeEntity? =
        dao.findPendingInSession(sessionId)

    /**
     * 把发出去超过 [EXPIRE_AFTER_MS] 仍然没人理的记录收尾成 EXPIRED。
     *
     * 交给 [resolve] 逐条走，而不是直接一条 UPDATE 改状态：收尾也是一次反馈，
     * 亲密度得跟着掉一点点；只改状态的话，这条「发出去没人理」在关系上等于没发生。
     *
     * @return 本次收尾的条数
     */
    suspend fun expireOverdue(now: Long): Int {
        val stale = dao.listPendingBefore(now - EXPIRE_AFTER_MS)
        stale.forEach { resolve(it.id, OutcomeState.EXPIRED, now) }
        return stale.size
    }

    /**
     * 这些类型里哪些已经被用户用脚投票否决了。
     *
     * 一次把候选涉及的类型问完，而不是逐条牵挂查库：候选可能有十几条，
     * 而类型只有那么几种，按类型判定的结果天然可以复用。
     */
    suspend fun unpopularKinds(assistantId: String, kinds: Collection<ThoughtKind>): Set<ThoughtKind> =
        kinds.filterTo(mutableSetOf()) { isKindUnpopular(assistantId, it) }

    suspend fun getRecent(assistantId: String, limit: Int = 20): List<ProactiveOutcomeEntity> =
        dao.getRecent(assistantId, limit)

    /**
     * 某类型是否已经被用户用脚投票否决了。
     *
     * 用途：用户对里程碑从来不感冒时，就别再发成就播报。
     * 判据本身（样本量下限、划掉率过半）在 [ProactiveLearning] 里，这里只取数。
     */
    suspend fun isKindUnpopular(assistantId: String, kind: ThoughtKind): Boolean {
        val dismissed = dao.countKindState(assistantId, kind.name, OutcomeState.DISMISSED.name)
        val total = dismissed +
            dao.countKindState(assistantId, kind.name, OutcomeState.REPLIED.name) +
            dao.countKindState(assistantId, kind.name, OutcomeState.OPENED.name) +
            dao.countKindState(assistantId, kind.name, OutcomeState.EXPIRED.name)
        if (total == 0) return false
        return ProactiveLearning.isKindUnpopular(dismissed, total)
    }
}
