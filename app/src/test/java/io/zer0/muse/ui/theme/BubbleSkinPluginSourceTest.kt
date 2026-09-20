package io.zer0.muse.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 插件皮肤接入 [BubbleSkinStore] 的选择/回退语义测试。
 *
 * 遵循既有 Store 测试约定:单个测试方法内按顺序覆盖多个状态,避免 DataStore 文件在
 * 测试方法间互相污染;机器人插件来源用内存实现,不触碰真实插件目录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BubbleSkinPluginSourceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val validStyle = BubbleRoleStyle(
        surfaceArgb = 0xFF1B2A38L,
        contentArgb = 0xFFF2F7FBL,
        radiusDp = 16f,
    )

    @Test
    fun pluginSkinsParticipateInSelectionAndFallBackSafely() = runBlocking {
        val source = FakePluginSkinSource()
        val store = BubbleSkinStore(context, source)
        try {
            selectAndFallBack(store, source)
        } finally {
            // muse_settings DataStore 单例在同一 Robolectric sandbox 内被多个测试类共享:
            // 清理本测试写入,避免污染既有 BubbleSkinStoreTest 的默认值断言。
            store.deleteSkin("shared-id")
            store.saveSelectedSkinId(BubbleSkinCatalog.DEFAULT_SKIN_ID)
        }
    }

    private suspend fun selectAndFallBack(
        store: BubbleSkinStore,
        source: FakePluginSkinSource,
    ) {
        // 无插件皮肤时保持既有行为:选中内置 id → null。
        assertNull(store.selectedSkinFlow.first())

        source.entries = listOf(installedSkin("aurora", enabled = true))
        store.saveSelectedSkinId("aurora")
        assertEquals("aurora", store.selectedSkinFlow.first()?.id)
        assertEquals(listOf("aurora"), store.pluginSkinsFlow.first().map { it.skin.id })

        // 插件被禁用(来源以 enabled=false 返回)→ 稳定回退内置 default;设置页仍显示为不可用。
        source.entries = listOf(installedSkin("aurora", enabled = false))
        source.revision.value += 1
        assertNull(store.selectedSkinFlow.first())
        assertEquals(listOf(false), store.pluginSkinsFlow.first().map { it.enabled })

        // 重新启用 → 恢复该插件皮肤。
        source.entries = listOf(installedSkin("aurora", enabled = true))
        source.revision.value += 1
        assertEquals("aurora", store.selectedSkinFlow.first()?.id)

        // 插件被卸载 → 回退内置 default,条目消失。
        source.entries = emptyList()
        source.revision.value += 1
        assertNull(store.selectedSkinFlow.first())
        assertTrue(store.pluginSkinsFlow.first().isEmpty())

        // 同 id 时用户导入的自定义皮肤保持既有优先级,安装插件不改变用户已有外观。
        assertTrue(store.upsertSkin(skin("shared-id", name = "Custom shared")))
        source.entries = listOf(
            InstalledSkin(
                pluginId = "skin-pack",
                pluginName = "Aurora",
                version = "1.0.0",
                enabled = true,
                skin = skin("shared-id", name = "Plugin shared"),
            ),
        )
        source.revision.value += 1
        store.saveSelectedSkinId("shared-id")
        assertEquals("Custom shared", store.selectedSkinFlow.first()?.name)

        // 非法插件皮肤即使被来源标记为可用,也不会进入渲染路径(宿主侧最后一道校验)。
        source.entries = listOf(
            InstalledSkin(
                pluginId = "skin-pack",
                pluginName = "Aurora",
                version = "1.0.0",
                enabled = true,
                skin = skin("broken").copy(
                    light = mapOf(
                        BubbleRole.USER to validStyle.copy(
                            surfaceArgb = 0xFFEEEEEE,
                            contentArgb = 0xFFDDDDDD,
                        ),
                    ),
                ),
            ),
        )
        source.revision.value += 1
        store.saveSelectedSkinId("broken")
        assertNull(store.selectedSkinFlow.first())

        // 内置 default 的 id 永远不能被插件皮肤占用。
        source.entries = listOf(
            InstalledSkin(
                pluginId = "skin-pack",
                pluginName = "Aurora",
                version = "1.0.0",
                enabled = true,
                skin = skin(BubbleSkinCatalog.DEFAULT_SKIN_ID),
            ),
        )
        source.revision.value += 1
        store.saveSelectedSkinId(BubbleSkinCatalog.DEFAULT_SKIN_ID)
        assertNull(store.selectedSkinFlow.first())
    }

    private fun skin(id: String, name: String = "Skin $id"): BubbleSkin = BubbleSkin(
        id = id,
        name = name,
        light = mapOf(BubbleRole.USER to validStyle, BubbleRole.ASSISTANT to validStyle),
        dark = mapOf(BubbleRole.USER to validStyle, BubbleRole.ASSISTANT to validStyle),
    )

    private fun installedSkin(id: String, enabled: Boolean): InstalledSkin = InstalledSkin(
        pluginId = "skin-pack",
        pluginName = "Aurora",
        version = "1.0.0",
        enabled = enabled,
        skin = skin(id),
    )

    /** 内存版插件皮肤来源:测试通过修改 [entries] 并自增 [revision] 模拟插件状态变化。 */
    private class FakePluginSkinSource : PluginSkinSource {
        val revision = MutableStateFlow(0)
        var entries: List<InstalledSkin> = emptyList()

        override fun listInstalledSkins(includeDisabled: Boolean): List<InstalledSkin> =
            entries.filter { includeDisabled || it.enabled }

        override val revisionFlow: Flow<Int> = revision
    }
}
