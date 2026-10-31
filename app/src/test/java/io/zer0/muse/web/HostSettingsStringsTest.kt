package io.zer0.muse.web

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 设置文案解析对真实应用资源的校验。
 *
 * `HostSettingsCatalog` 里每个分组 / 条目都只写资源名，文案由
 * [AndroidHostSettingsStrings] 在快照时按当前语言解析。这里拿真资源逐条解析一遍：
 * 任何一条改名、删键或漏翻（退回资源名）都会在这里断出来，而不是在网页上变成一行空白。
 */
@RunWith(RobolectricTestRunner::class)
class HostSettingsStringsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val strings = AndroidHostSettingsStrings(context)

    /** 目录里所有要在界面上显示的文案资源名（分组标题 + 条目标题 + 条目说明）。 */
    private val catalogResourceNames: List<String> = buildList {
        HostSettingsCatalog.groups.forEach { group ->
            add(group.key)
            group.entries.forEach { entry ->
                add(entry.titleKey)
                entry.descKey?.let { add(it) }
            }
        }
    }

    @Test
    fun `every catalog resource name resolves to non blank text`() {
        // 默认资源目录（中文）与英文目录都必须解析得到文案。
        listOf("system", "zh", "en").forEach { language ->
            val missing = catalogResourceNames.filter { strings.resolve(it, language).isNullOrBlank() }
            assertTrue("language=$language 这些设置文案资源解析不到: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `resolved text follows the requested language`() {
        val name = "settings_screen_group_assistants"

        // 显式语言：values/ 是中文默认目录，values-en 是英文目录。
        val zh = strings.resolve(name, "zh")
        val en = strings.resolve(name, "en")

        assertTrue("中文目录必须解析出文案", !zh.isNullOrBlank())
        assertTrue("英文目录必须解析出文案", !en.isNullOrBlank())
        assertNotEquals("切到英文后文案应跟着变，否则等于没按语言解析", zh, en)
    }

    @Test
    fun `unknown resource names resolve to null instead of a placeholder`() {
        assertEquals(null, strings.resolve("settings_screen_definitely_not_a_resource", "zh"))
        assertEquals(null, strings.resolve("  ", "zh"))
    }
}
