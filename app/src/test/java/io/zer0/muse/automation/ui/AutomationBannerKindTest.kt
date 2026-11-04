package io.zer0.muse.automation.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 页面顶部拦截说明的**互斥判定**（纯函数）。
 *
 * 这两条横幅以前由两句手写条件维持互斥：
 *  - `if (!automationEnabled)` → 「自动化总闸是关的，所以工具都在拒绝执行」
 *  - `if (automationEnabled && !accessibilityEnabled)` → 「无障碍没开，去哪儿开回来」
 *
 * 只要有人改坏其中一句（比如把第二句的 `automationEnabled &&` 删了），
 * 用户就会同时读到两句互相矛盾的话：一条说"总闸关着"，一条让他"去开无障碍"。
 * 抽成函数后这条规则是可测的，而不是靠读条件表达式确认。
 */
class AutomationBannerKindTest {

    @Test
    fun `总闸关着时只报总闸，不再催着去开无障碍`() {
        assertEquals(
            "总闸没开时让用户去开无障碍是没意义的，两句同现更是互相矛盾",
            AutomationBannerKind.MASTER_OFF,
            automationBannerKind(automationEnabled = false, accessibilityEnabled = false),
        )
    }

    @Test
    fun `总闸关着且无障碍已连上也只报总闸`() {
        assertEquals(
            AutomationBannerKind.MASTER_OFF,
            automationBannerKind(automationEnabled = false, accessibilityEnabled = true),
        )
    }

    @Test
    fun `总闸开着但服务断了才提示去开回来`() {
        assertEquals(
            AutomationBannerKind.A11Y_OFF,
            automationBannerKind(automationEnabled = true, accessibilityEnabled = false),
        )
    }

    @Test
    fun `两者都正常时不显示任何拦截说明`() {
        assertEquals(
            AutomationBannerKind.NONE,
            automationBannerKind(automationEnabled = true, accessibilityEnabled = true),
        )
    }
}
