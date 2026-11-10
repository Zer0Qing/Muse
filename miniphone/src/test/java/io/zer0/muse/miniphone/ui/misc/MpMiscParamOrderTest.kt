package io.zer0.muse.miniphone.ui.misc

import io.zer0.muse.miniphone.data.misc.MpRingtoneStore
import io.zer0.muse.miniphone.data.misc.MpWardrobeStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 两个「四个 String」的写入接口顺序相反，各自语义必须钉死。
 *
 * `MpRingtoneStore.add(characterId, name, uri, kind)` 与
 * `MpWardrobeStore.addItem(characterId, name, kind, uri)` —— 后两位一个是 `uri, kind`、
 * 另一个是 `kind, uri`。类型完全相同，错位编译器不会报，只会把铃声的地址写进「种类」列，
 * 表现为「加了铃声但列表里没有 / 点了播不出」。装配层一律用命名参数调用（见各 Feature），
 * 这条测试则从数据侧再兜一层：真写进去之后，每一列都要落在它该在的地方。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpMiscParamOrderTest {

    private lateinit var db: io.zer0.muse.miniphone.data.db.MiniPhoneDb

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `铃声的 uri 与 kind 各自落到自己的列`() = runBlocking {
        val store = MpRingtoneStore(db)
        val kind = MpRingtoneStore.KIND_VOICE
        val uri = "file:///voice/ring.mp3"
        val id = checkNotNull(store.add(characterId = "c1", name = "老式电话", uri = uri, kind = kind))
        val row = db.ringtoneDao().observeAll().first().single { it.id == id }
        assertEquals("uri 列要存地址", uri, row.uri)
        assertEquals("kind 列要存种类", kind, row.kind)
    }

    @Test
    fun `衣物的 kind 与 uri 各自落到自己的列`() = runBlocking {
        val store = MpWardrobeStore(db)
        val kind = MpWardrobeStore.KIND_TOP
        val uri = "file:///closet/shirt.png"
        val id = checkNotNull(store.addItem(characterId = "c1", name = "白衬衫", kind = kind, uri = uri))
        val row = db.wardrobeItemDao().observeByCharacter("c1").first().single { it.id == id }
        assertEquals("kind 列要存种类", kind, row.kind)
        assertEquals("uri 列要存地址", uri, row.uri)
    }

    @Test
    fun `顺序写反时会被校验挡住而不是写进错列`() = runBlocking {
        // 把 uri 当 kind 传：既不是合法种类、uri 也不该是空，写入必须被拒。
        val ringtone = MpRingtoneStore(db)
        val rejected = ringtone.add(
            characterId = "c1",
            name = "写反了",
            uri = MpRingtoneStore.KIND_VOICE,
            kind = "file:///voice/ring.mp3",
        )
        assertEquals("种类不合法时不能落库", null, rejected)
        assertEquals(0, db.ringtoneDao().observeAll().first().size)

        val wardrobe = MpWardrobeStore(db)
        val alsoRejected = wardrobe.addItem(
            characterId = "c1",
            name = "写反了",
            kind = "file:///closet/shirt.png",
            uri = MpWardrobeStore.KIND_TOP,
        )
        assertEquals("种类不合法时不能落库", null, alsoRejected)
        assertEquals(0, db.wardrobeItemDao().observeByCharacter("c1").first().size)
    }
}
