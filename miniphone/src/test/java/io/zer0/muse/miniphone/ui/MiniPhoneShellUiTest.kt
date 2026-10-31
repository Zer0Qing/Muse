package io.zer0.muse.miniphone.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.MINI_PHONE_DB_VERSION
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import io.zer0.muse.miniphone.ui.shell.MessagesPage
import io.zer0.muse.miniphone.ui.shell.MiniPhoneApps
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShell
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShellActions
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTab
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTags
import io.zer0.muse.miniphone.ui.shell.MiniPhoneThreadUi
import io.zer0.muse.miniphone.ui.shell.ShellUiState
import io.zer0.muse.miniphone.ui.shell.ThreadMenuAction
import io.zer0.muse.miniphone.ui.shell.ThreadMenuActions
import io.zer0.muse.miniphone.ui.shell.assembleShellState
import io.zer0.muse.miniphone.ui.shell.contactSectionsOf
import io.zer0.muse.miniphone.ui.shell.miniPhoneVersionLabel
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import io.zer0.muse.miniphone.ui.theme.darkMiniPhoneColors
import io.zer0.muse.miniphone.ui.theme.lightMiniPhoneColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 外壳与四页的渲染验证。
 *
 * 断言只落在「看不看得见 / 顺序对不对 / 数字对不对」上:每个用例都从桌面进页,
 * 再按底部 tab 切页,这样顺带证明切页真的换了内容,而不是四页叠在一起。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneShellUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var db: MiniPhoneDb? = null

    @After
    fun tearDown() {
        db?.close()
    }

    @Test
    fun tabBarSwitchesBetweenFourPages() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(state = ShellUiState(profile = MeProfile))
            }
        }

        // 起始在桌面:图标网格在,页面标题不在
        compose.onNodeWithTag(launcherTag(MiniPhoneTab.MESSAGES)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertDoesNotExist()

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertTextEquals("消息")
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS).assertExists()

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.CONTACTS)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertTextEquals("通讯录")
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_CONTACTS).assertExists()
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS).assertDoesNotExist()

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.DISCOVER)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertTextEquals("发现")
        compose.onNodeWithTag(MiniPhoneTags.MOMENTS_ENTRY).assertExists()
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_CONTACTS).assertDoesNotExist()

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertTextEquals("我")
        compose.onNodeWithTag(MiniPhoneTags.MOMENTS_ENTRY).assertDoesNotExist()
        compose.onNodeWithText(MeProfile.nickname).assertExists()
    }

    @Test
    fun autoMomentSwitchIsOnMePageAndReportsToggling() {
        var toggled: Boolean? = null
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(profile = MeProfile, autoMomentEnabled = true),
                    actions = MiniPhoneShellActions(onToggleAutoMoment = { toggled = it }),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()
        compose.onNodeWithText("自动发朋友圈").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.AUTO_MOMENT_SWITCH).performScrollTo().performClick()

        // 点一下就是「关掉」:状态在 ViewModel 里,这里只验回调传的是 false
        assertEquals(false, toggled)
    }

    @Test
    fun chatProactiveSwitchIsOnMePageAndReportsToggling() {
        var toggled: Boolean? = null
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(profile = MeProfile, chatProactiveEnabled = false),
                    actions = MiniPhoneShellActions(onToggleChatProactive = { toggled = it }),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()
        compose.onNodeWithText("角色主动私信").performScrollTo().assertIsDisplayed()
        // 默认关:说明文案要把「只在白天、默认关闭」说清楚
        compose.onNodeWithText("角色会偶尔主动找你说话，只在 08:00–22:00 之间，默认关闭").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.CHAT_PROACTIVE_SWITCH).performScrollTo().performClick()

        // 点一下就是「打开」:状态在 ViewModel 里,这里只验回调传的是 true
        assertEquals(true, toggled)
    }

    @Test
    fun emptyThreadListShowsEmptyState() {
        openMessages(ShellUiState())

        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS).assertIsDisplayed()
        compose.onNodeWithText("还没有聊天").assertIsDisplayed()
        compose.onNodeWithText("去通讯录找个角色开始吧").assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).assertDoesNotExist()
    }

    @Test
    fun emptyThreadStateButtonJumpsToContacts() {
        openMessages(ShellUiState())

        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS_ACTION).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS_ACTION).performClick()

        // 点一下就是「切到通讯录」:消息页连同它的空态一起退场
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertTextEquals("通讯录")
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_CONTACTS).assertExists()
        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS).assertDoesNotExist()
    }

    @Test
    fun emptyThreadStateButtonReportsExactlyOneClick() {
        var clicks = 0
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MessagesPage(threads = emptyList(), onGoContacts = { clicks += 1 })
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.EMPTY_THREADS_ACTION).performClick()
        compose.waitForIdle()

        assertEquals(1, clicks)
    }

    @Test
    fun tappingContactRowAsksForAThreadAndOpensIt() {
        val asked = mutableListOf<String>()
        val sections = contactSectionsOf(listOf(MpCharacter(id = "c1", name = "阿离", tagline = "住在楼下")))
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(contactSections = sections),
                    chatContent = { threadId, _ ->
                        Text(text = "chat:$threadId", modifier = Modifier.testTag(FAKE_CHAT_TAG))
                    },
                    onOpenCharacterChat = { characterId ->
                        asked += characterId
                        "t-new"
                    },
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.CONTACTS)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.contact("c1")).performClick()
        compose.waitForIdle()

        assertEquals(listOf("c1"), asked)
        compose.onNodeWithTag(FAKE_CHAT_TAG).assertTextEquals("chat:t-new")
    }

    @Test
    fun threadsRenderInLastMessageOrderWithUnreadBadge() {
        val now = System.currentTimeMillis()
        val state = loadState(
            threads = listOf(
                thread(id = "t-old", characterId = "c-old", preview = "在的", at = now - 86_400_000L),
                thread(id = "t-new", characterId = "c-new", preview = "晚上吃面吗", at = now - 1_000L, unread = 2),
            ),
            characters = listOf(
                MpCharacter(id = "c-old", name = "旧朋友"),
                MpCharacter(id = "c-new", name = "新朋友"),
            ),
        )

        openMessages(state)

        val newerTop = compose.onNodeWithTag(MiniPhoneTags.thread("t-new")).getBoundsInRoot().top
        val olderTop = compose.onNodeWithTag(MiniPhoneTags.thread("t-old")).getBoundsInRoot().top
        assertTrue("最后消息更新的会话应排在上面", newerTop < olderTop)

        compose.onNodeWithTag(MiniPhoneTags.unread("t-new"), useUnmergedTree = true).assertTextEquals("2")
        compose.onNodeWithTag(MiniPhoneTags.unread("t-old"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("晚上吃面吗").assertExists()
        compose.onNodeWithText("新朋友").assertExists()
    }

    @Test
    fun contactsAreGroupedByInitialInAlphabeticalOrder() {
        val sections = contactSectionsOf(
            listOf(
                MpCharacter(id = "c1", name = "阿离", tagline = "住在楼下"),
                MpCharacter(id = "c2", name = "Cara"),
                MpCharacter(id = "c3", name = "bob"),
                MpCharacter(id = "c4", name = "Zoe"),
            ),
        )
        assertEquals(listOf("B", "C", "Z", "#"), sections.map { it.key })

        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(state = ShellUiState(contactSections = sections))
            }
        }
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.CONTACTS)).performClick()

        val tops = listOf("B", "C", "Z", "#").map { key ->
            compose.onNodeWithTag(MiniPhoneTags.contactHeader(key)).getBoundsInRoot().top
        }
        assertEquals("分组标题必须按 B→C→Z→# 依次向下", tops.sorted(), tops)
        compose.onNodeWithTag(MiniPhoneTags.CONTACT_INDEX).assertExists()
        compose.onNodeWithText("Cara").assertExists()

        // 点右侧索引条跳到中文分组,验证索引条真的会带动列表
        compose.onNodeWithTag(MiniPhoneTags.contactIndex("#")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("阿离").assertExists()
    }

    @Test
    fun profileNicknameAndSignatureAreRendered() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(state = ShellUiState(profile = MeProfile))
            }
        }
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()

        compose.onNodeWithText(MeProfile.nickname).assertIsDisplayed()
        compose.onNodeWithText(requireNotNull(MeProfile.signature)).assertIsDisplayed()
    }

    @Test
    fun darkThemePassesDarkPaletteDown() {
        assertEquals(Color(0xFFF5F3EF), lightMiniPhoneColors().background)
        assertEquals(Color(0xFF12110F), darkMiniPhoneColors().background)
        assertEquals(Color(0xFF24211D), darkMiniPhoneColors().surface)
        assertEquals(Color(0xFF7C9B85), darkMiniPhoneColors().accent)

        var captured: Color? = null
        compose.setContent {
            MiniPhoneTheme(darkTheme = true) {
                captured = MiniPhoneTheme.colors.background
                MiniPhoneShell(state = ShellUiState())
            }
        }
        compose.waitForIdle()

        assertEquals(Color(0xFF12110F), captured)
    }

    @Test
    fun longPressThreadMenuPinsAndDeletesOnlyAfterConfirmation() {
        val pinned = mutableListOf<Pair<String, Boolean>>()
        val deleted = mutableListOf<String>()
        var markedRead = 0
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MessagesPage(
                    threads = listOf(threadUi(unread = 2)),
                    actions = ThreadMenuActions(
                        onTogglePin = { threadId, value -> pinned += threadId to value },
                        onMarkRead = { markedRead += 1 },
                        onDelete = { deleted += it },
                    ),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag(MiniPhoneTags.THREAD_MENU).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.MARK_READ)).assertIsDisplayed()

        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.PIN)).performClick()

        assertEquals("置顶带上方向", listOf("t1" to true), pinned)

        // 删除要二次确认:点了只弹确认框,确认之后才真删
        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.DELETE)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.THREAD_DELETE_DIALOG).assertIsDisplayed()
        assertEquals("确认之前不删", emptyList<String>(), deleted)

        compose.onNodeWithTag(MiniPhoneTags.THREAD_DELETE_CONFIRM).performClick()

        assertEquals(listOf("t1"), deleted)
        assertEquals("没点就没标记", 0, markedRead)
    }

    @Test
    fun longPressThreadMenuHidesRowsThatWouldDoNothing() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MessagesPage(threads = listOf(threadUi(unread = 0, pinned = false)))
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).performSemanticsAction(SemanticsActions.OnLongClick)

        // 未读为 0 不摆「标记已读」;没置顶不摆「取消置顶」
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.MARK_READ)).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.UNPIN)).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.PIN)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.threadMenuAction(ThreadMenuAction.DELETE)).assertIsDisplayed()
    }

    @Test
    fun mePageOpensMyMomentsAndShowsBothVersions() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(profile = MeProfile, appVersionName = "2.6.12"),
                    momentsContent = { onlyMine, _ ->
                        Text(
                            text = if (onlyMine) MINE_ONLY_CONTENT else ALL_CONTENT,
                            modifier = Modifier.testTag(FAKE_CHAT_TAG),
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.VERSION_LABEL).performScrollTo()
            .assertTextEquals("小手机数据 v${MINI_PHONE_DB_VERSION} / 应用 2.6.12")

        compose.onNodeWithTag(MiniPhoneTags.MY_MOMENTS_ROW).performScrollTo().performClick()

        // 我页进的是「只看自己」:两个入口不共用一份时间线
        compose.onNodeWithTag(FAKE_CHAT_TAG).assertTextEquals(MINE_ONLY_CONTENT)
    }

    @Test
    fun clearingChatsNeedsConfirmationBeforeReportingTheAction() {
        var cleared = 0
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(profile = MeProfile),
                    actions = MiniPhoneShellActions(onClearConversations = { cleared += 1 }),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.ME)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.CLEAR_CHATS_ROW).performScrollTo().performClick()
        compose.onNodeWithTag(MiniPhoneTags.CLEAR_CHATS_DIALOG).assertIsDisplayed()

        assertEquals("点入口只弹确认", 0, cleared)

        compose.onNodeWithTag(MiniPhoneTags.CLEAR_CHATS_CONFIRM).performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun versionLabelDropsTheHostPartWhenTheHostReportsNothing() {
        assertEquals("小手机数据 v$MINI_PHONE_DB_VERSION", miniPhoneVersionLabel(MINI_PHONE_DB_VERSION, ""))
        assertEquals("小手机数据 v$MINI_PHONE_DB_VERSION", miniPhoneVersionLabel(MINI_PHONE_DB_VERSION, "  "))
        assertEquals(
            "小手机数据 v$MINI_PHONE_DB_VERSION / 应用 2.6.12",
            miniPhoneVersionLabel(MINI_PHONE_DB_VERSION, "2.6.12"),
        )
    }

    /** 一行会话:默认带两条未读,置顶与未读由参数固定,便于断言菜单里该出现哪几行。 */
    private fun threadUi(unread: Int = 2, pinned: Boolean = false): MiniPhoneThreadUi = MiniPhoneThreadUi(
        id = "t1",
        name = "阿离",
        preview = "在吗",
        lastMessageAt = 1_000L,
        unreadCount = unread,
        pinned = pinned,
    )

    /** 从桌面切到消息页。 */
    private fun openMessages(state: ShellUiState) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(state = state)
            }
        }
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).performClick()
    }

    /** 真落库再读回来:顺带验证 DAO 的排序确实传达到了界面。 */
    private fun loadState(threads: List<MpThreadEntity>, characters: List<MpCharacter>): ShellUiState {
        val database = newInMemoryMiniPhoneDb()
        db = database
        return runBlocking {
            threads.forEach { database.threadDao().upsert(it) }
            assembleShellState(
                threads = database.threadDao().observeThreads().first(),
                characters = characters,
                profile = MeProfile,
            )
        }
    }

    private fun thread(id: String, characterId: String, preview: String, at: Long, unread: Int = 0): MpThreadEntity = MpThreadEntity(
        id = id,
        characterId = characterId,
        unreadCount = unread,
        lastMessagePreview = preview,
        lastMessageAt = at,
    )

    private fun launcherTag(tab: MiniPhoneTab): String = MiniPhoneTags.launcher(MiniPhoneApps.launcher.first { it.tab == tab })

    private companion object {
        val MeProfile = MpProfile(nickname = "子奇", signature = "慢慢来,比较快")
        const val FAKE_CHAT_TAG: String = "mp-shell-fake-chat"
        const val MINE_ONLY_CONTENT: String = "mine-only"
        const val ALL_CONTENT: String = "all-moments"
    }
}
