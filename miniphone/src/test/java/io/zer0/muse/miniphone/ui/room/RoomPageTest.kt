package io.zer0.muse.miniphone.ui.room

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.zer0.muse.miniphone.data.room.DataStoreMpRoomStore
import io.zer0.muse.miniphone.data.room.MpRoomState
import io.zer0.muse.miniphone.data.room.MpRooms
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTags
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 房间页真的能打开：画布、锚点、房间切换、点家具挪人、返回。
 *
 * 这个页面此前**没有任何测试** —— 只有 store 的读写测试，页面本身从没被组合过。
 * 「编译过」不等于「能打开」：Compose 页面里放错修饰链、锚点算错位置、切换器点了没反应，
 * 都要到真机上才发现。这里把用户在这页会做的几件事各钉一条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomPageTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var store: DataStoreMpRoomStore
    private var backs = 0

    @Before
    fun setUp() {
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // 每个用例一个全新目录：TemporaryFolder 回收旧目录后再写就会 FileNotFound
        val dir = tempFolder.newFolder()
        val file = File(dir, "miniphone_settings.preferences_pb")
        store = DataStoreMpRoomStore(
            PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file }),
        )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private fun render(names: Map<String, String> = emptyMap()) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                RoomPage(store = store, occupantNames = names, onBack = { backs += 1 })
            }
        }
    }

    @Test
    fun `页面能打开并画出默认房间的画布`() {
        render()
        compose.onNodeWithTag(MiniPhoneTags.ROOM_PAGE).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.ROOM_CANVAS).assertIsDisplayed()
    }

    @Test
    fun `默认房间的每个锚点都在`() {
        render()
        val livingRoom = MpRooms.find(null)
        livingRoom.anchors.forEach { anchor ->
            compose.onNodeWithTag(MiniPhoneTags.roomAnchor(anchor.id)).assertIsDisplayed()
        }
    }

    @Test
    fun `点家具把本机用户挪过去`() {
        render()
        compose.onNodeWithTag(MiniPhoneTags.roomAnchor("sofa")).performClick()
        // 写入是异步的：点完立刻读会读到旧状态，等它真的变了再断言
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking { store.state() }.occupants[MpRoomState.LOCAL_USER] == "sofa"
        }
        assertEquals(MpRoomState.LOCAL_USER to "sofa", runBlocking { store.state() }.occupants.toList().first())
    }

    @Test
    fun `切换房间后画布换成新房间的锚点`() {
        render()
        val bedroom = MpRooms.all.first { it.id == "bedroom" }
        compose.onNodeWithTag(MiniPhoneTags.roomSwitcher(bedroom.id)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { runBlocking { store.state() }.roomId == bedroom.id }
        bedroom.anchors.forEach { anchor ->
            compose.onNodeWithTag(MiniPhoneTags.roomAnchor(anchor.id)).assertIsDisplayed()
        }
    }

    @Test
    fun `返回键交回上层`() {
        render()
        compose.onNodeWithTag(MiniPhoneTags.ROOM_BACK).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `别人在家时按名字画出他的小人`() {
        runBlocking { store.moveTo(anchorId = "window", participant = "c1") }
        render(names = mapOf("c1" to "阿离"))
        compose.onNodeWithTag(MiniPhoneTags.roomFigure("阿离")).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.roomFigure(MpRoomState.LOCAL_USER)).assertDoesNotExist()
    }
}
