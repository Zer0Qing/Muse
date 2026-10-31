package io.zer0.muse.miniphone.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShell
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTab
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTags
import io.zer0.muse.miniphone.ui.shell.MiniPhoneThreadUi
import io.zer0.muse.miniphone.ui.shell.ShellUiState
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 聊天页的渲染验证:左右分栏、时间分隔、输入栏可用性、超长折叠,以及从会话列表进出的导航。
 *
 * 断言只落在「位置对不对 / 能不能按 / 展开了没有」上。状态由测试直接喂,
 * 不经过 ViewModel —— 数据怎么来的由 [MiniPhoneChatViewModelTest] 负责。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneChatUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<String>()
    private val resent = mutableListOf<String>()
    private val deleted = mutableListOf<String>()

    @Test
    fun ownBubbleSitsRightAndPeerBubbleSitsLeft() {
        render(
            chatState(
                message("m1", MpActorType.CHARACTER, "在的", createdAt = MINUTE_MS),
                message("m2", MpActorType.USER, "在吗", createdAt = MINUTE_MS + 1_000L),
            ),
        )

        val rootWidth = widthOf(compose.onRoot().getBoundsInRoot())
        val peer = compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).getBoundsInRoot()
        val own = compose.onNodeWithTag(MiniPhoneChatTags.bubble("m2")).getBoundsInRoot()

        assertTrue("对方的气泡应落在左半边", centerX(peer) < rootWidth / 2f)
        assertTrue("自己的气泡应落在右半边", centerX(own) > rootWidth / 2f)
        assertTrue("自己的气泡起点在对方之后", own.left > peer.right)
    }

    @Test
    fun timeDividerSitsBetweenMessagesThatAreMinutesApart() {
        render(
            chatState(
                message("m1", MpActorType.CHARACTER, "先走了", createdAt = MINUTE_MS),
                message("m2", MpActorType.USER, "我到了", createdAt = MINUTE_MS + TIME_GAP_MS),
            ),
        )

        val divider = compose.onNodeWithTag(MiniPhoneChatTags.timeDivider(MINUTE_MS + TIME_GAP_MS)).getBoundsInRoot()
        val earlier = compose.onNodeWithTag(MiniPhoneChatTags.message("m1")).getBoundsInRoot()
        val later = compose.onNodeWithTag(MiniPhoneChatTags.message("m2")).getBoundsInRoot()

        assertTrue("时间行排在较早那条之后", divider.top >= earlier.bottom)
        assertTrue("时间行排在较晚那条之前", divider.bottom <= later.top)
    }

    @Test
    fun sendButtonIsDisabledUntilSomethingIsTyped() {
        render(chatState())

        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).assertIsNotEnabled()
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).performClick()
        assertEquals(emptyList<String>(), sent)

        compose.onNodeWithTag(MiniPhoneChatTags.INPUT_FIELD).performTextInput("在吗")
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).assertIsEnabled()
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).performClick()

        assertEquals(listOf("在吗"), sent)
    }

    @Test
    fun workingCopyReplacesTheTypingRowWhileTheCharacterDoesSomething() {
        render(
            chatState(message("m1", MpActorType.USER, "帮我找找", createdAt = MINUTE_MS))
                .copy(isGenerating = true, activity = MpActivityKind.SEARCHING),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.ACTIVITY).assertExists()
        compose.onNodeWithText("对方正在翻找…").assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.TYPING).assertDoesNotExist()
    }

    @Test
    fun systemNoticeIsCenteredAndLongTextCollapses() {
        render(
            chatState(
                message("sys", MpActorType.SYSTEM, "对方没有回应", createdAt = MINUTE_MS, type = MpMessageType.SYSTEM),
                message("long", MpActorType.CHARACTER, LONG_TEXT, createdAt = MINUTE_MS + 1_000L),
            ),
        )

        val rootWidth = widthOf(compose.onRoot().getBoundsInRoot())
        val noticeCenter = centerX(compose.onNodeWithTag(MiniPhoneChatTags.message("sys")).getBoundsInRoot())
        val middle = rootWidth / 2f
        assertTrue("系统提示居中", noticeCenter > middle - rootWidth * NOTICE_CENTER_TOLERANCE)
        assertTrue("系统提示居中", noticeCenter < middle + rootWidth * NOTICE_CENTER_TOLERANCE)

        compose.onNodeWithTag(MiniPhoneChatTags.expand("long")).assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.expand("long")).performClick()
        compose.onNodeWithText(COLLAPSE_ACTION).assertExists()
    }

    @Test
    fun tappingThreadRowOpensChatAndLeavingItReturnsToTheList() {
        val shellState = ShellUiState(
            threads = listOf(
                MiniPhoneThreadUi(
                    id = "t1",
                    name = "阿离",
                    preview = "在吗",
                    lastMessageAt = MINUTE_MS,
                    unreadCount = 2,
                    pinned = false,
                ),
            ),
        )
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = shellState,
                    chatContent = { threadId, onBack ->
                        Column {
                            Text(text = "chat:$threadId", modifier = Modifier.testTag(FAKE_CHAT_TAG))
                            Text(
                                text = "返回列表",
                                modifier = Modifier
                                    .testTag(FAKE_CHAT_BACK_TAG)
                                    .clickable(onClick = onBack),
                            )
                        }
                    },
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).performClick()

        compose.onNodeWithTag(FAKE_CHAT_TAG).assertTextEquals("chat:t1")
        // 聊天页里不显示 tab 栏,输入栏才排得下
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).assertDoesNotExist()

        compose.onNodeWithTag(FAKE_CHAT_BACK_TAG).performClick()

        compose.onNodeWithTag(FAKE_CHAT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).assertExists()
    }

    @Test
    fun longPressOnOwnTextOffersCopyDeleteAndResend() {
        render(chatState(message("m1", MpActorType.USER, "在吗", createdAt = MINUTE_MS)))

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.MENU).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.COPY)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.RESEND)).assertIsDisplayed()
    }

    @Test
    fun longPressOnCharacterTextHasNoResend() {
        render(chatState(message("m1", MpActorType.CHARACTER, "在的", createdAt = MINUTE_MS)))

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.COPY)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.RESEND)).assertDoesNotExist()
    }

    @Test
    fun longPressOnImageOffersDeleteButNotCopy() {
        render(chatState(message("img", MpActorType.USER, "[图片]", createdAt = MINUTE_MS, type = MpMessageType.IMAGE)))

        compose.onNodeWithTag(MiniPhoneChatTags.image("img")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.COPY)).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.RESEND)).assertDoesNotExist()
    }

    @Test
    fun longPressOnVoiceOffersDeleteButNotCopy() {
        // 语音条要从 Koin 取播放器;这里只验菜单,给一个不会被真正播放的控制器
        startKoin { modules(module { single { MiniPhoneVoicePlaybackController() } }) }
        try {
            render(
                chatState(
                    message("voice", MpActorType.CHARACTER, "[语音]", createdAt = MINUTE_MS, type = MpMessageType.VOICE),
                ),
            )

            compose.onNodeWithTag(MiniPhoneChatTags.voiceUnavailable("voice")).performTouchInput { longClick() }
            compose.waitForIdle()

            compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).assertIsDisplayed()
            compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.COPY)).assertDoesNotExist()
        } finally {
            stopKoin()
        }
    }

    @Test
    fun deletingAMessageAsksForConfirmationFirst() {
        render(chatState(message("m1", MpActorType.USER, "在吗", createdAt = MINUTE_MS)))

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.DELETE_DIALOG).assertIsDisplayed()
        assertEquals("确认之前不能删", emptyList<String>(), deleted)

        compose.onNodeWithTag(MiniPhoneChatTags.DELETE_CANCEL).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.DELETE_DIALOG).assertDoesNotExist()
        assertEquals(emptyList<String>(), deleted)

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.DELETE_CONFIRM).performClick()
        compose.waitForIdle()

        assertEquals(listOf("m1"), deleted)
    }

    @Test
    fun resendingFromTheMenuReportsTheMessageId() {
        render(chatState(message("m1", MpActorType.USER, "在吗", createdAt = MINUTE_MS)))

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.RESEND)).performClick()
        compose.waitForIdle()

        assertEquals(listOf("m1"), resent)
    }

    @Test
    fun failedMessageShowsARetryMarkBesideIt() {
        render(
            chatState(
                message("m1", MpActorType.USER, "在吗", createdAt = MINUTE_MS, status = MpMessageStatus.FAILED),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.status("m1")).assertIsDisplayed()

        compose.onNodeWithTag(MiniPhoneChatTags.status("m1")).performClick()
        compose.waitForIdle()

        assertEquals(listOf("m1"), resent)
    }

    @Test
    fun sendingMessageShowsALightProgressWordNotACover() {
        render(
            chatState(
                message("m1", MpActorType.USER, "在吗", createdAt = MINUTE_MS, status = MpMessageStatus.SENDING),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.status("m1")).assertTextEquals("发送中")
        // 正文不能被进行态遮住
        compose.onNodeWithText("在吗").assertIsDisplayed()
    }

    @Test
    fun newMessagesPillAppearsOnlyAfterLeavingTheBottom() {
        var state by mutableStateOf(chatState(*manyMessages(count = 40)))
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(state = state, onBack = {}, onLoadOlder = {})
            }
        }
        // 进页就停在最新一条,不弹提示
        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).assertDoesNotExist()

        // 翻回更早的位置(程序化滚动,不是手势)
        compose.onNodeWithTag(MiniPhoneChatTags.MESSAGE_LIST).performScrollToIndex(20)
        compose.waitForIdle()

        state = state.copy(
            messages = state.messages + message("arrived", MpActorType.CHARACTER, "刚发来的一句", createdAt = LEAVING_BOTTOM_MS),
        )
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).assertTextEquals("1 条新消息")

        // 点一下回到最新:提示自己收起来
        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).assertDoesNotExist()
    }

    @Test
    fun newMessagesPillStaysHiddenWhileReaderIsAtTheBottom() {
        var state by mutableStateOf(chatState(*manyMessages(count = 40)))
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(state = state, onBack = {}, onLoadOlder = {})
            }
        }

        state = state.copy(
            messages = state.messages + message("arrived", MpActorType.CHARACTER, "刚发来的一句", createdAt = LEAVING_BOTTOM_MS),
        )
        compose.waitForIdle()

        // 人在底部:新消息直接被看到,不弹提示打断阅读
        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).assertDoesNotExist()
        compose.onNodeWithText("刚发来的一句").assertIsDisplayed()
    }

    private fun render(state: ChatUiState) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(
                    state = state,
                    onBack = {},
                    onLoadOlder = {},
                    input = ChatInputActions(onSend = { sent += it }),
                    messageActions = ChatMessageActions(onResend = { resent += it }, onDelete = { deleted += it }),
                )
            }
        }
    }

    /** 元素横向中心。 `getBoundsInRoot()` 给的是 Dp 矩形,没有现成的 center。 */
    private fun centerX(bounds: DpRect): Dp = bounds.left + widthOf(bounds) / 2f

    private fun widthOf(bounds: DpRect): Dp = bounds.right - bounds.left

    private fun chatState(vararg messages: ChatMessageUi): ChatUiState = ChatUiState(
        threadId = "t1",
        characterId = "c1",
        peerName = "阿离",
        messages = messages.toList(),
    )

    private fun message(
        id: String,
        sender: MpActorType,
        content: String,
        createdAt: Long,
        type: MpMessageType = MpMessageType.TEXT,
        status: MpMessageStatus = MpMessageStatus.SENT,
    ): ChatMessageUi = ChatMessageUi(
        id = id,
        sender = sender,
        type = type,
        content = content,
        createdAt = createdAt,
        status = status,
    )

    /** 一串按时间递增的消息:足够撑出滚动,方便验「离开底部」之后的提示。 */
    private fun manyMessages(count: Int): Array<ChatMessageUi> = Array(count) { index ->
        message(
            id = "m$index",
            sender = if (index % 2 == 0) MpActorType.CHARACTER else MpActorType.USER,
            content = "第 $index 句",
            createdAt = (index + 1) * MINUTE_MS,
        )
    }

    private companion object {
        const val MINUTE_MS: Long = 60_000L
        const val LEAVING_BOTTOM_MS: Long = 41 * MINUTE_MS
        const val TIME_GAP_MS: Long = 10 * MINUTE_MS
        const val FAKE_CHAT_TAG: String = "mp-fake-chat"
        const val FAKE_CHAT_BACK_TAG: String = "mp-fake-chat-back"
        const val COLLAPSE_ACTION: String = "收起"

        /** 居中容差:允许 10% 屏宽的偏差。 */
        const val NOTICE_CENTER_TOLERANCE: Float = 0.1f

        /** 300 字以上的正文,用来验证折叠。 */
        val LONG_TEXT: String = "这是一句很长的话".repeat(40)
    }
}
