package io.zer0.muse.schedule

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `ChatGenerationService` 的 Koin 崩溃守卫（源码契约）。
 *
 * 为什么不用运行时用例：`stopKoin()` 是**全局状态**，会在同一个测试 JVM 里污染后续用例
 * （实测会让无关的 `DeleteMemoryToolTest` 报「uncaught exceptions before test」）。
 * 服务生命周期 + 全局 DI 的组合不适合在共享 JVM 里模拟，这里改为钉住源码契约。
 *
 * 要保证的三件事：
 *  1. `onCreate` 在碰任何 `by inject()` 之前先判 Koin 是否启动；
 *  2. 判空后必须 `stopSelf()` 优雅退场，而不是继续初始化；
 *  3. 正常路径（进前台 + WakeLock）整体包在 try/catch 里 —— 任何 Throwable 都只放弃保活。
 */
class ChatGenerationServiceKoinGuardTest {

    private val source: String by lazy {
        listOf(
            Path.of("src/main/java/io/zer0/muse/schedule/ChatGenerationService.kt"),
            Path.of("app/src/main/java/io/zer0/muse/schedule/ChatGenerationService.kt"),
        ).firstOrNull(Files::exists)
            ?.let { Files.newBufferedReader(it).use { r -> r.readText() } }
            ?: error("ChatGenerationService.kt not found")
    }

    @Test
    fun `onCreate checks koin before touching injected dependencies`() {
        val onCreate = source.substringAfter("override fun onCreate()").substringBefore("override fun onStartCommand")
        val koinCheckAt = onCreate.indexOf("GlobalContext.getOrNull()")
        assertTrue("onCreate 必须先判断 Koin 是否启动", koinCheckAt >= 0)
        assertTrue("判空后要 stopSelf 优雅退场", onCreate.contains("stopSelf()"))
        assertTrue(
            "正常路径必须整体包在 try/catch 里，避免任何初始化异常导致 Service 创建失败",
            onCreate.contains("initForegroundAndWakeLock()") && onCreate.contains("catch (t: Throwable)"),
        )
    }

    @Test
    fun `onStartCommand also guards koin`() {
        val onStart = source.substringAfter("override fun onStartCommand").substringBefore("private fun startObserve")
        assertTrue("onStartCommand 也要判 Koin", onStart.contains("GlobalContext.getOrNull()"))
        assertTrue("判空后应 stopSelf", onStart.contains("stopSelf()"))
    }

    /**
     * onDestroy 是最容易漏的一处：它没有返回值、不能失败，但会在崩溃后的 Safe Mode 进程里被调用。
     * 那里 Koin 没启动，直接用 `by inject` 的字段会把“停止服务”变成新的崩溃。
     */
    @Test
    fun `onDestroy never touches injected fields directly`() {
        val onDestroy = source.substringAfter("override fun onDestroy()").substringBefore("companion object")
        assertTrue(
            "onDestroy 不能直接访问 by inject 字段（Koin 未启动时会抛 Unable to stop service）",
            !onDestroy.contains("notificationManager.clearLiveProgress()"),
        )
        assertTrue("onDestroy 应走可空取用", onDestroy.contains("notificationManagerOrNull()"))
        assertTrue("清理失败不能从 onDestroy 抛出去", onDestroy.contains("runCatching"))
    }
}
