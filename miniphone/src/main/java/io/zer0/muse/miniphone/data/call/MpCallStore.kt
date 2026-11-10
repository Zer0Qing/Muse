package io.zer0.muse.miniphone.data.call

import io.zer0.muse.miniphone.data.db.dao.MpCallSessionDao
import io.zer0.muse.miniphone.data.db.entity.MpCallSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 通话记录门面：挂断时落一行，会话内/全局历史读回，删会话级联清掉。
 *
 * 薄包 DAO，id 与时间由调用方给（沿用模块写入器的可测约定）。时长收非负：
 * 引擎算出的接通时长不该是负数，真出现负值就当未接通（0）落库，不写脏数据。
 */
internal class MpCallStore(
    private val dao: MpCallSessionDao,
) {
    fun observeByThread(threadId: String): Flow<List<MpCallSessionEntity>> = dao.observeByThread(threadId)

    fun observeHistory(limit: Int): Flow<List<MpCallSessionEntity>> = dao.observeHistory(limit)

    suspend fun record(id: String, threadId: String, characterId: String, kind: MpCallKind, startedAt: Long, durationMs: Long) {
        dao.insert(
            MpCallSessionEntity(
                id = id,
                threadId = threadId,
                characterId = characterId,
                kind = kind.wire,
                startedAt = startedAt,
                durationMs = durationMs.coerceAtLeast(0L),
            ),
        )
    }

    suspend fun deleteByThread(threadId: String) = dao.deleteByThread(threadId)
}
