package io.zer0.muse.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 小手机"入口隐藏"契约（全面细化 · 小手机第一批）。
 *
 * 为什么要有这条测试：入口该藏、路由该留，这个设计此前**只写在注释里，而且两处注释互相矛盾**
 * （FeatureFlags 顶部说"不能只藏 UI 而留着路由"，`MINI_PHONE_ENTRY` 自己说"链接仍能进入"）。
 * 靠注释传达的契约一定会走偏，所以把它钉成断言：
 *
 *  - 入口（首页、设置）由 `MINI_PHONE_ENTRY` / `browseVisible` 管；
 *  - 路由由 `MINI_PHONE` 管，**不能**改用 `MINI_PHONE_ENTRY` ——
 *    那样搜索命中与恢复的导航栈会指向一个不存在的页面，比"能进但没入口"更难解释；
 *  - `MOMENTS` 恒等于 `MINI_PHONE`（朋友圈是小手机的内嵌能力，不该单独成为第二个可漂移的开关）。
 */
class MiniPhoneVisibilityContractTest {

    @Test
    fun momentsAlwaysFollowsTheModuleFlag() {
        assertEquals("朋友圈随小手机存在，不是第二个独立开关", FeatureFlags.MINI_PHONE, FeatureFlags.MOMENTS)
    }

    @Test
    fun homeEntryIsGovernedByTheEntryFlag() {
        val source = readSource("ui/HomeScreen.kt")

        assertTrue("首页入口要过 MINI_PHONE_ENTRY", source.contains("FeatureFlags.MINI_PHONE_ENTRY"))
    }

    @Test
    fun settingsEntryNeedsBrowseVisibleAndTheModuleFlag() {
        val source = readSource("ui/settings/SettingsGroupScreen.kt")

        assertTrue(
            "设置条目要同时看 browseVisible 与模块旗",
            source.contains("browseVisible &&") && source.contains("FeatureFlags.MINI_PHONE"),
        )
    }

    @Test
    fun routesStayBehindTheModuleFlagNotTheEntryFlag() {
        val source = readSource("ui/navigation/ChatNavGraph.kt")

        assertTrue("路由注册要过 MINI_PHONE", source.contains("FeatureFlags.MINI_PHONE)"))
        assertFalse(
            "路由不能过 MINI_PHONE_ENTRY：入口藏了但模块在包里时，搜索与 deep link 必须仍能落到真实页面",
            source.contains("FeatureFlags.MINI_PHONE_ENTRY"),
        )
    }

    /** 测试读的是源码而不是编译产物：接线位置本身就是被测对象。 */
    private fun readSource(relative: String): String {
        val candidates = listOf(
            Path.of("src/main/java/io/zer0/muse/$relative"),
            Path.of("app/src/main/java/io/zer0/muse/$relative"),
        )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
