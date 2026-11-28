package io.zer0.muse

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.asr.local.LocalModelStoreBridge
import io.zer0.muse.computenode.ComputeNodeManager
import io.zer0.muse.conversation.ui.ConversationViewModel
import io.zer0.muse.data.SettingsStoreContextHolder
import io.zer0.muse.data.agentbot.AgentMemoryBridge
import io.zer0.muse.data.agentbot.AgentSpaceEngine
import io.zer0.muse.data.agentbot.AgentWatcherEngine
import io.zer0.muse.data.media.MediaGenerationManager
import io.zer0.muse.ui.agentbot.AgentBotViewModel
import io.zer0.muse.ui.chat.ChatViewModel
import io.zer0.muse.ui.groupchat.GroupChatViewModel
import io.zer0.muse.ui.memory.MemoryGraphViewModel
import io.zer0.muse.ui.memory.MemorySpaceViewModel
import io.zer0.muse.ui.memory.MemoryViewModel
import io.zer0.muse.ui.stats.StatsViewModel
import io.zer0.muse.ui.translate.TranslateViewModel
import io.zer0.muse.ui.worldbook.WorldBookViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 真实 Koin 依赖图解析回归测试。
 *
 * 确保全部核心后台服务与 ViewModel 都能从 [allKoinModules] 真实解析出来，
 * 拦截诸如“接口类型与具体实现类注册不匹配”在编译期无法暴露、真机首屏才崩溃的问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class KoinGraphResolutionTest {

    private lateinit var koin: Koin

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        SettingsStoreContextHolder.applicationContext = app
        LocalModelStoreBridge.init(app)
        koin = GlobalContext.getOrNull() ?: startKoin {
            androidContext(app)
            modules(allKoinModules)
        }.koin
    }

    @After
    fun tearDown() {
        if (::koin.isInitialized) {
            runCatching { koin.getOrNull<AgentWatcherEngine>()?.close() }
            runCatching { koin.getOrNull<CoroutineScope>()?.cancel() }
        }
        runCatching { stopKoin() }
    }

    @Test
    fun `agentbot and core viewmodels resolve without missing definitions`() {
        assertNotNull(koin.get<AgentWatcherEngine>())
        assertNotNull(koin.get<AgentSpaceEngine>())
        assertNotNull(koin.get<AgentMemoryBridge>())
        assertNotNull(koin.get<ComputeNodeManager>())
        assertNotNull(koin.get<MediaGenerationManager>())

        assertNotNull(koin.get<AgentBotViewModel>())
        assertNotNull(koin.get<ChatViewModel>())
        assertNotNull(koin.get<ConversationViewModel>())
        assertNotNull(koin.get<MemoryViewModel>())
        assertNotNull(koin.get<MemorySpaceViewModel>())
        assertNotNull(koin.get<MemoryGraphViewModel>())
        assertNotNull(koin.get<StatsViewModel>())
        assertNotNull(koin.get<TranslateViewModel>())
        assertNotNull(koin.get<GroupChatViewModel>())
        // QuickNotesViewModel 在 init 期实例化依赖 AndroidKeyStore 的 NoteCipher，Robolectric JVM 无该密钥库。
        assertNotNull(koin.get<WorldBookViewModel>())
    }
}
