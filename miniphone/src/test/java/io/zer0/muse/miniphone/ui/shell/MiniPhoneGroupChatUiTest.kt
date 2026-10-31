package io.zer0.muse.miniphone.ui.shell

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 建群入口与消息页上的群会话。
 *
 * 两件事分开验:列表里群与私信能不能一眼分开(名字与预览标记),
 * 以及「+」进来之后选人、完成这一串真的会带着选中的 id 回去建群并进群。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneGroupChatUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var db: MiniPhoneDb? = null

    @After
    fun tearDown() {
        db?.close()
    }

    @Test
    fun groupThreadSitsInTheListWithItsTitleAndAPreviewMark() {
        val state = loadState(
            threads = listOf(
                thread(id = "t-group", characterId = "c1", at = NOW, preview = "晚上见", isGroup = true, title = "阿远、小满、我"),
                thread(id = "t-dm", characterId = "c1", at = NOW - 60_000L, preview = "在吗"),
            ),
            characters = listOf(MpCharacter(id = "c1", name = "阿远")),
        )
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(state = state)
            }
        }
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).performClick()

        compose.onNodeWithTag(MiniPhoneTags.thread("t-group")).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.thread("t-dm")).assertIsDisplayed()
        compose.onNodeWithText("阿远、小满、我").assertIsDisplayed()
        compose.onNodeWithText("[群] 晚上见").assertIsDisplayed()
        compose.onNodeWithText("在吗").assertIsDisplayed()
        assertEquals("[群] 在吗", groupPreviewOf("在吗"))
        assertEquals("没有最后一条消息时也要能认出是群", "[群]", groupPreviewOf(null))
    }

    @Test
    fun finishStaysDisabledUntilTwoPeopleAreSelectedAndThenCreatesTheGroup() {
        var created: List<String>? = null
        val sections = contactSectionsOf(
            listOf(
                MpCharacter(id = "c1", name = "阿远"),
                MpCharacter(id = "c2", name = "小满"),
                MpCharacter(id = "c3", name = "阿吾"),
            ),
        )
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(contactSections = sections),
                    chatContent = { threadId, _ ->
                        Text(text = "chat:$threadId", modifier = Modifier.testTag(FAKE_CHAT_TAG))
                    },
                    actions = MiniPhoneShellActions(
                        onCreateGroup = { ids ->
                            created = ids
                            "t-group"
                        },
                    ),
                )
            }
        }
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_ENTRY).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_ENTRY).performClick()

        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_PAGE).assertIsDisplayed()
        // 一个人都不选:完成按不动,点了也不会建群
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_DONE).assertIsNotEnabled()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_DONE).performClick()
        compose.waitForIdle()
        assertEquals("没选人不能建群", null, created)

        // 只选一个:还是按不动
        compose.onNodeWithTag(MiniPhoneTags.groupPick("c1")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_COUNT).assertTextEquals("已选 1 人,至少选 2 人")
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_DONE).assertIsNotEnabled()

        // 选到两个:完成可用,点下去带着这两个 id 建群并直接进群
        compose.onNodeWithTag(MiniPhoneTags.groupPick("c2")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_DONE).assertIsEnabled()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_DONE).performClick()
        compose.waitForIdle()

        assertEquals(listOf("c1", "c2"), created)
        compose.onNodeWithTag(FAKE_CHAT_TAG).assertTextEquals("chat:t-group")
    }

    @Test
    fun pickedRowsShowTheirCheckMarkAndCanBeUnpicked() {
        val sections = contactSectionsOf(listOf(MpCharacter(id = "c1", name = "阿远"), MpCharacter(id = "c2", name = "小满")))
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                GroupCreatePage(
                    sections = sections,
                    selectedIds = setOf("c1"),
                    onToggle = { },
                    onBack = { },
                    onDone = { },
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.groupPickMark("c1"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(MiniPhoneTags.GROUP_CREATE_COUNT).assertTextEquals("已选 1 人,至少选 2 人")
    }

    /** 真落库再读回来,顺带验证群名与预览标记确实从实体走到了列表。 */
    private fun loadState(threads: List<MpThreadEntity>, characters: List<MpCharacter>): ShellUiState {
        val database = newInMemoryMiniPhoneDb()
        db = database
        return runBlocking {
            threads.forEach { database.threadDao().upsert(it) }
            assembleShellState(
                threads = database.threadDao().observeThreads().first(),
                characters = characters,
                profile = MpProfile(nickname = "子奇"),
            )
        }
    }

    private fun thread(
        id: String,
        characterId: String,
        at: Long,
        preview: String,
        isGroup: Boolean = false,
        title: String = "",
    ): MpThreadEntity = MpThreadEntity(
        id = id,
        characterId = characterId,
        lastMessagePreview = preview,
        lastMessageAt = at,
        isGroup = isGroup,
        title = title,
    )

    private companion object {
        const val NOW: Long = 1_700_000_000_000L
        const val FAKE_CHAT_TAG: String = "mp-group-fake-chat"
    }
}
