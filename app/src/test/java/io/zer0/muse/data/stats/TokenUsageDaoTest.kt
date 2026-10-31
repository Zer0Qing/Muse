package io.zer0.muse.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * get_token_usage 依赖的查询口径（真 Room，不用替身）。
 *
 * 两件事分开钉：
 *  - usage_records 的既有聚合（totalsSince / bySourceSince / topModelsSince）确实按时间窗口过滤；
 *  - messages 的会话用量聚合只数「有实测 token 的助手消息」，剔除已删除与全零行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TokenUsageDaoTest {

    private lateinit var database: MuseDb

    private val dayMs = 24L * 60 * 60 * 1000
    private val base = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun record(
        createdAt: Long,
        source: String = UsageSource.CHAT,
        modelId: String = "m-1",
        prompt: Int = 1_000,
        completion: Int = 100,
    ) {
        database.usageRecordDao().insert(
            UsageRecordEntity(
                createdAt = createdAt,
                source = source,
                modelId = modelId,
                promptTokens = prompt,
                completionTokens = completion,
            ),
        )
    }

    @Test
    fun `totals respect the time window`() = runTest {
        record(createdAt = base)
        record(createdAt = base + dayMs, prompt = 2_000, completion = 200)

        val all = database.usageRecordDao().totalsSince(0L)
        assertEquals(2, all?.requests)
        assertEquals(3_000L, all?.promptTokens)
        assertEquals(300L, all?.completionTokens)

        val recent = database.usageRecordDao().totalsSince(base + dayMs)
        assertEquals(1, recent?.requests)
        assertEquals(2_000L, recent?.promptTokens)
    }

    @Test
    fun `source and model breakdown only see the window`() = runTest {
        record(createdAt = base, source = UsageSource.CHAT, modelId = "m-1")
        record(createdAt = base + dayMs, source = UsageSource.TITLE, modelId = "m-2")

        val sources = database.usageRecordDao().bySourceSince(base + dayMs)
        assertEquals(listOf(UsageSource.TITLE), sources.map { it.source })

        val models = database.usageRecordDao().topModelsSince(base + dayMs, 5)
        assertEquals(listOf("m-2"), models.map { it.modelId })
        assertEquals(1_100L, models.first().totalTokens)
    }

    @Test
    fun `session usage counts only assistant messages with measured tokens`() = runTest {
        database.sessionDao().insert(
            SessionEntity(id = "s-1", title = "s-1", createdAt = base, updatedAt = base, assistantId = "default"),
        )
        database.sessionDao().insert(
            SessionEntity(id = "s-2", title = "s-2", createdAt = base, updatedAt = base, assistantId = "default"),
        )
        message("a-1", "s-1", prompt = 1_000, completion = 100)
        message("a-2", "s-1", prompt = 2_000, completion = 200)
        // 全零行不该计入（usage_records 侧同样不记全零）
        message("a-zero", "s-1", prompt = 0, completion = 0)
        // 已删除消息不计入
        message("a-del", "s-1", prompt = 9_999, completion = 9_999, deletedAt = base + 10)
        // 其他会话不计入
        message("a-other", "s-2", prompt = 5_000, completion = 500)
        // 用户消息没有实测用量
        database.messageDao().upsert(
            MessageEntity(id = "u-1", sessionId = "s-1", role = "USER", content = "hi", createdAt = base),
        )

        val usage = database.messageDao().usageTotalsBySession("s-1")

        assertEquals(2, usage.requests)
        assertEquals(3_000L, usage.promptTokens)
        assertEquals(300L, usage.completionTokens)
    }

    @Test
    fun `session without measured usage returns zeros`() = runTest {
        database.sessionDao().insert(
            SessionEntity(id = "s-empty", title = "e", createdAt = base, updatedAt = base, assistantId = "default"),
        )

        val usage = database.messageDao().usageTotalsBySession("s-empty")

        assertEquals(0, usage.requests)
        assertEquals(0L, usage.promptTokens)
        assertEquals(0L, usage.completionTokens)
    }

    private suspend fun message(
        id: String,
        sessionId: String,
        prompt: Int,
        completion: Int,
        deletedAt: Long? = null,
    ) {
        database.messageDao().upsert(
            MessageEntity(
                id = id,
                sessionId = sessionId,
                role = "ASSISTANT",
                content = "reply",
                createdAt = base,
                deletedAt = deletedAt,
                promptTokens = prompt,
                completionTokens = completion,
            ),
        )
    }
}
