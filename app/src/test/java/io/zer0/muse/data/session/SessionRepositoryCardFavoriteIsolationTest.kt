package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * 卡片收藏与源会话的隔离边界（用户反馈 32）。
 *
 * 收藏内容是以独立快照消息存放在一个 archived=1 的容器会话里。容器一旦泄漏进归档列表，
 * 用户会把它当普通归档删掉，messages 的 FK 级联随之清空整个收藏夹。这里钉住四件事：
 *  1. 容器不出现在归档列表；
 *  2. 任何删除入口都删不掉容器（收藏 survive）；
 *  3. 删源会话不影响已收藏的卡片快照；
 *  4. 删收藏快照不影响源会话消息。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionRepositoryCardFavoriteIsolationTest {

    private lateinit var context: Context
    private lateinit var database: MuseDb
    private lateinit var imageDir: File
    private lateinit var repository: SessionRepository
    private var previousFts5 = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        imageDir = Files.createTempDirectory("card-favorite-isolation").toFile()
        database = Room.databaseBuilder(context, MuseDb::class.java, File(imageDir, "muse.db").absolutePath)
            .allowMainThreadQueries()
            .build()
        repository = SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            database = database,
            context = context,
            messageImageStore = MessageImageStore(imageDir),
        )
        previousFts5 = MessageFtsRuntime.useFts5
        // Robolectric 的 FTS4 fallback 会中止外层事务；本用例只验主数据隔离边界。
        MessageFtsRuntime.useFts5 = true
    }

    @After
    fun tearDown() {
        database.close()
        imageDir.deleteRecursively()
        MessageFtsRuntime.useFts5 = previousFts5
    }

    private suspend fun sourceSession(id: String = "source") {
        database.sessionDao().insert(
            SessionEntity(id = id, title = id, createdAt = 1_000L, updatedAt = 1_000L),
        )
        database.messageDao().upsert(
            MessageEntity(
                id = "$id-msg",
                sessionId = id,
                role = "ASSISTANT",
                content = "<html>card</html>",
                createdAt = 1_000L,
            ),
        )
    }

    @Test
    fun `card favorite container is hidden from the archived list`() = runBlocking {
        val containerId = repository.ensureCardFavoriteSession()
        database.sessionDao().insert(
            SessionEntity(
                id = "real-archived",
                title = "real",
                createdAt = 1_000L,
                updatedAt = 1_000L,
                archived = true,
            ),
        )

        val archived = repository.observeArchived().first()

        assertEquals(listOf("real-archived"), archived.map { it.id })
        assertTrue("容器本身必须是归档态", database.sessionDao().getByIds(listOf(containerId)).single().archived)
    }

    @Test
    fun `deleting the container is refused and favorites survive`() = runBlocking {
        sourceSession()
        repository.favoriteCard(cardId = "source-msg", language = "html", content = "<html>card</html>")
        val containerId = repository.ensureCardFavoriteSession()
        val favoriteId = database.messageDao().observeAllFavorites().first().single().id

        repository.deleteSession(containerId)
        repository.softDeleteSession(containerId)

        val favorites = database.messageDao().observeAllFavorites().first()
        assertEquals(listOf(favoriteId), favorites.map { it.id })
        assertNotNull("容器行必须还在", database.sessionDao().getByIds(listOf(containerId)).singleOrNull())
    }

    @Test
    fun `deleting the source session keeps the card favorite snapshot`() = runBlocking {
        sourceSession()
        repository.favoriteCard(cardId = "source-msg", language = "html", content = "<html>card</html>")

        repository.deleteSession("source")

        val favorites = database.messageDao().observeAllFavorites().first()
        assertEquals(1, favorites.size)
        assertTrue(favorites.single().content.contains("<html>card</html>"))
        assertTrue("源会话消息应已删除", database.messageDao().getByMessageId("source-msg") == null)
    }

    @Test
    fun `deleting a favorite snapshot keeps the source message`() = runBlocking {
        sourceSession()
        repository.favoriteCard(cardId = "source-msg", language = "html", content = "<html>card</html>")
        val favoriteId = database.messageDao().observeAllFavorites().first().single().id
        assertTrue("快照 id 必须与源消息 id 不同", favoriteId != "source-msg")

        repository.deleteMessage(favoriteId)

        assertNotNull("源会话消息必须还在", database.messageDao().getByMessageId("source-msg"))
        assertTrue(database.messageDao().observeAllFavorites().first().isEmpty())
    }
}
