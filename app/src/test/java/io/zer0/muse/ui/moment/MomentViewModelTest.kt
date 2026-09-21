package io.zer0.muse.ui.moment

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentGenerator
import io.zer0.muse.data.moment.MomentInteractionEngine
import io.zer0.muse.data.moment.MomentRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.schedule.MomentScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * TEST-03: 朋友圈 ViewModel — state 生成 + 渲染分发关键路径的 JVM 单测。
 *
 * MomentViewModel 的 [SettingsRepository] 与 [MomentScheduler] 通过 Koin(GlobalContext.get())
 * 惰性注入,因此测试里用 `startKoin` 提供 mock 实例,使 load()/generateNow() 等 Koin 依赖路径可测。
 * 其余(repository / assistantRepository / generator / chatService / factStore)直接构造注入。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MomentViewModelTest {

    private lateinit var testScheduler: TestCoroutineScheduler
    private lateinit var app: Application
    private lateinit var repository: MomentRepository
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var generator: MomentGenerator
    private lateinit var settings: SettingsRepository
    private lateinit var scheduler: MomentScheduler
    private lateinit var interactionEngine: MomentInteractionEngine
    private lateinit var sessionRepository: SessionRepository
    private lateinit var viewModel: MomentViewModel

    private fun createVm() {
        viewModel = MomentViewModel(
            application = app,
            repository = repository,
            chatService = null,
            factStore = null,
            generator = generator,
            assistantRepository = assistantRepository,
            interactionEngine = interactionEngine,
            sessionRepository = sessionRepository,
        )
    }

    /** load() 里 getAll 经 withContext(Dispatchers.IO) 跳线程;反复推进直到 state 就绪,避免竞态。 */
    private fun awaitState(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            testScheduler.advanceUntilIdle()
            if (check()) return
            Thread.sleep(10)
        }
    }

    @Before
    fun setup() {
        unmockkAll()
        clearAllMocks()
        testScheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        app = ApplicationProvider.getApplicationContext()

        repository = mockk<MomentRepository>(relaxed = true)
        every { repository.observeMoments(any()) } returns MutableStateFlow(emptyList())
        coEvery { repository.getAll(any()) } returns emptyList()
        coEvery { repository.getCommentsBatch(any()) } returns emptyMap()

        assistantRepository = mockk<AssistantRepository>(relaxed = true)
        coEvery { assistantRepository.getAll() } returns emptyList()

        generator = mockk<MomentGenerator>(relaxed = true)

        settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.momentsCoverImageFlow } returns MutableStateFlow<String?>(null)
        every { settings.miniPhoneWallpaperFlow } returns MutableStateFlow<String?>(null)
        every { settings.momentsLastReadAtFlow } returns MutableStateFlow(0L)
        every { settings.momentMessagesLastReadAtFlow } returns MutableStateFlow(0L)
        every { settings.momentFavoriteIdsFlow } returns MutableStateFlow<Set<String>>(emptySet())

        scheduler = mockk<MomentScheduler>(relaxed = true)
        interactionEngine = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)

        // MomentViewModel 通过 Koin GlobalContext 惰性解析 settings / scheduler,这里注册 mock。
        startKoin {
            modules(
                module {
                    single<SettingsRepository> { settings }
                    single<MomentScheduler> { scheduler }
                },
            )
        }
    }

    @After
    fun teardown() {
        stopKoin()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun moment(id: String, content: String, createdAt: Long = 200L) = MomentEntity(
        id = id,
        content = content,
        createdAt = createdAt,
    )

    private fun comment(id: String, momentId: String) = MomentCommentEntity(
        id = id,
        momentId = momentId,
        sender = "assistant",
        content = "评论",
    )

    /** 动态列表加载:observeMoments 流正确映射进 state.moments。 */
    @Test
    fun `init loads moments into state`() {
        val m1 = moment("m1", "第一条", createdAt = 200L)
        val m2 = moment("m2", "第二条", createdAt = 100L)
        val c1 = comment("c1", "m1")
        every { repository.observeMoments(any()) } returns MutableStateFlow(listOf(m1, m2))
        coEvery { repository.getAll(any()) } returns listOf(m1, m2)
        coEvery { repository.getCommentsBatch(any()) } returns mapOf("m1" to listOf(c1))

        createVm()
        awaitState { !viewModel.state.value.isLoading }

        assertEquals(listOf(m1, m2), viewModel.state.value.moments)
        assertFalse(viewModel.state.value.isLoading)
        assertEquals(listOf(c1), viewModel.state.value.comments["m1"])
    }

    /** 空态/加载态流转:refresh(load) 后 repository 返回空列表 → 列表空且 isLoading=false、无错误。 */
    @Test
    fun `refresh keeps list empty after data cleared`() {
        val m1 = moment("m1", "第一条")
        coEvery { repository.getAll(any()) } returns listOf(m1)

        createVm()
        awaitState { viewModel.state.value.moments.isNotEmpty() }
        assertEquals(1, viewModel.state.value.moments.size)

        // 数据清空后再刷新:空列表 + 非 loading + 无错误态。
        coEvery { repository.getAll(any()) } returns emptyList()
        viewModel.load()
        awaitState { viewModel.state.value.moments.isEmpty() && !viewModel.state.value.isLoading }

        assertTrue(viewModel.state.value.moments.isEmpty())
        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.error)
    }

    /** toggleLike:点赞结果正确写回 state.moments(渲染分发关键路径)。 */
    @Test
    fun `toggleLike updates moment in state`() {
        val original = moment("m1", "第一条")
        coEvery { repository.getAll(any()) } returns listOf(original)

        createVm()
        awaitState { viewModel.state.value.moments.isNotEmpty() }

        coEvery { repository.toggleLike(any(), any(), any(), any()) } returns
            (original.copy(likes = 3, likedByUser = true) to true)
        viewModel.toggleLike(original)
        awaitState { viewModel.state.value.moments.firstOrNull()?.likes == 3 }

        val updated = viewModel.state.value.moments.first { it.id == "m1" }
        assertEquals(3, updated.likes)
        assertTrue(updated.likedByUser)
    }

    /** generateNow:手动生成结束后的 loading 态复位与结果通知(Koin 依赖 scheduler 已 mock)。 */
    @Test
    fun `generateNow resets loading and reports fallback notice`() {
        coEvery { repository.getAll(any()) } returns emptyList()
        coEvery { scheduler.generateNow() } returns false

        createVm()
        awaitState { !viewModel.state.value.isLoading }

        viewModel.generateNow()
        awaitState { !viewModel.state.value.isGeneratingNow }

        assertFalse(viewModel.state.value.isGeneratingNow)
        // factStore = null → 无素材判定无法成立 → 落到 LLM_FAILED(fallback 通知)。
        assertEquals(
            MomentViewModel.MomentGenerateNotice.LLM_FAILED,
            viewModel.state.value.generateNotice,
        )
    }
}