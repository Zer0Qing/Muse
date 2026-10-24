package io.zer0.muse.ui.groupchat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.MultiAgentConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.schedule.GroupChatScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 群聊 ViewModel 的 UI 状态流异常收敛回归。
 *
 * 背景：init 里的 `viewModelScope.launch { flow.collect { } }` 此前没有任何异常防线。
 * 上游（Room/DataStore）抛错、或 collect 块自身抛错，都会变成未捕获协程异常：
 * 真机可能崩进程；测试里会泄漏成下一个用例的 `UncaughtExceptionsBeforeTest`
 * （WorkspaceBoundaryTest 因此 flaky）。
 *
 * 这里用 StandardTestDispatcher 跑真实的 viewModelScope：如果异常没被收敛，
 * 测试调度器会把它当成未捕获异常抛出来，用例直接红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GroupChatUpstreamGuardTest {

    private lateinit var testScheduler: TestCoroutineScheduler
    private lateinit var repository: GroupChatRepository
    private lateinit var scheduler: GroupChatScheduler
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var settings: SettingsRepository
    private lateinit var activityHub: GroupChatActivityHub

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun chat(id: String, name: String) = GroupChatEntity(
        id = id,
        name = name,
        memberIdsJson = "[]",
        updatedAt = 1L,
    )

    private fun msg(id: String, chatId: String, body: String, ts: Long) = GroupChatMessageEntity(
        id = id,
        chatId = chatId,
        senderType = "assistant",
        senderId = "assistant-a",
        senderName = "助手A",
        body = body,
        timestamp = ts,
    )

    @Before
    fun setup() {
        unmockkAll()
        clearAllMocks()
        testScheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        repository = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        assistantRepository = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        every { settings.multiAgentConfigFlow } returns MutableStateFlow(MultiAgentConfig())
        every { settings.chatPreferencesFlow } returns MutableStateFlow(ChatPreferences())
        activityHub = GroupChatActivityHub()

        // 默认全部流正常，具体用例再覆盖成会抛错的流。
        every { repository.observeChats() } returns MutableStateFlow(emptyList())
        every { assistantRepository.observeAll } returns MutableStateFlow(emptyList())
        every { repository.observeChat(any()) } returns MutableStateFlow<GroupChatEntity?>(null)
        every { repository.getPagedMessages(any(), any()) } returns MutableStateFlow(emptyList())
        // relaxed mock 的 StateFlow 取值会抛 KotlinNothingValueException，这里给真 StateFlow。
        every { scheduler.activeGroupGeneration } returns MutableStateFlow(null)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun buildViewModel(): GroupChatViewModel {
        val vm = GroupChatViewModel(
            groupChatRepository = repository,
            scheduler = scheduler,
            assistantRepository = assistantRepository,
            settings = settings,
            activityHub = activityHub,
            appContext = context,
            groupChatMemoryRepository = null,
        )
        testScheduler.advanceUntilIdle()
        return vm
    }

    /** 上游直接抛错：整条收集流被收敛，进程/测试都不该收到未捕获异常。 */
    @Test
    fun `throwing upstream flow does not leak an uncaught exception`() {
        every { repository.observeChats() } returns flow { throw IllegalStateException("room exploded") }

        val vm = buildViewModel()

        // 收集器已结束，state 保持默认值而不是崩掉。
        assertTrue(vm.state.value.chats.isEmpty())
    }

    /** 一条流挂掉不能连累其他流：助手列表仍能正常刷进 state。 */
    @Test
    fun `one failing collector does not stop the others`() {
        every { repository.observeChats() } returns flow { throw IllegalStateException("room exploded") }
        every { assistantRepository.observeAll } returns MutableStateFlow(
            listOf(AssistantEntity(id = "assistant-a", name = "助手A")),
        )

        val vm = buildViewModel()

        assertEquals("群聊列表流失败不影响助手列表流", 1, vm.state.value.assistants.size)
        // 列表流挂了，首屏 loading 标记就只能停在原值（不再有任何东西把它关掉）。
        assertTrue("列表流失败时 isChatsLoading 保持原值", vm.state.value.isChatsLoading)
    }

    /** 消息窗口流抛错后，切群聊仍能靠 loadInitialPage 这条直连 DB 的路径拿到消息。 */
    @Test
    fun `failing message window flow still allows initial page load`() {
        val chatId = "chat1"
        every { repository.observeChats() } returns MutableStateFlow(listOf(chat(chatId, "群一")))
        every { repository.getPagedMessages(chatId, any()) } returns flow {
            throw IllegalStateException("paged query failed")
        }
        coEvery { repository.countMessages(chatId) } returns 1
        coEvery { repository.getRecentMessagesPaged(chatId, any()) } returns
            listOf(msg("m1", chatId, "hello", 1L))

        val vm = buildViewModel()
        vm.selectChat(chatId)
        testScheduler.advanceUntilIdle()

        assertEquals(1, vm.state.value.currentMessages.size)
    }

    /**
     * 下游 collect 块自身抛错（历史 flake 的真实形态：取 activeGroupGeneration 的字段时
     * 抛 KotlinNothingValueException）也必须被收敛。
     *
     * 注意抛点必须在 collect 体内：如果在 `scheduler.activeGroupGeneration` 这个属性读取处抛，
     * 那就是 launch 块里的普通异常，不经过 Flow 守卫（那属于调用方 bug，不在本修复范围）。
     */
    @Test
    fun `throwing collect body does not leak an uncaught exception`() {
        val brokenGeneration = mockk<GroupChatScheduler.ActiveGroupGeneration>()
        every { brokenGeneration.currentSpeakerId } throws IllegalStateException("speaker unavailable")
        every { scheduler.activeGroupGeneration } returns MutableStateFlow(brokenGeneration)
        every { assistantRepository.observeAll } returns MutableStateFlow(
            listOf(AssistantEntity(id = "assistant-a", name = "助手A")),
        )

        val vm = buildViewModel()

        // 助手列表流在 collect 体内中断，但 VM 没崩，其他流也不受影响。
        assertEquals(0, vm.state.value.assistants.size)
        assertEquals(false, vm.state.value.isAgentResponding)
    }
}
