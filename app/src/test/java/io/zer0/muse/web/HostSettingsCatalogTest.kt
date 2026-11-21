package io.zer0.muse.web

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.feature.FeatureFlags
import io.zer0.muse.ui.settings.SettingsCatalog
import io.zer0.muse.ui.settings.SettingsGroupKind
import io.zer0.muse.ui.settings.SettingsEntryAction
import io.zer0.muse.ui.settings.SettingsToggleKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * settings.snapshot 的分组清单与设置清单的一致性锁定。
 *
 * App 端的顺序、标题、开关归属只在
 * [io.zer0.muse.ui.settings.SettingsCatalog] 里声明一份；[HostSettingsCatalog] 是它给
 * Web 端用的协议投影（协议里的 key 是字符串资源名）。这里把清单里的资源 id 还原成资源名，
 * 逐组逐项比对：任一侧换了分组、换了顺序、改了标题或增删条目都会失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSettingsCatalogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resources: Resources = context.resources

    @Test
    fun `catalog groups match the settings groups and order`() {
        // 2.6.27: 小手机整体收起后，两侧都不再列出该分组。
        // 这里按「可见分组」比对：镜像契约要求的是**用户能看到的清单一致**，
        // 而不是把已收起的分类强行留在协议侧。
        val appGroups = visibleAppGroups()
        assertEquals(
            "分组与设置清单不一致:\nApp = $appGroups\n协议 = ${HostSettingsCatalog.groups.map { it.key }}",
            appGroups,
            HostSettingsCatalog.groups.map { it.key },
        )
    }

    /**
     * App 侧用户可见的分组。
     *
     * 2026-11-22: 原来这里要按 `FeatureFlags.MINI_PHONE` 过滤掉收起的小手机分类；
     * 该分类已并入 CONNECTION，不再有需要按开关隐藏的独立分组，过滤条件随之去掉。
     */
    private fun visibleAppGroups(): List<String> =
        SettingsCatalog.groups.map { resourceName(it.titleRes) }

    @Test
    fun `catalog entries match each group's declared entries`() {
        val visibleGroups = SettingsCatalog.groups
        visibleGroups.forEachIndexed { index, group ->
            val hostGroup = HostSettingsCatalog.groups[index]
            val appEntries = group.entries
            assertEquals(
                "「${hostGroup.key}」条目数不一致",
                appEntries.size,
                hostGroup.entries.size,
            )
            appEntries.forEachIndexed { entryIndex, entry ->
                val hostEntry = hostGroup.entries[entryIndex]
                assertEquals(
                    "「${hostGroup.key}」第 ${entryIndex + 1} 项的 id 不一致",
                    entry.id.name.lowercase(),
                    hostEntry.id,
                )
                assertEquals(
                    "条目 ${hostEntry.id} 的标题资源不一致",
                    resourceName(entry.titleRes),
                    hostEntry.titleKey,
                )
                // 静态说明已从 App 与 Web 两侧一并删除（设置行只留标题）。
                // 这条断言改成守护「两侧都不再声明静态说明」——若将来有人只加回一侧，这里会红。
                assertNull(
                    "条目 ${hostEntry.id} 仍带静态说明（App 侧或 Web 侧）",
                    hostEntry.descKey,
                )
                assertEquals(
                    "条目 ${hostEntry.id} 的类型与声明动作不一致",
                    expectedKind(entry.action),
                    hostEntry.kind,
                )
            }
        }
    }

    @Test
    fun `every entry carries the fields the web client renders`() {
        val ids = HostSettingsCatalog.entries.map { it.id }
        assertEquals("入口 id 必须唯一: $ids", ids.size, ids.toSet().size)

        HostSettingsCatalog.entries.forEach { entry ->
            assertTrue("条目 ${entry.id} 缺少 id", entry.id.isNotBlank())
            assertTrue("条目 ${entry.id} 缺少 titleKey", entry.titleKey.isNotBlank())
            assertTrue("条目 ${entry.id} 缺少 iconKey", entry.iconKey.isNotBlank())
            assertTrue("条目 ${entry.id} 的 groupKey 为空", entry.groupKey.isNotBlank())
            assertTrue(
                "条目 ${entry.id} 的 kind 不在约定取值内: ${entry.kind}",
                entry.kind in setOf(
                    HostSettingsCatalog.KIND_LINK,
                    HostSettingsCatalog.KIND_SWITCH,
                    HostSettingsCatalog.KIND_ACTION,
                ),
            )
        }

        // 分组内 entry 的 groupKey 必须就是所属分组,避免后续手改漏同步。
        HostSettingsCatalog.groups.forEach { group ->
            group.entries.forEach { entry ->
                assertEquals("条目 ${entry.id} 的 groupKey 与所属分组不一致", group.key, entry.groupKey)
            }
        }
    }

    @Test
    fun `switch entries point at writable setting keys`() {
        val appToggles = SettingsCatalog.entries
            .filter { it.action is SettingsEntryAction.Toggle }
            .map { (it.action as SettingsEntryAction.Toggle).key }
        val switches = HostSettingsCatalog.entries.filter { it.kind == HostSettingsCatalog.KIND_SWITCH }
        assertTrue("设置清单至少应有一个开关入口", switches.isNotEmpty())
        assertEquals("开关条目数量与清单不一致", appToggles.size, switches.size)

        appToggles.zip(switches).forEach { (toggleKey, entry) ->
            assertEquals(
                "开关条目 ${entry.id} 的键与清单不一致",
                appSettingKey(toggleKey),
                entry.toggleKey,
            )
            assertTrue(
                "开关条目 ${entry.id} 的 toggleKey=${entry.toggleKey} 不在 settings.update 白名单内",
                entry.toggleKey in HostSettingDefinitions.writableKeys,
            )
        }
    }

    @Test
    fun `compile time gated entries keep their flag state`() {
        // 2026-11-22: 小手机原来独占一个分组，闸门关掉时整组不出现，所以这里断言「条目不存在」。
        // 并入连接组后组本身必须存在（里面还有别的条目），闸门只控制这一条是否可点：
        // 关闭时条目仍在目录里但 enabled=false，开启时才可进子页。
        val hostMiniPhone = HostSettingsCatalog.entries.firstOrNull { it.id == "miniphone" }
        assertNotNull("合并分组后该条目始终在目录里（组还在）", hostMiniPhone)
        assertEquals("闸门状态必须如实反映在 enabled 上", FeatureFlags.MINI_PHONE, hostMiniPhone?.enabled)
    }

    private fun expectedKind(action: SettingsEntryAction): String = when (action) {
        is SettingsEntryAction.Navigate -> HostSettingsCatalog.KIND_LINK
        is SettingsEntryAction.Toggle -> HostSettingsCatalog.KIND_SWITCH
        is SettingsEntryAction.Action -> HostSettingsCatalog.KIND_ACTION
    }

    /** 开关条目在 settings.update 里的键；键名只在 [HostSettingKeys] 声明一次。 */
    private fun appSettingKey(key: SettingsToggleKey): String = when (key) {
        SettingsToggleKey.PII_GUARD -> HostSettingKeys.PII_GUARD
    }

    private fun resourceName(resId: Int): String = resources.getResourceEntryName(resId)
}