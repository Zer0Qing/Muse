package io.zer0.muse.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置搜索索引的结构与命中锁定。
 *
 * 索引由分类清单里的 47 项加上「只做搜索直达」的页内设置组成，全部记录都必须挂在
 * 16 个一级分类之一上：搜索结果靠这个分类名拼面包屑，缺了它用户就不知道自己会被带到哪。
 * 这里用真实资源跑一遍过滤与面包屑，键名改了、分组搬了都会断出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsSearchIndexTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val groupTitles: Map<SettingsGroupKind, String> =
        SettingsCatalog.groups.associate { group -> group.kind to text(group.titleRes) }
    private val records: List<SettingsSearchRecord> = SettingsSearchCatalog.records

    @Test
    fun `index covers the settings catalog and extra deep links`() {
        val catalogEntries = SettingsCatalog.entries.filter { entry ->
            entry.id != SettingsEntryId.MINIPHONE || io.zer0.muse.feature.FeatureFlags.MINI_PHONE
        }
        assertTrue("索引至少要覆盖分类清单里可到达的 ${catalogEntries.size} 项", records.size >= catalogEntries.size)

        catalogEntries.forEach { entry ->
            assertTrue(
                "分类清单里的条目「${text(entry.titleRes)}」没有进搜索索引",
                records.any { it.titleRes == entry.titleRes },
            )
        }
    }

    /**
     * 开关类命中必须带上能定位到行的条目 id:只把用户丢进分类页,等于让他自己再翻一遍,
     * 而"直达"正是设置搜索的价值所在。id 丢了,分类页的高亮与滚动就无从计算。
     */
    @Test
    fun `toggle records carry the entry id used for locate`() {
        val toggles = records.filter { it.action is SettingsEntryAction.Toggle }
        assertTrue("索引里至少要有一个开关条目", toggles.isNotEmpty())
        toggles.forEach { record ->
            assertNotNull("开关条目「${text(record.titleRes)}」缺少定位用的条目 id", record.entryId)
            assertTrue(
                "开关条目的 id 必须能在分类清单里找到对应行",
                SettingsCatalog.entries.any { it.id == record.entryId },
            )
        }
    }

    @Test
    fun `every indexed entry is reachable by searching its own title`() {
        records.forEach { record ->
            val title = text(record.titleRes)
            val hits = search(title)
            assertTrue(
                "搜「$title」找不到这条记录（分组 ${record.group}）",
                hits.contains(record),
            )
        }
    }

    @Test
    fun `every search result carries a breadcrumb that starts with one of the fourteen categories`() {
        val allowed = SettingsCatalog.groups.map { text(it.titleRes) }.toSet()

        records.forEach { record ->
            val breadcrumb = settingsBreadcrumb(
                groupTitle = groupTitles.getValue(record.group),
                entryTitle = text(record.titleRes),
            )
            val firstSegment = breadcrumb.substringBefore(SETTINGS_BREADCRUMB_SEPARATOR)
            assertTrue("面包屑必须以分类名开头: $breadcrumb", allowed.contains(firstSegment))
            assertTrue(
                "面包屑第二段必须是条目名: $breadcrumb",
                breadcrumb.substringAfter(SETTINGS_BREADCRUMB_SEPARATOR) == text(record.titleRes),
            )
        }
    }

    /** 渲染组 == 索引组：分类页里看到的每一项，索引里的归属必须与清单一致。 */
    @Test
    fun `catalog entries are indexed under their declared group`() {
        SettingsCatalog.entries
            .filter { entry ->
                entry.id != SettingsEntryId.MINIPHONE || io.zer0.muse.feature.FeatureFlags.MINI_PHONE
            }
            .forEach { entry ->
                val declared = SettingsCatalog.groups.single { group ->
                    group.entries.any { it.id == entry.id }
                }
                assertTrue(
                    "条目「${text(entry.titleRes)}」在索引里没有挂在「${text(declared.titleRes)}」下",
                    records.any { it.titleRes == entry.titleRes && it.group == declared.kind },
                )
            }
    }

    /** 关闸时路由不存在，索引里也不能留一个点了没有落地页的按钮。 */
    @Test
    fun `mini phone record exists only while the module is on`() {
        assertEquals(
            "小手机条目是否进搜索索引，必须与编译期总闸一致",
            io.zer0.muse.feature.FeatureFlags.MINI_PHONE,
            records.any { it.entryId == SettingsEntryId.MINIPHONE },
        )
    }

    @Test
    fun `moved entries are indexed in their new categories`() {
        assertEquals(
            "代理应挂在「连接」下",
            SettingsGroupKind.CONNECTION,
            records.single { it.titleRes == R.string.proxy_title }.group,
        )
        assertEquals(
            "分享与安全应挂在「隐私与安全」下",
            SettingsGroupKind.PRIVACY_SECURITY,
            records.single { it.titleRes == R.string.settings_screen_share }.group,
        )
        assertEquals(
            "PII 守护应挂在「隐私与安全」下",
            SettingsGroupKind.PRIVACY_SECURITY,
            records.single { it.titleRes == R.string.settings_screen_pii_guard }.group,
        )
        assertEquals(
            "数据管理应挂在「数据与备份」下",
            SettingsGroupKind.DATA_STORAGE,
            records.single { it.titleRes == R.string.data_management_entry }.group,
        )
        assertEquals(
            "统计应挂在「开发者」下",
            SettingsGroupKind.DEVELOPER,
            records.single { it.titleRes == R.string.settings_screen_stats }.group,
        )
    }

    @Test
    fun `web access entry is indexed in the connection group`() {
        val record = records.single { it.titleRes == R.string.settings_screen_web_access }
        assertEquals("网页版访问必须挂在「连接」分类下", SettingsGroupKind.CONNECTION, record.group)
        assertTrue("网页版访问必须可点进页面", record.action is SettingsEntryAction.Navigate)

        listOf("网页版", "二维码", "局域网").forEach { keyword ->
            assertTrue("关键词「$keyword」缺失，搜索命中不到", record.keywords.contains(keyword))
        }
        assertTrue("拼音关键词缺失", record.keywords.contains("wangyeban"))
    }

    @Test
    fun `searching a category name lists that category's entries`() {
        val chatGroup = SettingsGroupKind.CHAT
        val hits = search(groupTitles.getValue(chatGroup))
        val chatRecords = records.filter { it.group == chatGroup }

        assertTrue("按「${groupTitles.getValue(chatGroup)}」搜应当列出该分类的全部条目", hits.containsAll(chatRecords))
    }

    private fun search(query: String): List<SettingsSearchRecord> = filterSettingsSearch(
        records = records,
        query = query,
        titleOf = { text(it) },
        groupTitleOf = { groupTitles.getValue(it) },
    )

    private fun text(resId: Int): String = context.getString(resId)
}
