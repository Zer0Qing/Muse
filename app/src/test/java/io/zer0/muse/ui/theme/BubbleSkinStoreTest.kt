package io.zer0.muse.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 气泡皮肤存储(DataStore)测试。
 *
 * 遵循既有 Store 测试约定:单个测试方法内"先读默认值再写入",
 * 避免 DataStore 文件在测试方法间互相污染。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BubbleSkinStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val validStyle = BubbleRoleStyle(
        surfaceArgb = 0xFF1B2A38L,
        contentArgb = 0xFFF2F7FBL,
        radiusDp = 16f,
    )

    private fun skin(id: String, style: BubbleRoleStyle = validStyle): BubbleSkin = BubbleSkin(
        id = id,
        name = "Skin $id",
        light = mapOf(BubbleRole.USER to style, BubbleRole.ASSISTANT to style),
        dark = mapOf(BubbleRole.USER to style, BubbleRole.ASSISTANT to style),
    )

    @Test
    fun bubbleSkinStore_defaultsThenPersistsSelectionImportAndDelete() = runBlocking {
        val store = BubbleSkinStore(context)

        // 默认:没有自定义皮肤、选中内置 default → selectedSkinFlow 为 null,
        // 调用方传 null 即保持改造前的渲染路径。
        assertEquals(BubbleSkinCatalog.DEFAULT_SKIN_ID, store.selectedSkinIdFlow.first())
        assertTrue(store.customSkinsFlow.first().isEmpty())
        assertNull(store.selectedSkinFlow.first())

        // 保存合法皮肤并选中
        assertTrue(store.upsertSkin(skin("store-skin")))
        store.saveSelectedSkinId("store-skin")
        assertEquals("store-skin", store.selectedSkinFlow.first()?.id)
        assertEquals(1, store.customSkinsFlow.first().size)

        // 非法皮肤(对比度不足)写入被拒,现有列表/选中不变
        assertFalse(store.upsertSkin(skin("bad-skin", validStyle.copy(contentArgb = 0xFFDDDDDDL, surfaceArgb = 0xFFEEEEEE))))
        assertNull(store.selectedSkinFlow.first()?.takeIf { it.id == "bad-skin" })
        assertEquals(1, store.customSkinsFlow.first().size)

        // 导入非法内容不改变任何状态
        assertNull(store.importSkin("{ not json"))
        assertEquals(1, store.customSkinsFlow.first().size)

        // 导入合法 JSON 后可通过 id 选中
        val imported = store.importSkin(
            AppJson.encodeToString(BubbleSkin.serializer(), skin("imported-skin")),
        )
        assertEquals("imported-skin", imported?.id)
        store.saveSelectedSkinId("imported-skin")
        assertEquals("imported-skin", store.selectedSkinFlow.first()?.id)
        assertEquals(2, store.customSkinsFlow.first().size)

        // 删除当前选中皮肤 → 选中 id 清空并回退内置 default(null 皮肤)
        store.deleteSkin("imported-skin")
        assertEquals(BubbleSkinCatalog.DEFAULT_SKIN_ID, store.selectedSkinIdFlow.first())
        assertNull(store.selectedSkinFlow.first())
        assertEquals(1, store.customSkinsFlow.first().size)

        // 未选中自定义皮肤时再一次写内置 id 仍为默认行为
        store.saveSelectedSkinId(BubbleSkinCatalog.DEFAULT_SKIN_ID)
        assertNull(store.selectedSkinFlow.first())
    }
}
