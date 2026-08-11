package io.zer0.muse.data.diary

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.0.74: AI 日记仓库。
 */
class DiaryRepository(
    private val dao: DiaryDao,
) {
    private val TAG = "DiaryRepo"

    suspend fun getByDate(date: String): DiaryEntity? = withContext(Dispatchers.IO) {
        resultOf { dao.getByDate(date) }.getOrNull()
    }

    /** 某月全部日记(map: date → content)。 */
    suspend fun getByMonth(year: Int, month: Int): Map<String, String> = withContext(Dispatchers.IO) {
        val start = java.time.LocalDate.of(year, month, 1)
        val next = start.plusMonths(1)
        resultOf {
            dao.getByMonth(start.toString(), next.toString())
                .associate { it.date to it.content }
        }.getOrNull() ?: emptyMap()
    }

    suspend fun save(date: String, content: String) {
        withContext(Dispatchers.IO) {
            resultOf {
                dao.upsert(
                    DiaryEntity(
                        date = date,
                        content = content,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }.onError { msg, t -> Logger.w(TAG, "保存日记失败: ${t?.message ?: msg}") }
        }
    }
}
