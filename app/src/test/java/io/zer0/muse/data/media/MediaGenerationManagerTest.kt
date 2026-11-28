package io.zer0.muse.data.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.image.ImageService
import io.zer0.ai.video.VideoGenerationService
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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

/** Unit tests for [MediaGenerationManager] lifecycle: submission, local persistence, cancellation, and startup recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaGenerationManagerTest {

    private lateinit var context: Context
    private lateinit var db: MuseDb
    private lateinit var dao: MediaGenDao
    private lateinit var storage: MediaAssetStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mediaGenDao()
        storage = MediaAssetStorage(context, OkHttpClient())
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "media_studio").deleteRecursively()
    }

    @Test
    fun `init marks stale QUEUED and RUNNING tasks as FAILED`() = runBlocking {
        val now = System.currentTimeMillis()
        dao.upsertTask(
            MediaGenTaskEntity(
                id = "stale_1",
                kind = MediaGenTaskEntity.KIND_VIDEO,
                status = MediaGenTaskEntity.STATUS_RUNNING,
                prompt = "stale",
                providerId = "p1",
                modelId = "m1",
                createdAt = now,
                updatedAt = now,
            ),
        )
        MediaGenerationManager(
            context = context,
            dao = dao,
            storage = storage,
            settings = mockk(relaxed = true),
            imageService = mockk(relaxed = true),
            videoGenerationService = mockk(relaxed = true),
        )
        delay(200)
        val restored = dao.getTaskById("stale_1")
        assertNotNull(restored)
        assertEquals(MediaGenTaskEntity.STATUS_FAILED, restored?.status)
    }

    @Test
    fun `submitImageTask persists task, local PNG asset, and toggles favorite`() = runBlocking {
        val settings: SettingsRepository = mockk(relaxed = true)
        val imageService: ImageService = mockk()
        val provider = ProviderConfig(
            id = "p_img",
            displayName = "Img",
            type = ProviderType.OPENAI,
            apiKey = "sk-test",
            models = listOf(Model(id = "gpt-image-1", name = "GPT Image", providerId = "p_img")),
        )
        coEvery { settings.getProviderById("p_img") } returns provider
        val pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        coEvery { imageService.generate(any(), any(), any()) } returns listOf("data:image/png;base64,$pngBase64")

        val manager = MediaGenerationManager(
            context = context,
            dao = dao,
            storage = storage,
            settings = settings,
            imageService = imageService,
            videoGenerationService = mockk(relaxed = true),
        )
        val taskId = manager.submitImageTask(
            StudioImageRequest(
                prompt = "minimal pixel",
                providerId = "p_img",
                modelId = "gpt-image-1",
            ),
        )
        // Wait for background IO coroutine completion
        var attempts = 0
        while (dao.getTaskById(taskId)?.status == MediaGenTaskEntity.STATUS_RUNNING && attempts < 50) {
            delay(50)
            attempts++
        }
        val task = dao.getTaskById(taskId)
        assertEquals(MediaGenTaskEntity.STATUS_SUCCEEDED, task?.status)

        val assets = manager.observeAssets().first()
        assertEquals(1, assets.size)
        val asset = assets.first()
        assertTrue(File(asset.localPath).exists())

        manager.toggleFavorite(asset.id)
        assertEquals(true, dao.getAssetById(asset.id)?.favorite)

        manager.deleteAsset(asset.id)
        assertTrue(manager.observeAssets().first().isEmpty())
    }

    @Test
    fun `cancelTask marks running video task CANCELLED`() = runBlocking {
        val settings: SettingsRepository = mockk(relaxed = true)
        val videoService: VideoGenerationService = mockk()
        val gate = CompletableDeferred<Result<String>>()
        coEvery { settings.getProviderById("p_vid") } returns ProviderConfig(
            id = "p_vid",
            displayName = "Vid",
            type = ProviderType.OPENAI,
            apiKey = "sk-test",
        )
        coEvery { videoService.generateVideo(any(), any(), any(), any()) } coAnswers { gate.await() }

        val manager = MediaGenerationManager(
            context = context,
            dao = dao,
            storage = storage,
            settings = settings,
            imageService = mockk(relaxed = true),
            videoGenerationService = videoService,
        )
        val taskId = manager.submitVideoTask(
            StudioVideoRequest(
                prompt = "flying car",
                providerId = "p_vid",
                modelId = "sora-2",
            ),
        )
        delay(100)
        manager.cancelTask(taskId)
        delay(150)
        assertEquals(MediaGenTaskEntity.STATUS_CANCELLED, dao.getTaskById(taskId)?.status)
    }
}
