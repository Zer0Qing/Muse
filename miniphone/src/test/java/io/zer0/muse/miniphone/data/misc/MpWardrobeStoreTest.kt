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
 * 衣橱门面：添衣、换装、删衣。
 *
 * 最关键的一条是「同一角色同一 kind 只能有一件穿着」—— 换第二件必须把第一件脱下来，
 * 否则角色会同时穿着两件上装，这正是换装玩法最容易坏掉的地方。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpWardrobeStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpWardrobeStore
    private var seq = 0

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        seq = 0
        store = MpWardrobeStore(db, now = { 1_000L }, newId = { "id-${seq++}" })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `添加衣物落库且默认未穿着`() = runBlocking {
        val id = store.addItem("c1", "白衬衫", MpWardrobeStore.KIND_TOP, "uri")
        assertNotNull(id)
        val rows = store.observeByCharacter("c1").first()
        assertEquals(1, rows.size)
        assertEquals("白衬衫", rows.first().name)
        assertEquals(0, rows.first().equipped)
    }

    @Test
    fun `空白名字不落库`() = runBlocking {
        assertNull(store.addItem("c1", "   ", MpWardrobeStore.KIND_TOP, "uri"))
        assertEquals(0, store.observeByCharacter("c1").first().size)
    }

    @Test
    fun `非法种类不落库`() = runBlocking {
        assertNull(store.addItem("c1", "帽子", "hat", "uri"))
        assertEquals(0, store.observeByCharacter("c1").first().size)
    }

    @Test
    fun `同一种类换装后只有一件穿着`() = runBlocking {
        val first = requireNotNull(store.addItem("c1", "旧上装", MpWardrobeStore.KIND_TOP, "u1"))
        val second = requireNotNull(store.addItem("c1", "新上装", MpWardrobeStore.KIND_TOP, "u2"))
        store.equip("c1", first)
        store.equip("c1", second)
        val equipped = store.observeEquipped("c1").first()
        assertEquals(1, equipped.size)
        assertEquals(second, equipped.first().id)
        assertEquals(1, store.observeByCharacter("c1").first().count { it.equipped != 0 })
    }

    @Test
    fun `不同种类可以同时穿着`() = runBlocking {
        val top = requireNotNull(store.addItem("c1", "上装", MpWardrobeStore.KIND_TOP, "u1"))
        val shoes = requireNotNull(store.addItem("c1", "鞋子", MpWardrobeStore.KIND_SHOES, "u2"))
        store.equip("c1", top)
        store.equip("c1", shoes)
        assertEquals(2, store.observeEquipped("c1").first().size)
    }

    @Test
    fun `换装不影响别的角色`() = runBlocking {
        val mine = requireNotNull(store.addItem("c1", "我的上装", MpWardrobeStore.KIND_TOP, "u1"))
        val other = requireNotNull(store.addItem("c2", "他的上装", MpWardrobeStore.KIND_TOP, "u2"))
        store.equip("c1", mine)
        store.equip("c2", other)
        assertEquals(1, store.observeEquipped("c1").first().size)
        assertEquals(1, store.observeEquipped("c2").first().size)
        assertTrue(store.observeEquipped("c1").first().all { it.characterId == "c1" })
    }

    @Test
    fun `删除衣物后衣柜为空`() = runBlocking {
        val id = requireNotNull(store.addItem("c1", "上装", MpWardrobeStore.KIND_TOP, "u1"))
        store.delete(id)
        assertEquals(0, store.observeByCharacter("c1").first().size)
    }
}
