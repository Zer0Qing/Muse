package io.zer0.muse.miniphone.ui.forum

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.entity.MpForumCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpForumPostEntity
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 论坛页：帖子与评论都由可替换的 Flow 注入，动作只记回调，不碰 Room。
 *
 * 断言文案时从资源取串 —— 测试进程的 locale 未必是中文，写死中文会在别的语言下假失败。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneForumScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val posts = MutableStateFlow(emptyList<MpForumPostEntity>())
    private val comments = MutableStateFlow(emptyList<MpForumCommentEntity>())
    private val published = mutableListOf<String>()
    private val commented = mutableListOf<Pair<String, String>>()
    private val liked = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private val messaged = mutableListOf<String>()
    private val composed = mutableListOf<String>()
    private var backClicks = 0
    private var nextId = 0

    private fun text(id: Int, vararg args: Any): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private fun post(id: String, authorId: String, content: String = "一条帖子", likeIdsJson: String = "[]") = MpForumPostEntity(
        id = id,
        authorId = authorId,
        authorName = "",
        content = content,
        likeIdsJson = likeIdsJson,
        createdAt = 1L,
    )

    private fun comment(id: String, postId: String, authorId: String, content: String) = MpForumCommentEntity(
        id = id,
        postId = postId,
        authorId = authorId,
        authorName = "",
        content = content,
        createdAt = 1L,
    )

    /** 假的点赞名单翻转：页面测试只需要「名单变了」这件事，不需要真的解析 JSON。 */
    private fun flipLikes(json: String): String = if (json.contains(SELF)) "[]" else "[\"$SELF\"]"

    private fun render() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneForumScreen(
                    posts = posts,
                    commentsFor = { postId -> comments.map { rows -> rows.filter { it.postId == postId } } },
                    currentUserId = SELF,
                    characters = listOf(MpCharacter(id = "c1", name = "阿离")),
                    onPublish = { content ->
                        published += content
                        posts.value = posts.value + post("p-${nextId++}", SELF, content)
                    },
                    onComment = { postId, content ->
                        commented += postId to content
                        comments.value = comments.value + comment("cm-${nextId++}", postId, SELF, content)
                    },
                    onToggleLike = { postId ->
                        liked += postId
                        posts.value = posts.value.map { row ->
                            if (row.id == postId) row.copy(likeIdsJson = flipLikes(row.likeIdsJson)) else row
                        }
                    },
                    onDelete = { postId ->
                        deleted += postId
                        posts.value = posts.value.filterNot { it.id == postId }
                    },
                    onComposePost = { composed += it },
                    onMessageAuthor = { messaged += it },
                    onBack = { backClicks += 1 },
                )
            }
        }
    }

    /** 等某一行真的组合出来：Flow 的首帧与列表项的落地都不是同步的。 */
    private fun awaitRow(id: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneForumTags.postRow(id)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(value: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `空态提示可见`() {
        render()
        compose.onNodeWithTag(MiniPhoneForumTags.PAGE).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneForumTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mp_forum_empty)).assertIsDisplayed()
    }

    @Test
    fun `空白输入不发帖`() {
        render()
        compose.onNodeWithTag(MiniPhoneForumTags.INPUT).performTextInput("   ")
        compose.onNodeWithTag(MiniPhoneForumTags.PUBLISH).performClick()
        assertEquals(0, published.size)
        compose.onNodeWithTag(MiniPhoneForumTags.EMPTY).assertIsDisplayed()
    }

    @Test
    fun `输入后发帖 正文去掉首尾空白且输入框清空`() {
        render()
        compose.onNodeWithTag(MiniPhoneForumTags.INPUT).performTextInput("  今天很好  ")
        compose.onNodeWithTag(MiniPhoneForumTags.PUBLISH).performClick()
        assertEquals(listOf("今天很好"), published)
        awaitRow("p-0")
        compose.onNodeWithText("今天很好").assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneForumTags.PUBLISH).performClick()
        assertEquals(1, published.size)
    }

    @Test
    fun `点赞触发回调并显示赞数与取消入口`() {
        posts.value = listOf(post("p1", "c1"))
        render()
        awaitRow("p1")
        compose.onNodeWithText(text(R.string.mp_forum_like)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneForumTags.like("p1")).performClick()
        assertEquals(listOf("p1"), liked)
        awaitText(text(R.string.mp_forum_like_count, 1))
        compose.onNodeWithText(text(R.string.mp_forum_liked)).assertIsDisplayed()
    }

    @Test
    fun `名单里已经有我时显示取消点赞`() {
        posts.value = listOf(post("p1", "c1", likeIdsJson = "[\"$SELF\"]"))
        render()
        awaitRow("p1")
        compose.onNodeWithText(text(R.string.mp_forum_liked)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mp_forum_like_count, 1)).assertIsDisplayed()
    }

    @Test
    fun `展开评论能看到已有评论并提交新评论`() {
        posts.value = listOf(post("p1", "c1"))
        comments.value = listOf(comment("cm1", "p1", "c2", "已有的评论"))
        render()
        awaitRow("p1")
        compose.onNodeWithTag(MiniPhoneForumTags.comments("p1")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneForumTags.commentRow("cm1")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("已有的评论").assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneForumTags.commentInput("p1")).performTextInput("  我也说一句  ")
        compose.onNodeWithTag(MiniPhoneForumTags.commentSend("p1")).performClick()
        assertEquals(listOf("p1" to "我也说一句"), commented)
        awaitText("我也说一句")
    }

    @Test
    fun `没有评论时展开显示提示 空白评论不提交`() {
        posts.value = listOf(post("p1", "c1"))
        render()
        awaitRow("p1")
        compose.onNodeWithTag(MiniPhoneForumTags.comments("p1")).performClick()
        awaitText(text(R.string.mp_forum_no_comments))
        compose.onNodeWithTag(MiniPhoneForumTags.commentInput("p1")).performTextInput("   ")
        compose.onNodeWithTag(MiniPhoneForumTags.commentSend("p1")).performClick()
        assertEquals(0, commented.size)
    }

    @Test
    fun `只有自己的帖子给删除入口`() {
        posts.value = listOf(post("mine", SELF, "我发的"), post("other", "c1", "别人发的"))
        render()
        awaitRow("mine")
        compose.onNodeWithTag(MiniPhoneForumTags.delete("mine")).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneForumTags.delete("other")).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneForumTags.delete("mine")).performClick()
        assertEquals(listOf("mine"), deleted)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneForumTags.postRow("mine")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `点作者只看他的帖子 点全部回到时间线`() {
        posts.value = listOf(post("a1", "c1", "阿离的"), post("b1", "c2", "别人的"))
        render()
        awaitRow("a1")
        compose.onNodeWithTag(MiniPhoneForumTags.author("c1")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneForumTags.postRow("b1")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(MiniPhoneForumTags.postRow("a1")).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneForumTags.ALL_AUTHORS).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneForumTags.postRow("b1")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `点角色名请他发帖`() {
        render()
        compose.onNodeWithTag(MiniPhoneForumTags.character("c1")).performClick()
        assertEquals(listOf("c1"), composed)
    }

    @Test
    fun `点返回触发回调`() {
        render()
        compose.onNodeWithTag(MiniPhoneForumTags.BACK).performClick()
        assertEquals(1, backClicks)
    }

    private companion object {
        /** 与门面用的本机用户 id 一致；页面只把它当成一个普通字符串比较。 */
        const val SELF = "local"
    }

    @Test
    fun `看别人的主页能发私信`() {
        posts.value = listOf(post("p1", "c1", "第一条"))
        render()
        awaitRow("p1")
        compose.onNodeWithTag(MiniPhoneForumTags.author("c1")).performClick()
        compose.onNodeWithTag(MiniPhoneForumTags.MESSAGE_AUTHOR).performClick()
        assertEquals(listOf("c1"), messaged)
    }

    @Test
    fun `看自己的主页没有发私信`() {
        posts.value = listOf(post("p1", SELF, "自己发的"))
        render()
        awaitRow("p1")
        compose.onNodeWithTag(MiniPhoneForumTags.author(SELF)).performClick()
        compose.onNodeWithTag(MiniPhoneForumTags.MESSAGE_AUTHOR).assertDoesNotExist()
    }
}
