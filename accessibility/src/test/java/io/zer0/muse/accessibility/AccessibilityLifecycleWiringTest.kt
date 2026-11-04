package io.zer0.muse.accessibility

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 无障碍服务生命周期接线契约（`:accessibility` §2/§3 里我原本只能说"代码读查"的那部分）。
 *
 * 为什么用源码断言而不是跑一遍服务：`onUnbind` / `onDestroy` 由系统触发，
 * 单测里造不出真实的解绑时序；而**真正要防的错不是"系统行为不对"，是"有人把其中一边删掉"** ——
 * 类注释已经写明：只实现 `onDestroy` 不够，系统解绑时可能只调 `onUnbind` 而进程仍存活，
 * 那时 `connected` 还是 true、`instance` 还指着旧实例，宿主会以为"无障碍可用"，
 * 于是照发手势、读树，然后失败（真机上表现为「服务已连接 → Bound services 变空」的漂移）。
 *
 * 断言只认**去空白后的代码**，不认缩进/换行风格 —— 否则格式化工具一跑就假红。
 */
class AccessibilityLifecycleWiringTest {

    private fun source(): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/accessibility/MuseAccessibilityService.kt"),
            File("accessibility/src/main/java/io/zer0/muse/accessibility/MuseAccessibilityService.kt"),
            File("../accessibility/src/main/java/io/zer0/muse/accessibility/MuseAccessibilityService.kt"),
        )
        // 用 requireNotNull 而不是 `?: fail(...)`：junit 的 fail 返回 void，
        // 放进 elvis 会让整式退化成 Any，后面 .readText() 就找不到了。
        val file = requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到 MuseAccessibilityService.kt，工作目录=${File(".").absolutePath}"
        }
        return file.readText()
    }

    private fun body(fnName: String): String {
        val text = source()
        val start = text.indexOf("override fun $fnName(")
        assertTrue("少了 `override fun $fnName`：生命周期被删了一半", start >= 0)
        val rest = text.substring(start)
        // 取到下一个 override/私有函数定义之前，避免把相邻函数的实现算进来
        val ends = listOf("\n    override fun ", "\n    private fun ", "\n    fun ")
        return rest.substringBeforeAny(ends)
    }

    private fun String.substringBeforeAny(needles: List<String>): String {
        var end = length
        for (needle in needles) {
            val idx = indexOf(needle, startIndex = 1)
            if (idx > 0 && idx < end) end = idx
        }
        return substring(0, end)
    }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ")

    @Test
    fun `onUnbind 与 onDestroy 都清状态并广播`() {
        for (fn in listOf("onUnbind", "onDestroy")) {
            val code = normalized(body(fn))
            assertTrue("$fn 必须把 connected 置回 false（否则宿主以为仍可用）", code.contains("connected = false"))
            assertTrue("$fn 必须清掉 instance（否则调用方会拿到已解绑的旧实例）", code.contains("instance = null"))
            assertTrue("$fn 必须广播连接变化（宿主靠它刷新权限快照）", code.contains("notifyConnectionChanged(false)"))
        }
    }

    @Test
    fun `onServiceConnected 注册实例并广播可用`() {
        val code = normalized(body("onServiceConnected"))

        assertTrue("连接时要把自己登记成 instance", code.contains("instance = this"))
        assertTrue("连接时要置 connected = true", code.contains("connected = true"))
        assertTrue("连接时要广播 true（权限开关才能立刻变绿）", code.contains("notifyConnectionChanged(true)"))
    }

    @Test
    fun `取消不会被当成失败广播出去`() {
        // 生命周期回调里不该出现 `notifyConnectionChanged(true)` 之后的 catch-all；
        // 这条防的是以后有人给 onUnbind 包一层 try/catch 把解绑"咽下去"。
        val code = body("onUnbind")

        assertTrue("onUnbind 不该吞异常：$code", !code.contains("catch"))
    }
}
