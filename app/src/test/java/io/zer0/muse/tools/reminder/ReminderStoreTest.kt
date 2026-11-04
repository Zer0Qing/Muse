package io.zer0.muse.tools.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 提醒存储的基本读写与**损坏文件处理**。
 *
 * 顺序契约（先落盘移除、再补发通知）已由 `ReminderCatchUpOrderTest` 守着，这里不重复；
 * 这个类此前只有那 1 例，而它是一份 JSON 文件当唯一真源的存储 ——
 * "读不出来时怎么办"恰恰是最需要钉的一段（与 `:memory` 的矛盾清单同一形状：
 * 静默把坏文件当空表覆盖，用户的数据就没了）。
 *
 * 只碰文件，不碰通知通道（那条路径在单测环境会撞 native crash，见
 * `ReminderCatchUpOrderTest` 的说明）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderStoreTest {

    private val remindersFile: File
        get() = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "reminders/reminders.json")

    private fun wipe() {
        val dir = remindersFile.parentFile
        dir?.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `增删查与排期状态字段如实记录`() {
        wipe()
        val store = ReminderStore(ApplicationProvider.getApplicationContext())
        val at = System.currentTimeMillis() + 3_600_000L

        val id = store.add(title = "喝水", message = "该补水了", triggerAtMillis = at)

        assertEquals("喝水", store.get(id)?.title)
        assertEquals(at, store.get(id)?.triggerAtMillis)
        assertTrue("写盘文件必须真的落在提醒目录下", remindersFile.exists())
        // 尚未排期：alarmLive 必须为 false，否则 `list_reminders` 会谎报"已排上"
        assertFalse("新提醒还没排上闹钟，不能报成有效", store.get(id)!!.alarmLive)

        store.markScheduled(id, exact = true, atMillis = at)
        assertEquals(at, store.get(id)?.alarmScheduledAt)
        assertEquals(true, store.get(id)?.scheduledExact)
        assertTrue("排上之后 alarmLive 要为真", store.get(id)!!.alarmLive)

        store.markScheduleFailed(id, "精确闹钟未授权")
        assertEquals("精确闹钟未授权", store.get(id)?.scheduleError)
        assertFalse("记下失败后 alarmLive 要如实为 false（用户该看到「可能不会响」）", store.get(id)!!.alarmLive)

        assertTrue(store.remove(id))
        assertNull(store.get(id))
        assertFalse("重复删除要如实返回 false，不假装成功", store.remove(id))
    }

    @Test
    fun `损坏的提醒文件被备份留证而不是当空表覆盖`() {
        wipe()
        remindersFile.parentFile?.mkdirs()
        remindersFile.writeText("{ 这不是合法 JSON ")

        val store = ReminderStore(ApplicationProvider.getApplicationContext())

        // 读不出内容时按空表处理（不能崩），但必须留下可找回的证据
        assertEquals(0, store.list().size)
        val notice = store.consumeError()
        assertNotNull("损坏要给出一次可展示的提示，实际为 null", notice)
        assertTrue("提示要说明发生了什么：$notice", notice!!.contains("损坏"))
        assertNull("一次性消费：第二次取必须为空，否则横幅反复弹", store.consumeError())

        val dir = remindersFile.parentFile!!
        // 断言的是"留了证据文件"这件事，不锁具体命名（这里用的是 `.bak-<时间戳>`，
        // 而 `:memory` 的矛盾清单用 `.corrupt-<时间戳>` —— 锁名字会让守卫脆得像测试自己写的格式约定）
        val evidence = dir.listFiles { f -> f.name != remindersFile.name }
        assertTrue(
            "原文件必须被改名留证（直接覆盖就等于把用户的提醒抹了）：${dir.list().joinToString()}",
            evidence != null && evidence.isNotEmpty(),
        )
        wipe()
    }
}
