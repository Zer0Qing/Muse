package io.zer0.muse.miniphone.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTags
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 群聊界面的三处不同:顶栏写群名与人数、角色气泡旁写谁说的、点顶栏能看成员。
 *
 * 断言落在「文字对不对 / 有没有那一行 / 顺序稳不稳」上,状态由测试直接喂。
 * 私信那一侧的反向断言同样重要:多出来的名字若在 1:1 里也出现,这一整块就成了噪音。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneGroupChatUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun groupTopBarShowsTheTitleWithTheMemberCount() {
        render(
            groupState(
                message("m1", characterId = "c1", content = "在的"),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.PEER_NAME, useUnmergedTree = true).assertTextEquals("阿远、小满、我")
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBER_COUNT, useUnmergedTree = true).assertTextEquals("（3）")
    }

    @Test
    fun characterBubblesInAGroupSayWhoIsTalking() {
        render(
            groupState(
                message("m1", characterId = "c2", content = "小满在这"),
                message("m2", characterId = "c1", content = "阿远也在", createdAt = TWO_MINUTES_MS),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.senderName("m1"), useUnmergedTree = true).assertTextEquals("小满")
        compose.onNodeWithTag(MiniPhoneChatTags.senderName("m2"), useUnmergedTree = true).assertTextEquals("阿远")
    }

    @Test
    fun ownMessageInAGroupCarriesNoSpeakerName() {
        render(groupState(message("mine", characterId = "", content = "大家早", sender = MpActorType.USER)))

        compose.onNodeWithTag(MiniPhoneChatTags.senderName("mine"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.bubble("mine")).assertIsDisplayed()
    }

    @Test
    fun directMessagesStayWithoutSpeakerNames() {
        render(
            ChatUiState(
                threadId = "t1",
                characterId = "c1",
                peerName = "阿远",
                messages = listOf(message("m1", characterId = "c1", content = "在的")),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.PEER_NAME, useUnmergedTree = true).assertTextEquals("阿远")
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBER_COUNT).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.senderName("m1"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun tappingTheTopBarListsEveryMemberInJoinOrder() {
        render(groupState(message("m1", characterId = "c1", content = "在的")))

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TOP_BAR).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBERS_PAGE).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBERS_COUNT).assertTextEquals("（3）")
        val tops = MEMBER_IDS.map { id ->
            compose.onNodeWithTag(MiniPhoneChatTags.member(id)).getBoundsInRoot().top
        }
        assertEquals("成员按入群先后自上而下", tops.sorted(), tops)
        MEMBER_IDS.forEach { id ->
            compose.onNodeWithTag(MiniPhoneChatTags.member(id)).assertIsDisplayed()
        }

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBERS_BACK).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBERS_PAGE).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.MESSAGE_LIST).assertIsDisplayed()
    }

    @Test
    fun typingRowNamesTheCharacterWhoIsAboutToSpeak() {
        render(
            groupState(message("m1", characterId = "c1", content = "在的"))
                .copy(isGenerating = true, typingName = "小满", typingAvatarUri = null),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.TYPING).assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.TYPING_NAME, useUnmergedTree = true).assertTextEquals("小满")
    }

    @Test
    fun directChatTypingRowStaysNameless() {
        render(
            ChatUiState(threadId = "t1", characterId = "c1", peerName = "阿远", isGenerating = true),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.TYPING).assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.TYPING_NAME, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun memberCountIncludesThePhoneOwner() {
        val state = groupState()
        assertEquals("两个角色 + 自己", 3, state.memberCount)
        assertEquals(0, ChatUiState(peerName = "阿远").memberCount)
    }

    @Test
    fun membersPageRenamesTheGroupAndRefusesABlankName() {
        var renamed: String? = null
        renderMembers(actions = GroupAdminActions(onRename = { title -> renamed = title }))

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_ROW).performClick()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_DIALOG).assertExists()

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_FIELD).performTextClearance()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_SAVE).performClick()
        compose.waitForIdle()
        assertEquals("空名不提交", null, renamed)

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_FIELD).performTextInput("周末小队")
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_SAVE).performClick()
        compose.waitForIdle()

        assertEquals("周末小队", renamed)
    }

    @Test
    fun addMemberPageListsOnlyPeopleWhoAreNotInTheGroup() {
        var added: List<String>? = null
        renderMembers(
            characters = FIVE_CHARACTERS,
            actions = GroupAdminActions(onAddMembers = { ids -> added = ids }),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_ADD_ENTRY).performClick()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_ADD_PAGE).assertExists()

        compose.onNodeWithTag(MiniPhoneTags.groupPick("c1")).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneTags.groupPick("c2")).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneTags.groupPick("c3")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_ADD_DONE).performClick()
        compose.waitForIdle()

        assertEquals(listOf("c3"), added)
    }

    @Test
    fun removingAMemberNeedsConfirmation() {
        var removed: String? = null
        renderMembers(actions = GroupAdminActions(onRemoveMember = { id -> removed = id }))

        compose.onNodeWithTag(MiniPhoneChatTags.member("c2")).performTouchInput { longClick() }
        compose.onNodeWithTag(MiniPhoneChatTags.MEMBER_MENU_REMOVE).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.MEMBER_MENU_REMOVE).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.MEMBER_REMOVE_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("移出后他之前说过的话留在群里。").assertExists()
        assertEquals("确认之前不移出", null, removed)

        compose.onNodeWithTag(MiniPhoneChatTags.MEMBER_REMOVE_CONFIRM).performClick()
        compose.waitForIdle()

        assertEquals("c2", removed)
    }

    @Test
    fun theLastMemberHasNoRemoveEntry() {
        renderMembers(members = listOf(MEMBERS.first()))

        compose.onNodeWithTag(MiniPhoneChatTags.member("c1")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.MEMBER_MENU_REMOVE).assertDoesNotExist()
        assertTrue("剩一个人时没有可做的动作", groupMemberActionsFor(1).isEmpty())
        assertEquals(1, groupMemberActionsFor(2).size)
    }

    @Test
    fun disbandingAsksFirstAndSaysItCannotBeUndone() {
        var disbanded = false
        renderMembers(actions = GroupAdminActions(onDisband = { disbanded = true }))

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_DISBAND_ENTRY).performClick()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_DISBAND_DIALOG).assertExists()
        assertTrue(
            "文案要写明不可恢复",
            compose.onAllNodesWithText("整个群和里面的全部消息都会从这台手机上删掉,无法恢复。").fetchSemanticsNodes().isNotEmpty(),
        )
        assertFalse("确认之前不解散", disbanded)

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_DISBAND_CONFIRM).performClick()
        compose.waitForIdle()

        assertTrue(disbanded)
    }

    @Test
    fun privateChatsHaveNoMemberManagementEntry() {
        render(ChatUiState(threadId = "t1", characterId = "c1", peerName = "阿远"))

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_TOP_BAR).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_MEMBERS_PAGE).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_ADD_ENTRY).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.GROUP_DISBAND_ENTRY).assertDoesNotExist()
    }

    private fun renderMembers(
        members: List<ChatMemberUi> = MEMBERS,
        characters: List<MpCharacter> = emptyList(),
        actions: GroupAdminActions = GroupAdminActions(),
    ) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                GroupMembersPage(
                    members = members,
                    title = "阿远、小满、我",
                    characters = characters,
                    onBack = {},
                    actions = actions,
                )
            }
        }
    }

    private fun render(state: ChatUiState) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(state = state, onBack = {}, onLoadOlder = {})
            }
        }
    }

    private fun groupState(vararg messages: ChatMessageUi): ChatUiState = ChatUiState(
        threadId = "t-group",
        characterId = "c1",
        isGroup = true,
        groupTitle = "阿远、小满、我",
        members = MEMBERS,
        selfName = "子奇",
        messages = messages.toList(),
    )

    private fun message(
        id: String,
        characterId: String,
        content: String,
        createdAt: Long = MINUTE_MS,
        sender: MpActorType = MpActorType.CHARACTER,
    ): ChatMessageUi = ChatMessageUi(
        id = id,
        characterId = characterId,
        sender = sender,
        type = MpMessageType.TEXT,
        content = content,
        createdAt = createdAt,
    )

    private companion object {
        const val MINUTE_MS: Long = 60_000L
        const val TWO_MINUTES_MS: Long = 120_000L
        val MEMBERS: List<ChatMemberUi> = listOf(
            ChatMemberUi(characterId = "c1", name = "阿远"),
            ChatMemberUi(characterId = "c2", name = "小满"),
        )
        val MEMBER_IDS: List<String> = MEMBERS.map { it.characterId }

        val FIVE_CHARACTERS: List<MpCharacter> = listOf(
            MpCharacter(id = "c1", name = "阿远"),
            MpCharacter(id = "c2", name = "小满"),
            MpCharacter(id = "c3", name = "阿吾"),
            MpCharacter(id = "c4", name = "阿岑"),
            MpCharacter(id = "c5", name = "阿迟"),
        )
    }
}
