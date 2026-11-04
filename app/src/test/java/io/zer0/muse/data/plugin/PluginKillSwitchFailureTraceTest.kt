package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.Logger
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 插件总闸**写失败**时的两面契约（与 `PluginKillSwitchTest` 的正常路径互补）。
 *
 * `setDisabled` 的 KDoc 立了规矩："返回写入后的真实状态，而不是乐观返回目标值"。
 * 但光有这句不够 —— 用户在安全模式里按「关掉所有插件」，如果标志文件没落下去，
 * 界面只看到开关弹回去，**没人知道为什么**；这是全仓最不该无声的一种失败
 * （用户以为插件都停了，实际它们还在跑）。
 *
 * 造失败的方式刻意选了"任何平台都必然失败"的一种：
 * 让 `filesDir` 落在一个**普通文件底下**（父路径不是目录），
 * 于是 `mkdirs()` 失败、`writeText` 必抛。
 * 没用 `setWritable(false)`：Windows 上它对所有者未必拦得住写，测试会假绿。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginKillSwitchFailureTraceTest {

    private val seen = mutableListOf<Pair<String, String>>()

    @After
    fun tearDown() {
        Logger.sink = null
        seen.clear()
    }

    /**
     * 只改 `filesDir` 的上下文。
     *
     * 必须走 `ContextWrapper`：`Context` 是带构造器的抽象类，Kotlin 的 `by` 委托不支持
     * （`Context by base` 会直接编译失败）。
     */
    private class FilesDirContext(
        base: Context,
        private val override: File,
    ) : android.content.ContextWrapper(base) {
        override fun getFilesDir(): File = override

        /**
         * 必须一起覆写：`PluginKillSwitch.flagFile()` 读的是 `context.applicationContext.filesDir`，
         * 直接返回父类的话会拿回真实的应用目录，我改的 `filesDir` 就被绕过了。
         */
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `写不进标志文件时既不谎报关上也要留下原因日志`() {
        seen.clear()
        Logger.sink = { _, tag, msg, _ -> seen += tag to msg }

        val base = ApplicationProvider.getApplicationContext<Context>()
        // 一个真实存在的普通文件，被当成目录用 —— 写它下面的路径必然失败
        val notADirectory = File(base.filesDir, "killswitch-blocker-${System.nanoTime()}")
        notADirectory.writeText("我是文件不是目录")
        assertTrue("前置条件：这个占位文件必须真的存在", notADirectory.isFile)

        val context = FilesDirContext(base, File(notADirectory, "sub-dir"))

        val nowDisabled = PluginKillSwitch.setDisabled(context, disabled = true)

        assertFalse("写失败时不能报「已经关上」（返回值就是用户的真相）", nowDisabled)
        assertFalse("读侧要一致：真的没关上", PluginKillSwitch.isDisabled(context))

        val log = seen.filter { it.first == "PluginKillSwitch" }.singleOrNull()
        assertNotNull("总闸没落下去却没留任何痕迹，正是这次要修的静默：$seen", log)
        assertTrue("要说明是「想关却没关上」：${log!!.second}", log.second.contains("没关上"))
        assertTrue("要带路径，才知道是哪个位置写不了：${log.second}", log.second.contains("killswitch-blocker-"))

        notADirectory.delete()
    }
}
