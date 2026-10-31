package io.zer0.muse.tools.reminder

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 提醒重排的顺序契约。
 *
 * 这里测的是**语句先后**，不是行为结果 —— 行为需要真实 Context 与通知通道，
 * 单测里起不来（Robolectric 在本机会撞 native crash）。
 *
 * 为什么值得单独锁住：如果先发通知再落盘，进程在两步之间死亡时，
 * 下次重排会看到那条提醒依旧是「已过期」，于是再补一遍。
 * 用户收到两条一模一样的「(补)」通知，而这条路径极难在真机上复现。
 */
class ReminderCatchUpOrderTest {

    @Test
    fun `过期提醒必须先落盘移除再补发通知`() {
        val source = File("src/main/java/io/zer0/muse/tools/reminder/ReminderStore.kt").readText()
        val start = source.indexOf("private fun rescheduleAllLocked(")
        val end = source.indexOf("private fun catchUpNotify", startIndex = start)
        require(start >= 0 && end > start) { "无法定位 rescheduleAllLocked 方法边界" }
        val body = source.substring(start, end)

        val persistAt = body.indexOf("writeAll(remaining)")
        val notifyAt = body.indexOf("catchUpNotify(it)")

        assertTrue("重排方法里必须落盘一次", persistAt >= 0)
        assertTrue("过期提醒必须在落盘之后才发补发通知", notifyAt >= 0)
        assertTrue(
            "落盘必须先于补发通知：先通知后落盘会在进程死亡时导致重复补发",
            persistAt < notifyAt,
        )
    }
}
