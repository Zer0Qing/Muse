package io.zer0.muse.miniphone.ui.chat

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * 图片消息与自己的头像:渲染分支、破图回落、全屏看图的开合、图片入口的回调。
 *
 * 状态直接喂给无状态组件,不经过 ViewModel;能不能拉起系统选择器属于上层的事,
 * 这里只验「按下去把请求交出去了」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneChatImageUiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("mp-image-ui").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun imageMessageRendersAnImageNodeInsteadOfATextBubble() {
        render(
            chatState(
                message("m1", MpActorType.CHARACTER, "看这个"),
                imageMessage("m2", MpActorType.USER, imageFile().absolutePath),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.image("m2")).assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m2")).assertDoesNotExist()
        // 同一屏里的文本气泡不受影响
        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).assertExists()
        compose.onNodeWithText("看这个").assertExists()
    }

    @Test
    fun missingImageFileShowsTheBrokenPlaceholderInsteadOfCrashing() {
        render(chatState(imageMessage("m1", MpActorType.USER, "/no/such/dir/photo.jpg")))

        compose.onNodeWithTag(MiniPhoneChatTags.imageBroken("m1"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("图片无法显示").assertExists()
    }

    @Test
    fun tappingAnImageOpensTheFullScreenViewerAndTappingAnywhereClosesIt() {
        render(chatState(imageMessage("m1", MpActorType.USER, imageFile().absolutePath)))
        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_VIEWER).assertDoesNotExist()

        compose.onNodeWithTag(MiniPhoneChatTags.image("m1")).performClick()
        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_VIEWER).assertExists()

        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_VIEWER).performClick()
        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_VIEWER).assertDoesNotExist()
    }

    @Test
    fun ownBubbleShowsTheProfileAvatarOnTheRightWhenThereIsOne() {
        render(
            chatState(message("m1", MpActorType.USER, "在吗"))
                .copy(selfName = "子奇", selfAvatarUri = "https://example.com/avatar.png"),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.SELF_AVATAR).assertExists()
        val rootWidth = widthOf(compose.onRoot().getBoundsInRoot())
        val avatar = compose.onNodeWithTag(MiniPhoneChatTags.SELF_AVATAR).getBoundsInRoot()
        assertTrue("自己的头像在气泡右侧", avatar.left > rootWidth / 2f)
    }

    @Test
    fun ownBubbleFallsBackToTheNicknameInitialWhenThereIsNoAvatar() {
        render(chatState(message("m1", MpActorType.USER, "在吗")).copy(selfName = "子奇", selfAvatarUri = null))

        compose.onNodeWithTag(MiniPhoneChatTags.SELF_AVATAR).assertExists()
        compose.onNodeWithText("子").assertExists()
    }

    @Test
    fun imageButtonHandsThePickRequestToTheCaller() {
        var picks = 0
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(
                    state = chatState(),
                    onBack = {},
                    onLoadOlder = {},
                    input = ChatInputActions(onSend = {}, onPickImages = { picks += 1 }),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneChatTags.PICK_IMAGE).performClick()

        assertEquals("按一下就把请求交出去", 1, picks)
        // 图片入口不该影响发送键：空输入仍然按不动
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).assertIsNotEnabled()
    }

    private fun render(state: ChatUiState) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(
                    state = state,
                    onBack = {},
                    onLoadOlder = {},
                    input = ChatInputActions(),
                )
            }
        }
    }

    /** 真 PNG 落到临时目录:气泡按文件是否存在决定画图还是画破图。 */
    private fun imageFile(): File {
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        return File(directory, "photo.png").apply { writeBytes(bytes) }
    }

    private fun widthOf(bounds: DpRect): Dp = bounds.right - bounds.left

    private fun chatState(vararg messages: ChatMessageUi): ChatUiState = ChatUiState(
        threadId = "t1",
        characterId = "c1",
        peerName = "阿离",
        messages = messages.toList(),
    )

    private fun message(id: String, sender: MpActorType, content: String, createdAt: Long = MINUTE_MS): ChatMessageUi = ChatMessageUi(
        id = id,
        sender = sender,
        type = MpMessageType.TEXT,
        content = content,
        createdAt = createdAt,
    )

    private fun imageMessage(id: String, sender: MpActorType, path: String, createdAt: Long = MINUTE_MS): ChatMessageUi = ChatMessageUi(
        id = id,
        sender = sender,
        type = MpMessageType.IMAGE,
        content = "[图片]",
        mediaUri = path,
        createdAt = createdAt,
    )

    private companion object {
        const val MINUTE_MS: Long = 60_000L
    }
}
