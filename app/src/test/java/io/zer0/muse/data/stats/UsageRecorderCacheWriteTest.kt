package io.zer0.muse.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 缓存读/写分列落库：写入量不能像拆分前那样被丢掉。
 *
 * 拆分前统计流水只有 `cached_tokens` 一列，装的是「读 + 写」合并值，
 * 缓存写入（Anthropic 按输入价 1.25 倍计费）因此永远进不了统计。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UsageRecorderCacheWriteTest {

    private class CapturingDao : UsageRecordDao {
        val inserted = mutableListOf<UsageRecordEntity>()

        override suspend fun insert(record: UsageRecordEntity) {
            inserted += record
        }

        override suspend fun insertAll(records: List<UsageRecordEntity>) {
            inserted += records
        }

        override suspend fun totalsSince(sinceMs: Long): UsageTotals? = null

        override suspend fun bySourceSince(sinceMs: Long): List<UsageBySource> = emptyList()

        override suspend fun topModelsSince(sinceMs: Long, limit: Int): List<UsageByModel> = emptyList()

        override suspend fun byDaySince(sinceMs: Long): List<UsageByDay> = emptyList()

        override suspend fun deleteBefore(beforeMs: Long): Int = 0

        override suspend fun count(): Int = inserted.size
    }

    @Test
    fun `cache read and cache write are recorded in separate columns`() = kotlinx.coroutines.runBlocking {
        val dao = CapturingDao()
        val recorder = UsageRecorder(dao)

        recorder.record(
            source = UsageSource.CHAT,
            modelId = "claude-sonnet-4",
            promptTokens = 1_000,
            completionTokens = 200,
            reasoningTokens = 0,
            cachedTokens = 5_000,
            cacheWriteTokens = 2_000,
        )

        assertEquals(1, dao.inserted.size)
        val row = dao.inserted.single()
        assertEquals("缓存读取单独一列", 5_000, row.cachedTokens)
        assertEquals("缓存写入单独一列，不能并进读取", 2_000, row.cacheWriteTokens)
        // 既有口径不动
        assertEquals(1_000, row.promptTokens)
        assertEquals(200, row.completionTokens)
    }

    @Test
    fun `openai style record leaves cache write at zero`() = kotlinx.coroutines.runBlocking {
        val dao = CapturingDao()
        val recorder = UsageRecorder(dao)

        recorder.record(
            source = UsageSource.CHAT,
            promptTokens = 1_000,
            completionTokens = 100,
            cachedTokens = 800,
        )

        val row = dao.inserted.single()
        assertEquals(800, row.cachedTokens)
        assertEquals("OpenAI 没有缓存写入计费项", 0, row.cacheWriteTokens)
    }
}
