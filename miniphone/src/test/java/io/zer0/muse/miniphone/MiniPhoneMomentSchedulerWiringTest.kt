package io.zer0.muse.miniphone

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 接线契约:宿主启动时必须注册小手机的后台自动发朋友圈。
 *
 * 为什么用源码断言而不是真跑一遍 Application:WorkManager 的周期任务只在真机上排期,
 * 单元测试里既不会真的排上,也验不出「忘了调」—— 而这正是要防的错:
 * 模块里有一套完整调度,宿主却没接,功能等于不存在。
 *
 * 读源码用 `Files.newBufferedReader`(仓库既有 wiring 测试的写法),不用 `Files.readString`。
 * 三个候选路径是因为测试工作目录可能是模块目录,也可能是仓库根。
 */
class MiniPhoneMomentSchedulerWiringTest {

    @Test
    fun `muse app registers miniphone moment scheduler in onCreate`() {
        val source = MUSE_APP_CANDIDATES
            .firstOrNull(Files::exists)
            ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
            ?: error("MuseApp.kt not found")

        assertTrue(
            "MuseApp 必须 import 模块里的调度器",
            source.contains("import io.zer0.muse.miniphone.work.MiniPhoneMomentScheduler"),
        )

        val onCreateAt = source.indexOf("override fun onCreate()")
        val callAt = source.indexOf("MiniPhoneMomentScheduler.start(this)")
        assertTrue("MuseApp.kt 里找不到 override fun onCreate()", onCreateAt >= 0)
        assertTrue("MuseApp.onCreate 没有注册小手机后台任务", callAt > onCreateAt)

        // 注册只在启动时发生一次;stop 不在这里调(开关关掉只让 Worker 空轮跳过)
        assertTrue(
            "启动路径不该顺手取消任务",
            !source.substringAfter("override fun onCreate()").contains("MiniPhoneMomentScheduler.stop"),
        )
    }

    private companion object {
        val MUSE_APP_CANDIDATES: List<Path> = listOf(
            Path.of("../app/src/main/java/io/zer0/muse/MuseApp.kt"),
            Path.of("app/src/main/java/io/zer0/muse/MuseApp.kt"),
            Path.of("src/main/java/io/zer0/muse/MuseApp.kt"),
        )
    }
}
