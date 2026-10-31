package io.zer0.muse.miniphone.ui.moments

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import io.zer0.muse.miniphone.ui.chat.MiniPhoneChatTags
import io.zer0.muse.miniphone.ui.chat.MiniPhoneGalleryViewer
import io.zer0.muse.miniphone.ui.chat.galleryCounterOf
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
 * 朋友圈页的渲染验证:空态、九宫格怎么排、点赞计数随点击变化。
 *
 * 断言只落在「看不看得见 / 摆在哪个位置 / 数字对不对」上,不看颜色与像素。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneMomentsUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyTimelineShowsEmptyStateUnderTheCover() {
        render(MomentsUiState(isLoaded = true))

        compose.onNodeWithTag(MiniPhoneMomentsTags.COVER).assertExists()
        compose.onNodeWithTag(MiniPhoneMomentsTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("还没有动态").assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneMomentsTags.commentInput("m1")).assertDoesNotExist()
    }

    @Test
    fun cardRendersAuthorContentAndImages() {
        render(
            MomentsUiState(
                moments = listOf(momentUi(content = "今天的云很低", images = listOf("content://img/0"))),
                isLoaded = true,
            ),
        )

        compose.onNodeWithTag(MiniPhoneMomentsTags.moment("m1")).assertExists()
        compose.onNodeWithTag(MiniPhoneMomentsTags.content("m1"), useUnmergedTree = true)
            .assertTextEquals("今天的云很低")
        compose.onNodeWithTag(MiniPhoneMomentsTags.image("m1", 0)).assertExists()
    }

    @Test
    fun likeCountFollowsTheClick() {
        compose.setContent {
            var state by remember { mutableStateOf(MomentsUiState(moments = listOf(momentUi()), isLoaded = true)) }
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneMomentsScreen(
                    state = state,
                    onBack = {},
                    actions = MomentActions(
                        onToggleLike = { id ->
                            state = state.copy(
                                moments = state.moments.map { row ->
                                    if (row.id == id) row.copy(likes = listOf("子奇"), likedByMe = true) else row
                                },
                            )
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneMomentsTags.likeCount("m1"), useUnmergedTree = true).assertTextEquals("0")
        compose.onNodeWithTag(MiniPhoneMomentsTags.likeButton("m1")).performClick()
        compose.onNodeWithTag(MiniPhoneMomentsTags.likeCount("m1"), useUnmergedTree = true).assertTextEquals("1")
        compose.onNodeWithTag(MiniPhoneMomentsTags.likers("m1"), useUnmergedTree = true).assertTextEquals("❤️ 子奇")
    }

    @Test
    fun nineImagesWrapIntoThreeColumns() {
        render(MomentsUiState(moments = listOf(momentUi(images = nineImages())), isLoaded = true))

        val bounds = (0 until 9).map { index ->
            compose.onNodeWithTag(MiniPhoneMomentsTags.image("m1", index)).getBoundsInRoot()
        }

        // 第一行三张:同一 top,left 依次向右
        assertEquals(bounds[0].top.value, bounds[1].top.value, TOLERANCE)
        assertEquals(bounds[1].top.value, bounds[2].top.value, TOLERANCE)
        assertTrue("第二张应当在第一张右边", bounds[1].left > bounds[0].left)
        assertTrue("第三张应当在第二张右边", bounds[2].left > bounds[1].left)
        // 第四张换到下一行,并与第一张左对齐
        assertTrue("第四张应当换行", bounds[3].top > bounds[0].top)
        assertEquals(bounds[0].left.value, bounds[3].left.value, TOLERANCE)
    }

    @Test
    fun threeImagesSitInASingleRow() {
        render(MomentsUiState(moments = listOf(momentUi(images = nineImages().take(3))), isLoaded = true))

        val bounds = (0 until 3).map { index ->
            compose.onNodeWithTag(MiniPhoneMomentsTags.image("m1", index)).getBoundsInRoot()
        }

        assertEquals(bounds[0].top.value, bounds[1].top.value, TOLERANCE)
        assertEquals(bounds[1].top.value, bounds[2].top.value, TOLERANCE)
        assertTrue(bounds[2].left > bounds[1].left)
    }

    @Test
    fun publishScreenHandsOverTheOnlySelfSwitch() {
        var published: Triple<String, Int, Boolean>? = null
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneMomentsScreen(
                    state = MomentsUiState(isLoaded = true),
                    onBack = {},
                    actions = MomentActions(
                        onPublish = { content, images, onlySelf ->
                            published = Triple(content, images.size, onlySelf)
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneMomentsTags.COMPOSE_ENTRY).performClick()
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_SCREEN).assertExists()
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_VISIBLE_SELF).performClick()
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_INPUT).performTextInput("只给自己看")
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_CONFIRM).performClick()

        assertEquals(Triple("只给自己看", 0, true), published)
    }

    @Test
    fun gridColumnsFollowTheLayoutRule() {
        assertEquals("一张给一张大图", 1, momentGridColumns(1))
        assertEquals("两张两列", 2, momentGridColumns(2))
        assertEquals("三张一行", 3, momentGridColumns(3))
        assertEquals("四张 2×2", 2, momentGridColumns(4))
        assertEquals("九张三列", 3, momentGridColumns(9))
    }

    @Test
    fun likersLineFoldsLongLists() {
        assertEquals("❤️ 阿远，小满，老陈", likersLineOf(listOf("阿远", "小满", "老陈")))
        assertEquals("只有一个也带心", "❤️ 阿远", likersLineOf(listOf("阿远")))
        assertEquals("没人点赞就没有这一行", "", likersLineOf(emptyList()))

        val twelve = (1..12).map { index -> "人$index" }
        assertEquals("超过十人:前九个 + 等 12 人", "❤️ " + twelve.take(9).joinToString("，") + "，等 12 人", likersLineOf(twelve))
        assertFalse("第十个人不该出现", likersLineOf(twelve).contains("人10"))
    }

    @Test
    fun likersRendersAsOneLine() {
        render(MomentsUiState(moments = listOf(momentUi(likes = listOf("阿远", "小满", "老陈"))), isLoaded = true))

        compose.onNodeWithTag(MiniPhoneMomentsTags.likers("m1"), useUnmergedTree = true)
            .assertTextEquals("❤️ 阿远，小满，老陈")
    }

    @Test
    fun ownCommentOffersDeleteAndTheConfirmNamesTheCascade() {
        var deleted: Pair<String, String>? = null
        render(
            MomentsUiState(moments = listOf(momentUi(comments = listOf(commentUi(id = "k1", mine = true)))), isLoaded = true),
            MomentActions(onDeleteComment = { momentId, commentId -> deleted = momentId to commentId }),
        )

        compose.onNodeWithTag(MiniPhoneMomentsTags.commentRow("k1")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneMomentsTags.COMMENT_MENU_ACTION).assertIsDisplayed()

        compose.onNodeWithTag(MiniPhoneMomentsTags.COMMENT_MENU_ACTION).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneMomentsTags.COMMENT_DELETE_DIALOG).assertIsDisplayed()
        compose.onNodeWithText("这条评论和它下面的回复会一起删除。").assertIsDisplayed()
        assertEquals("确认之前不删", null, deleted)

        compose.onNodeWithTag(MiniPhoneMomentsTags.COMMENT_DELETE_CONFIRM).performClick()
        compose.waitForIdle()

        assertEquals("m1" to "k1", deleted)
    }

    @Test
    fun someoneElsesCommentHasNoDeleteEntry() {
        var deletes = 0
        render(
            MomentsUiState(moments = listOf(momentUi(comments = listOf(commentUi(id = "k1", mine = false)))), isLoaded = true),
            MomentActions(onDeleteComment = { _, _ -> deletes += 1 }),
        )

        compose.onNodeWithTag(MiniPhoneMomentsTags.commentRow("k1")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneMomentsTags.COMMENT_MENU_ACTION).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneMomentsTags.replyRow("k2")).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("别人的评论没有任何删除动作", 0, deletes)
    }

    @Test
    fun galleryOpensAtTheTappedImageAndSwipesBack() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneGalleryViewer(uris = nineImages(), initialIndex = 2, onDismiss = {})
            }
        }

        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_GALLERY_COUNTER, useUnmergedTree = true).assertTextEquals("3 / 9")

        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_GALLERY).performTouchInput { swipeRight() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_GALLERY_COUNTER, useUnmergedTree = true).assertTextEquals("2 / 9")
    }

    @Test
    fun galleryCounterTextIsOneBased() {
        assertEquals("3 / 9", galleryCounterOf(pageIndex = 2, pageCount = 9))
        assertEquals("1 / 1", galleryCounterOf(pageIndex = 0, pageCount = 1))
    }

    @Test
    fun coverCarriesSelfAvatarAndNickname() {
        render(MomentsUiState(selfName = "子奇", selfAvatarUri = "content://avatar/me", isLoaded = true))

        compose.onNodeWithTag(MiniPhoneMomentsTags.SELF_NAME, useUnmergedTree = true).assertTextEquals("子奇")
        compose.onNodeWithTag(MiniPhoneMomentsTags.SELF_AVATAR).assertExists()
    }

    private fun commentUi(id: String, mine: Boolean): MomentCommentUi = MomentCommentUi(
        id = id,
        senderName = if (mine) "子奇" else "阿离",
        content = if (mine) "我说的" else "他说的",
        createdAt = CREATED_AT,
        isMine = mine,
        replies = listOf(
            MomentReplyUi(
                id = "k2",
                senderName = "阿离",
                replyToName = null,
                content = "回你一句",
                createdAt = CREATED_AT,
                isMine = false,
            ),
        ),
    )

    private fun render(state: MomentsUiState, actions: MomentActions = MomentActions()) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneMomentsScreen(state = state, onBack = {}, actions = actions)
            }
        }
    }

    private fun nineImages(): List<String> = (0 until 9).map { index -> "content://img/$index" }

    private companion object {
        const val TOLERANCE: Float = 0.5f
    }
}

/** 一条用来渲染的动态。 */
internal fun momentUi(
    id: String = "m1",
    content: String = "今天的云很低",
    images: List<String> = emptyList(),
    likes: List<String> = emptyList(),
    likedByMe: Boolean = false,
    isMine: Boolean = false,
    comments: List<MomentCommentUi> = emptyList(),
) = MomentUi(
    id = id,
    authorId = "c1",
    authorName = "阿离",
    authorAvatarUri = null,
    content = content,
    images = images,
    createdAt = CREATED_AT,
    isMine = isMine,
    likes = likes,
    likedByMe = likedByMe,
    comments = comments,
)

private const val CREATED_AT: Long = 1_700_000_000_000L
