package io.zer0.muse.ui.groupchat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.MultiAgentConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.schedule.GroupChatScheduler
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TEST-03: 群聊 ViewModel — state 生成 + 渲染分发关键路径(分页加载 / 切换群聊 / 乐观发送)的 JVM 单测。
 *
 * 全部依赖注入用 MockK mock;GroupChatViewModel 不触碰 Koin/GlobalContext,可直接 mock 跑通。
 * Robolectric 提供 Context;StandardTestDispatcher 控制 viewModelScope 的 Main 调度。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GroupChatViewModelTest {

    private lateinit var testScheduler: TestCoroutineScheduler
    private lateinit var repository: GroupChatRepository
    private lateinit var scheduler: GroupChatScheduler
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var settings: SettingsRepository
    private lateinit var activityHub: GroupChatActivityHub
    private lateinit var viewModel: GroupChatViewModel

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun chat(id: String, name: String, updatedAt: Long = 0L) = GroupChatEntity(
        id = id,
        name = name,
        memberIdsJson = "[]",
        updatedAt = updatedAt,
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

    private fun buildViewModel() {
        viewModel = GroupChatViewModel(
            groupChatRepository = repository,
            scheduler = scheduler,
            assistantRepository = assistantRepository,
            settings = settings,
            activityHub = activityHub,
            appContext = context,
            groupChatMemoryRepository = null,
        )
        testScheduler.advanceUntilIdle()
    }

    @Before
    fun setup() {
        unmockkAll()
        clearAllMocks()
        testScheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        repository = mockk<GroupChatRepository>(relaxed = true)
        scheduler = mockk<GroupChatScheduler>(relaxed = true)
        assistantRepository = mockk<AssistantRepository>(relaxed = true)
        settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.multiAgentConfigFlow } returns MutableStateFlow(MultiAgentConfig())
        every { settings.chatPreferencesFlow } returns MutableStateFlow(ChatPreferences())

        // GroupChatActivityHub 是纯内存广播层(无 Android 依赖),用真实实例即可。
        activityHub = GroupChatActivityHub()

        // init 里各观察流默认给空值,避免 collect 到 null 崩溃;具体用例再按需覆盖。
        every { repository.observeChats() } returns MutableStateFlow(emptyList())
        every { assistantRepository.observeAll } returns MutableStateFlow(emptyList())
        every { repository.observeChat(any()) } returns MutableStateFlow<GroupChatEntity?>(null)
        every { repository.getPagedMessages(any(), any()) } returns MutableStateFlow(emptyList())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** selectChat 切换群聊 → currentChat 更新为选中群聊(渲染分发核心)。 */
    @Test
    fun `selectChat switches current chat and clears old messages`() {
        val chat1 = chat("chat1", "群一")
        val chat2 = chat("chat2", "群二")
        every { repository.observeChats() } returns MutableStateFlow(listOf(chat1, chat2))
        every { repository.observeChat("chat2") } returns MutableStateFlow(chat2)
        buildViewModel()

        viewModel.selectChat("chat2")
        testScheduler.advanceUntilIdle()

        assertEquals(2, viewModel.state.value.chats.size)
        assertEquals("chat2", viewModel.state.value.currentChat?.id)
        assertTrue(viewModel.state.value.currentMessages.isEmpty())
    }

    /** 群聊列表加载:observeChats 返回的数据正确映射进 state.chats(数量/保序)。 */
    @Test
    fun `observeChats maps repository list into state`() {
        val chatNew = chat("chat2", "新群", updatedAt = 200L)
        val chatOld = chat("chat1", "旧群", updatedAt = 100L)
        // 仓库已按 updatedAt 降序返回,VM 应原样透传。
        every { repository.observeChats() } returns MutableStateFlow(listOf(chatNew, chatOld))
        buildViewModel()

        assertEquals(listOf(chatNew, chatOld), viewModel.state.value.chats)
        assertFalse(viewModel.state.value.isChatsLoading)
    }

    /** loadMoreHistory 分页:首屏 N 条 → 上滑加载更早历史,条数增加且无重复插入。 */
    @Test
    fun `loadMoreHistory prepends older messages without duplicates`() {
        val chatId = "chat2"
        every { repository.observeChats() } returns MutableStateFlow(listOf(chat("chat2", "群二")))
        every { repository.observeChat(any()) } returns MutableStateFlow<GroupChatEntity?>(null)

        coEvery { repository.countMessages(chatId) } returns 5
        // 首屏：最近 4 条(时间升序 ts=1..4),total=5 故 hasMoreHistory=true
        val recent = (1L..4L).map { msg("m$it", chatId, "body$it", it) }
        coEvery { repository.getRecentMessagesPaged(chatId, any()) } returns recent
        // 更早 2 条(ts 0、1),远少于页大小 → 加载后 hasMoreHistory 归 false
        val older = listOf(
            msg("m-1", chatId, "older-1", 0L),
            msg("m0", chatId, "older0", 1L),
        )
        coEvery { repository.getOlderMessages(chatId, any(), any(), any()) } returns older

        buildViewModel()
        viewModel.selectChat(chatId)
        testScheduler.advanceUntilIdle()
        assertEquals(4, viewModel.state.value.currentMessages.size)
        assertTrue(viewModel.state.value.hasMoreHistory)

        viewModel.loadMoreHistory()
        testScheduler.advanceUntilIdle()

        val messages = viewModel.state.value.currentMessages
        assertEquals(6, messages.size)
        assertEquals(messages.size, messages.map { it.id }.toSet().size) // 无重复插入
        assertEquals(2, viewModel.state.value.lastHistoryLoadCount)
        assertFalse(viewModel.state.value.hasMoreHistory)
    }

    /** 状态重置:切换群聊后旧群聊消息被清空(防跨群聊串扰)。 */
    @Test
    fun `switching chat resets previous chat messages`() {
        val chat2 = chat("chat2", "群二")
        val chat3 = chat("chat3", "群三")
        every { repository.observeChats() } returns MutableStateFlow(listOf(chat2, chat3))
        every { repository.observeChat(any()) } returns MutableStateFlow<GroupChatEntity?>(null)

        coEvery { repository.countMessages("chat2") } returns 2
        coEvery { repository.getRecentMessagesPaged("chat2", any()) } returns
            listOf(msg("a", "chat2", "hi", 100L), msg("b", "chat2", "yo", 200L))
        coEvery { repository.countMessages("chat3") } returns 0

        buildViewModel()
        viewModel.selectChat("chat2")
        testScheduler.advanceUntilIdle()
        assertEquals(2, viewModel.state.value.currentMessages.size)

        viewModel.selectChat("chat3")
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.currentMessages.isEmpty())
        assertFalse(viewModel.state.value.hasMoreHistory)
    }

    /** 发送消息:清空输入框 + 追加乐观消息 + 委托调度器触发轮转(sendMessage 的 JVM 可测行为)。 */
    @Test
    fun `sendMessage clears input and appends optimistic message`() {
        val chatId = "chat2"
        every { repository.observeChats() } returns MutableStateFlow(listOf(chat("chat2", "群二")))
        coEvery { repository.countMessages(chatId) } returns 0

        buildViewModel()
        viewModel.selectChat(chatId)
        testScheduler.advanceUntilIdle()

        viewModel.updateInput("  你好呀  ")
        viewModel.sendMessage("  你好呀  ")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("", state.inputText)
        assertEquals(1, state.currentMessages.size)
        assertTrue(state.currentMessages.first().id.startsWith("optimistic-"))
        // 委托调度器触发 Agent 轮转(渲染分发给 worker 的关键路径)。
        verify { scheduler.launchRoundRobin(chatId, "  你好呀  ", emptyList(), emptyList()) }
    }
}