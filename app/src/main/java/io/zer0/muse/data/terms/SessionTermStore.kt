package io.zer0.muse.data.terms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v2.7.x(E2): 术语的写入面。
 *
 * 这里唯一需要小心的不是"怎么写",而是**什么不许被覆盖**:
 *  - 用户确认过的 `confirmed` 不能被下一次复现打回候选;
 *  - 已经知道的 `meaning` 不能被后来的裸候选(只看到词、没看到解释)清空。
 * 两条都是"用户已经给出的信息被系统自己弄丢",比少记一条严重。
 * 所以这里坚持先查后写,不用 REPLACE。
 */
class SessionTermStore(private val dao: SessionTermDao) {

    /**
     * 记一次观测。**调用方保证每条用户消息只调一次** ——
     * 重复调用会把 occurrences 抬成"被处理过几次",
     * 而那个数字是用来决定要不要打扰用户确认的。
     */
    suspend fun recordObserved(
        sessionId: String,
        assistantId: String,
        hits: List<SessionTerms.TermHit>,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        hits.forEach { hit ->
            val existing = dao.find(SessionTermEntity.SCOPE_SESSION, sessionId, hit.term)
            if (existing == null) {
                dao.insert(
                    SessionTermEntity(
                        term = hit.term,
                        meaning = hit.meaning,
                        sessionId = sessionId,
                        assistantId = assistantId,
                        occurrences = 1,
                        firstSeenAt = now,
                        lastSeenAt = now,
                    ),
                )
            } else {
                dao.update(
                    existing.copy(
                        // 解释只增不清:后来的裸候选不该把已知含义抹掉
                        meaning = hit.meaning ?: existing.meaning,
                        occurrences = existing.occurrences + 1,
                        lastSeenAt = now,
                    ),
                )
            }
        }
    }

    /** 用户确认(可以同时补一条解释)。确认后才算"可以当既定说法用"。 */
    suspend fun confirm(id: Long, meaning: String? = null) = withContext(Dispatchers.IO) {
        val entity = dao.getById(id) ?: return@withContext false
        dao.update(entity.copy(confirmed = true, meaning = meaning?.takeIf { it.isNotBlank() } ?: entity.meaning))
        true
    }

    /** 提升为助手级。是用户的显式动作,系统不自动搬。 */
    suspend fun promoteToAssistant(id: Long) = withContext(Dispatchers.IO) {
        val entity = dao.getById(id) ?: return@withContext false
        // 同一个助手下这个词可能已经被别的会话提升过:保留先立的那一条(它的解释可能被用户改过),
        // 把这一条删掉。留两条同名助手级条目会在注入时随机挤掉一条。
        val twin = dao.findAssistantScoped(entity.assistantId, entity.term)
        if (twin != null && twin.id != entity.id) {
            dao.delete(entity)
            return@withContext true
        }
        dao.update(entity.copy(scope = SessionTermEntity.SCOPE_ASSISTANT, confirmed = true))
        true
    }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) {
        val entity = dao.getById(id) ?: return@withContext false
        dao.delete(entity)
        true
    }

    /** 注入用:已确认优先,限 [SessionTerms.MAX_INJECT] 条。 */
    suspend fun termsForContext(sessionId: String, assistantId: String) = withContext(Dispatchers.IO) {
        dao.pickForContext(sessionId, assistantId, SessionTerms.MAX_INJECT)
    }

    suspend fun listSessionTerms(sessionId: String, limit: Int = 100) = withContext(Dispatchers.IO) {
        dao.listForScope(SessionTermEntity.SCOPE_SESSION, sessionId, limit)
    }

    /**
     * 术语页的取数:一个助手名下的**全部**条目(会话级 + 助手级)。
     *
     * 术语本来就带 assistant_id,按助手列是唯一能把两种作用域放在同一页上的口径;
     * 按会话列的话,设置页没有"当前会话",页面会退化成空列表。
     */
    suspend fun listByAssistantScope(assistantId: String, limit: Int = 200) = withContext(Dispatchers.IO) {
        dao.listByAssistant(assistantId, limit)
    }

    suspend fun listAssistantTerms(assistantId: String, limit: Int = 100) = withContext(Dispatchers.IO) {
        dao.listByAssistant(assistantId, limit)
    }

    /** 有多少词在等着用户确认(术语页与主动提示都用这一个数,不各自算一遍阈值)。 */
    suspend fun countAwaitingConfirmation() = withContext(Dispatchers.IO) {
        dao.countAwaitingConfirmation(SessionTerms.CONFIRM_THRESHOLD)
    }

    suspend fun deleteForSession(sessionId: String) = withContext(Dispatchers.IO) {
        dao.deleteForSession(sessionId)
    }
}
