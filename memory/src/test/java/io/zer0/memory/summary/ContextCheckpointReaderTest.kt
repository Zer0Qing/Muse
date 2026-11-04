package io.zer0.memory.summary

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话检查点的有效性判定（`:memory` 体检第十处）。
 *
 * 检查点说"这段话我已经摘要过到某条消息为止"。**判错的代价是用户可感知的**：
 * 历史被删过还沿用旧检查点，模型就会把"已经不存在的对话"又说成发生过。
 * 所以这里的规矩是宁可不用 —— 作废的同时还要把它删掉，不留一份会被反复误用的坏数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContextCheckpointReaderTest {

    private lateinit var db: MemoryDb
    private lateinit var reader: ContextCheckpointReader

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        reader = ContextCheckpointReader(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun checkpoint(
        sessionId: String = "s1",
        boundaryId: String = "m-9",
        summary: String = "前面聊过部署与回滚",
    ) = ContextCheckpointEntity(
        sessionId = sessionId,
        coveredSeq = 9L,
        lastCoveredMessageId = boundaryId,
        summary = summary,
        updatedAt = 1L,
    )

    @Test
    fun `会话 id 缺失或空白时不去查库`() = runBlocking {
        reader.put(checkpoint())

        assertNull(reader.get(null))
        assertNull(reader.get(""))
        assertNull(reader.get("   "))
        assertNull(reader.getValid(null, listOf("m-9")))
        assertNull(reader.getValid("  ", listOf("m-9")))
    }

    @Test
    fun `边界消息还在历史里时检查点可用`() = runBlocking {
        reader.put(checkpoint())

        val valid = reader.getValid("s1", listOf("m-8", "m-9", "m-10"))

        assertNotNull(valid)
        assertEquals("前面聊过部署与回滚", valid?.summary)
        assertNotNull("库里也该还在", db.contextCheckpointDao().get("s1"))
    }

    @Test
    fun `边界消息被删掉后检查点作废并且从库里清走`() = runBlocking {
        reader.put(checkpoint())

        val valid = reader.getValid("s1", listOf("m-10", "m-11"))

        assertNull("历史改过就不能再用这份摘要", valid)
        assertNull("坏检查点要一并删掉，免得下次又被误用", db.contextCheckpointDao().get("s1"))
    }

    @Test
    fun `极旧数据没有边界消息 id 时按 seq 边界继续用`() = runBlocking {
        // 这类记录不是坏数据：coveredSeq 仍是有效上界，判它作废等于把用户的摘要丢掉重来
        reader.put(checkpoint(boundaryId = ""))

        val valid = reader.getValid("s1", emptyList())

        assertNotNull(valid)
        assertEquals(9L, valid?.coveredSeq)
        assertNotNull("不该被顺手删掉", db.contextCheckpointDao().get("s1"))
    }

    @Test
    fun `同一会话只留最新一份 覆盖式写入`() = runBlocking {
        reader.put(checkpoint(summary = "第一版"))
        reader.put(checkpoint(summary = "第二版", boundaryId = "m-12"))

        val valid = reader.getValid("s1", listOf("m-12"))

        assertEquals("第二版", valid?.summary)
        assertEquals(1, db.contextCheckpointDao().getAll().size)
    }

    @Test
    fun `显式清空后读不到`() = runBlocking {
        reader.put(checkpoint())

        reader.clear("s1")

        assertNull(reader.get("s1"))
    }
}
