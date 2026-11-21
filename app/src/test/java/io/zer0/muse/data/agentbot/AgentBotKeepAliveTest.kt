package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 保活服务的接线与安全边界。
 *
 * 保活服务是最容易出"崩溃循环"的组件：onCreate 抛异常时系统会反复重启它，
 * 用户看到的就是"一开就闪退"。这里钉住几条必须的防御。
 */
class AgentBotKeepAliveTest {

    private val service: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/AgentBotKeepAliveService.kt").readText()
    }

    private val manifest: String by lazy {
        File("src/main/AndroidManifest.xml").readText()
    }

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
    }

    @Test
    fun `service is declared in the manifest with a foreground type`() {
        assertTrue(
            "未注册的服务 startForegroundService 会直接抛异常",
            manifest.contains("AgentBotKeepAliveService"),
        )
        assertTrue(
            "Android 14+ 要求显式声明前台服务类型",
            manifest.contains("foregroundServiceType=\"dataSync\""),
        )
        assertTrue("不得对外暴露", manifest.contains("android:exported=\"false\""));
    }

    @Test
    fun `onCreate guards against missing koin`() {
        assertTrue(
            "系统 sticky 重启可能发生在 Koin 未启动的进程里，此处不防会崩溃循环",
            service.contains("GlobalContext.getOrNull() == null"),
        )
    }

    @Test
    fun `onCreate never lets an exception escape`() {
        assertTrue(
            "Service onCreate 抛异常会被系统反复重启 —— 用户看到一开就闪退",
            service.contains("catch (t: Throwable)"),
        )
    }

    @Test
    fun `foreground starts in onCreate not in onStartCommand`() {
        val onCreateAt = service.indexOf("override fun onCreate()");
        val startCmdAt = service.indexOf("override fun onStartCommand(");
        val fgAt = service.indexOf("startForegroundCompat(buildNotification(");
        assertTrue(
            "服务创建即进前台，晚于 5 秒会触发 ForegroundServiceDidNotStartInTimeException",
            fgAt in onCreateAt until startCmdAt,
        )
    }

    @Test
    fun `wakelock is released on destroy`() {
        assertTrue(
            "锁不释放会让设备永远不休眠",
            service.contains("wakeLock?.takeIf { it.isHeld }?.release()"),
        )
    }

    @Test
    fun `empty state stops the service after a grace period`() {
        assertTrue(
            "空转的前台服务会被系统判滥用，用户也会看到不消失的通知",
            service.contains("EMPTY_STATE_GRACE_MS"),
        )
        assertTrue(service.contains("stopService()"));
    }

    @Test
    fun `scheduler raises keepalive when it claims work`() {
        val tickAt = scheduler.indexOf("suspend fun tick()");
        assertTrue(tickAt > 0);
        // 窗口要覆盖整个 tick 方法体（恢复窗口守卫让它变长了）。
        val body = scheduler.substring(tickAt, minOf(tickAt + 3000, scheduler.length));
        assertTrue(
            "领到任务要拉前台，否则锁屏后 Doze 会冻住任务",
            body.contains("keepAlive(ctx)"),
        )
    }

    @Test
    fun `keepalive is optional so pure JVM tests still run`() {
        assertTrue(
            "context 为 null 时跳过，单测环境不该被 Service 拖住",
            scheduler.contains("context?.let { ctx ->"),
        )
    }
}