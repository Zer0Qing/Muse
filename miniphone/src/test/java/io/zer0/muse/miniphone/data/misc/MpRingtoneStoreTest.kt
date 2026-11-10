package io.zer0.muse.miniphone.data.misc

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 铃声门面：按角色与通话种类取铃声、添加、删除。
 *
 * 要守住的是「空角色的通用铃声也要能被取到」—— 一段通用铃声不该因为没绑角色而在任何通话里消失；
 * 同时种类要分得清，语音铃声不会混进视频通话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpRingtoneStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpRingtoneStore
    private var seq = 0

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        seq = 0
        store = MpRingtoneStore(db, now = { 1_000L }, newId = { "id-${seq++}" })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `添加角色铃声落库`() = runBlocking {
        val id = store.add("c1", "专属铃声", "uri", MpRingtoneStore.KIND_VOICE)
        assertNotNull(id)
        val rows = store.observeFor("c1", MpRingtoneStore.KIND_VOICE).first()
        assertEquals(1, rows.size)
        assertEquals("专属铃声", rows.first().name)
    }

    @Test
    fun `通用铃声对任何角色都取得到`() = runBlocking {
        store.add("", "通用铃声", "u0", MpRingtoneStore.KIND_VOICE)
        store.add("c1", "专属铃声", "u1", MpRingtoneStore.KIND_VOICE)
        assertEquals(2, store.observeFor("c1", MpRingtoneStore.KIND_VOICE).first().size)
        val forOther = store.observeFor("c2", MpRingtoneStore.KIND_VOICE).first()
        assertEquals(1, forOther.size)
        assertEquals("通用铃声", forOther.first().name)
    }

    @Test
    fun `空白名字或素材地址不落库`() = runBlocking {
        assertNull(store.add("c1", "   ", "uri", MpRingtoneStore.KIND_VOICE))
        assertNull(store.add("c1", "名字", "   ", MpRingtoneStore.KIND_VOICE))
        assertEquals(0, store.observeAll().first().size)
    }

    @Test
    fun `按通话种类过滤互不混`() = runBlocking {
        store.add("c1", "语音铃声", "u1", MpRingtoneStore.KIND_VOICE)
        store.add("c1", "视频铃声", "u2", MpRingtoneStore.KIND_VIDEO)
        val voice = store.observeFor("c1", MpRingtoneStore.KIND_VOICE).first()
        val video = store.observeFor("c1", MpRingtoneStore.KIND_VIDEO).first()
        assertEquals(1, voice.size)
        assertEquals(MpRingtoneStore.KIND_VOICE, voice.first().kind)
        assertEquals(1, video.size)
        assertEquals(MpRingtoneStore.KIND_VIDEO, video.first().kind)
    }

    @Test
    fun `非法种类不落库`() = runBlocking {
        assertNull(store.add("c1", "铃声", "uri", "ring"))
        assertEquals(0, store.observeAll().first().size)
    }

    @Test
    fun `删除后列表为空`() = runBlocking {
        val id = requireNotNull(store.add("c1", "铃声", "uri", MpRingtoneStore.KIND_VOICE))
        store.delete(id)
        assertTrue(store.observeAll().first().isEmpty())
    }
}
