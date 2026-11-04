package io.zer0.common

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * `muse.log` 的**轮转**契约（这条路径此前完全没有测试）。
 *
 * 为什么要测：轮转是"日志文件会长多大"的唯一防线，而它的失败方式很特殊 ——
 * `File.renameTo()` 失败是**返回 false 不抛异常**。原代码把返回值丢掉，
 * 又把 `writtenBytes` 归零；而 `writerLocked` 随后会把真实文件大小读回来，
 * 于是"每一条日志都再触发一次关句柄 + 删备份 + 改名"，缓冲写退化成逐行落盘。
 *
 * 现在：改名失败 → 按全仓 12 处原子写的同一约定回退"复制 + 截断"；
 * 两条路都不行则进入冷却，不再每行重试。
 *
 * 为什么两个断言写在同一个方法里（而不是拆成两条用例）：
 * `writtenBytes` / `lastRollAttemptAt` 是 `Logger` 的**对象级状态**，
 * 同一个测试类的第二个方法会继承第一个方法留下的计数 —— 拆开写就会时好时坏。
 * 这里按"先到阈值前 → 后到阈值后"的自然顺序一次走完，确定且可重复。
 *
 * 诚实说明覆盖边界：**回退分支没有被测到** —— 那需要一个"改名必然失败"的稳定环境，
 * 本机造不出来（依赖 Windows 文件共享语义与占用时机），不假装已覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LoggerRollingTest {

    private var log: File? = null

    @After
    fun tearDown() {
        Logger.sink = null
        Logger.enabled = true
        runCatching { log?.parentFile?.deleteRecursively() }
        log = null
    }

    @Test
    fun `轮转只在越过阈值时发生且当前文件被控制住大小`() {
        Logger.initFileLog(RuntimeEnvironment.getApplication())
        // 先写一行：`getLogFile()` 只返回已存在的文件，刚 initFileLog 时还没有它
        Logger.w("Rolling", "small line")
        val file = checkNotNull(Logger.getLogFile()) { "写过一行之后应当能拿到日志文件" }
        log = file
        val backup = File(file.parentFile, "${file.name}.1")

        // 阶段一：远未达阈值 —— 不该出现备份，内容要能落盘
        repeat(4) { Logger.w("Rolling", "small line") }
        assertFalse("没到阈值就轮转会切碎日志，排查时看不出顺序", backup.exists())
        assertTrue("内容应当落盘", file.readText().contains("small line"))

        // 阶段二：写过阈值 —— 每条 ~2KB，几百条即可，不拖慢全量
        val chunk = "x".repeat(2048)
        val lines = (Logger.MAX_LOG_FILE_BYTES / chunk.length).toInt() + 30
        repeat(lines) { Logger.w("Rolling", chunk) }

        assertTrue("超过阈值应当留下轮转备份 ${backup.absolutePath}", backup.exists())
        assertTrue("备份里要有内容，不能是空文件", backup.length() > 0L)
        assertTrue(
            "当前日志大小失控：${file.length()}",
            file.length() <= Logger.MAX_LOG_FILE_BYTES * 3,
        )
        // 轮转不能把刚写的日志弄丢：当前文件仍可读且非空
        assertTrue("当前文件不该为空", file.readText().isNotEmpty())
    }
}
