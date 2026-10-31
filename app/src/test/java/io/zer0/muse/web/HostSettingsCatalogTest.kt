package io.zer0.muse.web

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.feature.FeatureFlags
import io.zer0.muse.ui.settings.SettingsCatalog
import io.zer0.muse.ui.settings.SettingsEntryAction
import io.zer0.muse.ui.settings.SettingsToggleKey
import org.junit.Assert.assertEquals
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
        val appGroups = SettingsCatalog.groups.map { resourceName(it.titleRes) }
        assertEquals(
            "分组与设置清单不一致:\nApp = $appGroups\n协议 = ${HostSettingsCatalog.groups.map { it.key }}",
            appGroups,
            HostSettingsCatalog.groups.map { it.key },
        )
    }

    @Test
    fun `catalog entries match each group's declared entries`() {
        SettingsCatalog.groups.forEachIndexed { index, group ->
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
                assertEquals(
                    "条目 ${hostEntry.id} 的说明资源不一致",
                    entry.descRes?.let { resourceName(it) },
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
        val hostMiniPhone = HostSettingsCatalog.entries.single { it.id == "miniphone" }
        assertEquals(
            "小手机入口的可用状态必须与编译期闸门一致",
            FeatureFlags.MINI_PHONE,
            hostMiniPhone.enabled,
        )
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
