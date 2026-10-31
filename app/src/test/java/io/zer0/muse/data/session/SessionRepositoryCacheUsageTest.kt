package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * 缓存读/写分列的持久化往返。
 *
 * 覆盖两件事：
 *  - 新记录：`cachedTokens`(读) 与 `cacheWriteTokens`(写) 各进各列，读回来都不丢；
 *  - 旧记录：拆分前的行只有 `cachedTokens`（装的是读 + 写合并值），读取不崩、按「读」解释，
 *    `cacheWriteTokens` 保持 NULL（未知），不回填、不重算。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionRepositoryCacheUsageTest {

    private lateinit var context: Context
    private lateinit var database: MuseDb
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        repository = SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            database = database,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "cache-usage-${UUID.randomUUID()}")),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `new message persists cache read and cache write separately`() = runTest {
        val sessionId = newSession("rt")
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "回答",
                createdAt = System.currentTimeMillis(),
                promptTokens = 1_000,
                completionTokens = 200,
                reasoningTokens = 0,
                cachedTokens = 5_000,
                cacheWriteTokens = 2_000,
            )

        val id = message.id.toString()
        repository.upsertMessage(sessionId, message, skipFts = true)
        val entity = database.messageDao().getByMessageId(id)

        assertNotNull(entity)
        assertEquals("缓存读取落 cachedTokens", 5_000, entity!!.cachedTokens)
        assertEquals("缓存写入落 cacheWriteTokens", 2_000, entity.cacheWriteTokens)
        // 既有口径不动
        assertEquals(1_000, entity.promptTokens)
        assertEquals(200, entity.completionTokens)

        val restored = repository.getMessageAsUiMessage(id)
        assertNotNull(restored)
        assertEquals(5_000, restored!!.cachedTokens)
        assertEquals(2_000, restored.cacheWriteTokens)
    }

    @Test
    fun `legacy row with only cached tokens reads as cache read without crashing`() = runTest {
        val sessionId = newSession("legacy")
        // 拆分前的行：只有合并值，cacheWriteTokens 列为 NULL
        val legacy = MessageEntity(
            id = "legacy-${UUID.randomUUID()}",
            sessionId = sessionId,
            role = MessageRole.ASSISTANT.name,
            content = "旧回复",
            createdAt = System.currentTimeMillis(),
            promptTokens = 300,
            completionTokens = 50,
            reasoningTokens = 0,
            cachedTokens = 700,
        )
        database.messageDao().upsert(legacy)

        val entity = database.messageDao().getByMessageId(legacy.id)
        assertNotNull(entity)
        assertEquals("旧行的合并值原样保留，不回填", 700, entity!!.cachedTokens)
        assertNull("旧行没有写入量记录，应为 NULL 而不是 0", entity.cacheWriteTokens)

        val restored = repository.getMessageAsUiMessage(legacy.id)
        assertNotNull(restored)
        assertEquals(700, restored!!.cachedTokens)
        assertNull(restored.cacheWriteTokens)
    }

    @Test
    fun `openai message keeps cache write null`() = runTest {
        val sessionId = newSession("openai")
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "回答",
                createdAt = System.currentTimeMillis(),
                promptTokens = 1_000,
                completionTokens = 100,
                cachedTokens = 800,
                cacheWriteTokens = 0,
            )

        val id = message.id.toString()
        repository.upsertMessage(sessionId, message, skipFts = true)
        val entity = database.messageDao().getByMessageId(id)

        assertEquals(800, entity!!.cachedTokens)
        // OpenAI 没有缓存写入：显式 0，与旧行的 NULL（未知）区分开
        assertEquals(0, entity.cacheWriteTokens)
    }

    private suspend fun newSession(tag: String): String {
        val now = System.currentTimeMillis()
        val id = "session-cache-$tag-$now"
        database.sessionDao().insert(SessionEntity(id = id, title = tag, createdAt = now, updatedAt = now))
        return id
    }
}
