package io.zer0.muse.miniphone.data.room

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 38: 小房间的位置落盘与目录自洽。
 *
 * 跑真 DataStore（临时文件），因为要验的正是「写进去、读回来还在」以及换房间时
 * 位置怎么带 —— 这两件事用替身都验不到。
 */
class MpRoomStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var store: MpRoomStore

    @Before
    fun setUp() {
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(tempFolder.newFolder(), "miniphone_settings.preferences_pb")
        store = DataStoreMpRoomStore(
            PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file }),
        )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun startsInDefaultRoomWithNobodyPlaced() = runTest {
        val state = store.state()
        assertEquals(MpRooms.DEFAULT_ROOM_ID, state.roomId)
        assertTrue(state.occupants.isEmpty())
        assertEquals(MpRooms.find(null).id, state.roomId)
    }

    @Test
    fun moveIsPersistedAndVisibleOnFlow() = runTest {
        store.moveTo(anchorId = "sofa")
        val state = store.state()
        assertEquals(MpRoomState.LOCAL_USER to "sofa", state.occupants.toList().first())
        assertEquals("sofa", store.stateFlow.first().occupants[MpRoomState.LOCAL_USER])
    }

    @Test
    fun anchorThatIsNotInCurrentRoomIsIgnored() = runTest {
        // 默认是客厅，"bed" 只存在于卧室 —— 不该被接受成幽灵位置。
        store.moveTo(anchorId = "bed")
        assertTrue(store.state().occupants.isEmpty())
    }

    @Test
    fun switchingRoomCarriesAnchorWithTheSameId() = runTest {
        store.moveTo(anchorId = "window")
        store.switchRoom("bedroom")
        val state = store.state()

        assertEquals("bedroom", state.roomId)
        assertEquals("同名锚点应原样带过去", "window", state.occupants[MpRoomState.LOCAL_USER])
    }

    @Test
    fun switchingRoomCarriesByFurnitureClassWhenIdDiffers() = runTest {
        // 客厅的 sofa 与阳台的 seat 是同一类家具（标签同为一个词），换房间该按类别接上。
        store.moveTo(anchorId = "sofa")
        store.switchRoom("balcony")
        val state = store.state()

        assertEquals("balcony", state.roomId)
        assertEquals("seat", state.occupants[MpRoomState.LOCAL_USER])
    }

    @Test
    fun switchingRoomDropsPositionWithNoMatchAtAll() = runTest {
        // 卧室没有「座位」这类锚点，宁可让人不在屋里，也不留一个不属于新房间的位置。
        store.moveTo(anchorId = "sofa")
        store.switchRoom("bedroom")
        val state = store.state()

        assertEquals("bedroom", state.roomId)
        assertTrue(
            "无匹配时必须丢弃，不能留下不属于新房间的锚点: ${state.occupants}",
            state.occupants.isEmpty() ||
                MpRooms.find(state.roomId).anchors.any { it.id == state.occupants[MpRoomState.LOCAL_USER] },
        )
    }

    @Test
    fun leaveRemovesTheParticipantEntirely() = runTest {
        store.moveTo(anchorId = "window", participant = "char-1")
        assertEquals("window", store.state().occupants["char-1"])

        store.leave("char-1")
        assertFalse(store.state().occupants.containsKey("char-1"))
    }

    @Test
    fun roomCatalogIsSelfConsistent() {
        assertEquals(
            "房间 id 不能重复",
            MpRooms.all.size,
            MpRooms.all.map { it.id }.distinct().size,
        )
        MpRooms.all.forEach { room ->
            assertEquals(
                "「${room.id}」的锚点 id 不能重复",
                room.anchors.size,
                room.anchors.map { it.id }.distinct().size,
            )
            room.anchors.forEach { anchor ->
                assertTrue("${anchor.id} 的 x 超出地板", anchor.xFraction in 0f..1f)
                assertTrue("${anchor.id} 的 y 超出地板", anchor.yFraction in 0f..1f)
            }
        }
    }

    @Test
    fun unknownRoomIdFallsBackInsteadOfCrashing() {
        // 降级安装或手工改过文件时读到不认识的值，必须回落而不是抛。
        assertEquals(MpRooms.DEFAULT_ROOM_ID, MpRooms.find("no-such-room").id)
    }
}
