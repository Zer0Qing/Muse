package io.zer0.muse.miniphone.di

import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.data.chat.MpConversationActions
import io.zer0.muse.miniphone.data.chat.MpConversationStore
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.moment.MP_LOCAL_ACTOR_ID
import io.zer0.muse.miniphone.ui.moments.MiniPhoneMomentsViewModel
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShellViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 模块的 Koin 装配:两个界面 ViewModel 都取得出来,且「我的朋友圈」那个真的拿到了模式参数。
 *
 * 装配错在这一层不会在编译期暴露 —— 外壳少一个依赖、朋友圈 ViewModel 的参数取错下标,
 * 都只在运行时点进去才炸。这里直接按 Koin 的方式解析一遍,并真往库里塞两条动态,
 * 看「只看自己」那个实例是不是只读到自己的那条。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneModuleWiringTest {

    private lateinit var koin: Koin

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        koin = startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(miniPhoneModule)
        }.koin
    }

    @After
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun shellViewModelResolvesWithTheConversationStore() {
        val viewModel = koin.get<MiniPhoneShellViewModel>()

        assertNotNull(viewModel)
        assertTrue(
            "会话行上的写操作要接到真实现上",
            koin.get<MpConversationActions>() is MpConversationStore,
        )
        assertNotNull("宿主能力由占位实现兜底", koin.get<MiniPhoneHost>())
    }

    @Test
    fun myMomentsViewModelReadsTheOnlyMineFlagFromItsParameters() = runBlocking {
        val db = koin.get<MiniPhoneDb>()
        db.momentDao().insert(moment(id = "mine", authorId = MP_LOCAL_ACTOR_ID, content = "自己的", at = 20L))
        db.momentDao().insert(moment(id = "other", authorId = "c1", content = "别人的", at = 30L))

        val mine = koin.get<MiniPhoneMomentsViewModel> { parametersOf(true) }

        val state = withTimeout(TIMEOUT_MS) { mine.uiState.first { it.isLoaded } }
        assertEquals("只要自己发的那条", listOf("mine"), state.moments.map { it.id })
    }

    @Test
    fun momentsViewModelWithoutTheFlagReadsTheWholeTimeline() = runBlocking {
        val db = koin.get<MiniPhoneDb>()
        db.momentDao().insert(moment(id = "mine", authorId = MP_LOCAL_ACTOR_ID, content = "自己的", at = 20L))
        db.momentDao().insert(moment(id = "other", authorId = "c1", content = "别人的", at = 30L))

        val all = koin.get<MiniPhoneMomentsViewModel> { parametersOf(false) }

        val state = withTimeout(TIMEOUT_MS) { all.uiState.first { it.isLoaded } }
        assertEquals("全部动态按时间倒序", listOf("other", "mine"), state.moments.map { it.id })
    }

    private fun moment(id: String, authorId: String, content: String, at: Long): MpMomentEntity = MpMomentEntity(
        id = id,
        authorId = authorId,
        authorName = authorId,
        content = content,
        createdAt = at,
        source = if (authorId == MP_LOCAL_ACTOR_ID) MpMomentSource.USER else MpMomentSource.AI,
    )

    private companion object {
        const val TIMEOUT_MS: Long = 5_000L
    }
}
