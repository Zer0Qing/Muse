package io.zer0.muse.ui.settings

import io.zer0.muse.data.proactive.ProactivePace
import io.zer0.muse.data.proactive.ProactiveTimingEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「主动程度」档位的接线。
 *
 * 档位以前写出的 `aiPaced` 字段全仓无人读取，等于一个只在界面上活着的开关；
 * 现在档位只写「间隔 + 概率」两个真正被判定层读的字段。
 * 这里做源码级断言而不是渲染测试：要盯的是「界面写的是不是调度会读的」，
 * 渲染出来长什么样并不影响这个结论。
 */
class ProactivePaceWiringTest {

    @Test
    fun `已废弃的 aiPaced 不再出现在设置页`() {
        assertFalse(
            "设置页不该再读写 aiPaced（该字段已废弃且无实现）",
            source().contains("aiPaced"),
        )
    }

    @Test
    fun `选档写出的间隔与概率是判定层真正读的字段`() {
        val text = source()
        assertTrue(text.contains("intervalMinutes = pace.intervalMinutes"))
        assertTrue(text.contains("sendProbability = pace.sendProbability"))
    }

    @Test
    fun `四个档位的间隔都落在判定允许的区间内`() {
        ProactivePace.values().forEach { pace ->
            assertTrue(
                "${pace.name}=${pace.intervalMinutes} 分钟超出判定区间",
                pace.intervalMinutes in
                    ProactiveTimingEngine.MIN_INTERVAL_MINUTES..ProactiveTimingEngine.MAX_INTERVAL_MINUTES,
            )
            assertTrue("${pace.name} 的概率应在 0..100", pace.sendProbability in 0..100)
        }
    }

    private fun source(): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/ui/settings/AgentSettingsPage.kt"),
            File("app/src/main/java/io/zer0/muse/ui/settings/AgentSettingsPage.kt"),
        )
        val file = candidates.firstOrNull { it.exists() } ?: error("找不到设置页源码")
        return file.readText()
    }
}
