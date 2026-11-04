package io.zer0.muse.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 无障碍服务配置的守卫（`:accessibility` 待查清单最后一项查出来的东西）。
 *
 * 起因：主应用与 `:accessibility-provider` 各有一份服务配置，两边已经漂了
 * （主应用那份多了 `flagReportViewIds` 与 `typeViewTextChanged`）。
 * 现在合成一份（`res/xml/accessibility_service_config.xml`，由 `:accessibility` 提供），
 * 这个测试就是防止它再被复制出第二份、或者把关键位偷回去。
 *
 * 为什么值得钉：这类漂移的表现是「provider 授权了、按 resource-id 却点不动」，
 * 而配置错误在代码里**完全看不出来** —— 只能钉文件。
 */
class AccessibilityServiceConfigTest {

    private val relative = "src/main/res/xml/accessibility_service_config.xml"

    private fun configText(): String {
        val candidates = listOf(
            File(relative), // 工作目录 = :accessibility 模块根
            File("accessibility/$relative"), // 工作目录 = 仓库根
            File("../accessibility/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到无障碍服务配置，工作目录=${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun `读控件与手势截图三个能力位都还在`() {
        val text = configText()

        assertTrue("读 UI 树的能力位丢了", text.contains("""android:canRetrieveWindowContent="true""""))
        assertTrue("手势注入能力位丢了", text.contains("""android:canPerformGestures="true""""))
        assertTrue("截图能力位丢了（API 34 起 provider 就截不了图）", text.contains("""android:canTakeScreenshot="true""""))
    }

    @Test
    fun `flagReportViewIds 不许被拿掉`() {
        // 没有它，`AccessibilityNodeInfo.viewIdResourceName` 恒为 null：
        // 按 resource-id 定位控件的那条路（findBestViewIdNode、screen_tap_text 的 id 匹配）
        // 会静默失效 —— 不报错，只是"永远找不到"。
        assertTrue(configText().contains("flagReportViewIds"))
    }

    @Test
    fun `事件类型不能写成空过滤器`() {
        val text = configText()

        // 空 packageNames 会被解析成「空包名过滤器」：零事件送达，
        // getCurrentActivityName 恒空、rootInActiveWindow 为 null。
        assertFalse("声明了 packageNames 就必须给出真实包名列表：$text", text.contains("android:packageNames"))
        assertTrue("窗口状态事件是前台应用名的唯一来源", text.contains("typeWindowStateChanged"))
    }

    @Test
    fun `主应用不再留第二份配置`() {
        val strays = listOf(
            File("../app/src/main/res/xml/muse_accessibility_service.xml"),
            File("app/src/main/res/xml/muse_accessibility_service.xml"),
            File("src/main/res/xml/muse_accessibility_service.xml"),
        )

        assertTrue(
            "同一服务配置写两处已经漂过一次（缺 flagReportViewIds / typeViewTextChanged）；" +
                "要改能力请只改 :accessibility 的那一份：${strays.filter { it.exists() }.map { it.path }}",
            strays.none { it.exists() },
        )
    }
}
