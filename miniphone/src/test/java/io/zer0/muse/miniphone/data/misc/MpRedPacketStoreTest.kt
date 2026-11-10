package io.zer0.muse.miniphone.data.misc

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpRedPacketEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 群红包门面：发红包、领红包。
 *
 * 金额只是玩法文案，这里不验任何账务；要守住的是「同一人不重复领」——
 * 名单是一段 JSON 文本，领过的人再点领取必须被拒，名单不能多出一条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpRedPacketStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpRedPacketStore
    private var seq = 0

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        seq = 0
        store = MpRedPacketStore(db, now = { 1_000L }, newId = { "id-${seq++}" })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `发红包落库为可领且名单为空`() = runBlocking {
        val id = requireNotNull(store.send("t1", "me", "8.88", "恭喜发财"))
        assertNotNull(id)
        val packet = requireNotNull(db.redPacketDao().get(id))
        assertEquals(MpRedPacketStore.STATE_OPEN, packet.state)
        assertEquals("[]", packet.claimedByJson)
        assertEquals("8.88", packet.amount)
        assertEquals("恭喜发财", packet.note)
    }

    @Test
    fun `空白金额不发`() = runBlocking {
        assertNull(store.send("t1", "me", "   "))
        assertEquals(0, store.observeByThread("t1").first().size)
    }

    @Test
    fun `领取把领取者写进名单`() = runBlocking {
        val id = requireNotNull(store.send("t1", "me", "6.66"))
        assertTrue(store.claim(id, "u1"))
        val packet = requireNotNull(db.redPacketDao().get(id))
        assertEquals(listOf("u1"), MpRedPacketStore.decodeClaimers(packet.claimedByJson))
    }

    @Test
    fun `同一人不能重复领`() = runBlocking {
        val id = requireNotNull(store.send("t1", "me", "6.66"))
        assertTrue(store.claim(id, "u1"))
        assertFalse(store.claim(id, "u1"))
        val packet = requireNotNull(db.redPacketDao().get(id))
        assertEquals(listOf("u1"), MpRedPacketStore.decodeClaimers(packet.claimedByJson))
    }

    @Test
    fun `不同人可以各领一次`() = runBlocking {
        val id = requireNotNull(store.send("t1", "me", "6.66"))
        assertTrue(store.claim(id, "u1"))
        assertTrue(store.claim(id, "u2"))
        val packet = requireNotNull(db.redPacketDao().get(id))
        assertEquals(listOf("u1", "u2"), MpRedPacketStore.decodeClaimers(packet.claimedByJson))
    }

    @Test
    fun `不存在的红包领取被拒`() = runBlocking {
        assertFalse(store.claim("missing", "u1"))
    }

    @Test
    fun `坏掉的名单文本当作没人领过`() = runBlocking {
        db.redPacketDao().insert(
            MpRedPacketEntity(
                id = "dirty",
                threadId = "t1",
                senderId = "me",
                amount = "1",
                state = MpRedPacketStore.STATE_OPEN,
                claimedByJson = "not-json",
                createdAt = 1,
            ),
        )
        assertTrue(store.claim("dirty", "u1"))
        assertEquals(listOf("u1"), MpRedPacketStore.decodeClaimers(requireNotNull(db.redPacketDao().get("dirty")).claimedByJson))
    }
}
