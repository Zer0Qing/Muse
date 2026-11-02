package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 插件总闸（安全模式里的「关闭所有插件」）。
 *
 * 这一层刻意做成**文件标志**而不是设置项：安全模式本身就是崩溃后的降级界面，
 * 那时 Koin / DataStore 都可能不可用。所以测试重点在"不依赖任何东西也能读能写"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginKillSwitchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clean() {
        PluginKillSwitch.setDisabled(context, false)
    }

    @Test
    fun `plugins run by default`() {
        assertFalse("默认不该关着", PluginKillSwitch.isDisabled(context))
    }

    @Test
    fun `disabling writes the flag and restoring removes it`() {
        assertTrue(PluginKillSwitch.setDisabled(context, true))
        assertTrue(PluginKillSwitch.isDisabled(context))
        assertTrue("标志文件应存在", PluginKillSwitch.flagFile(context).isFile)

        assertFalse(PluginKillSwitch.setDisabled(context, false))
        assertFalse(PluginKillSwitch.isDisabled(context))
        assertFalse("标志文件应被删掉", PluginKillSwitch.flagFile(context).exists())
    }

    @Test
    fun `the sibling check finds the flag without a context`() {
        // 权限账本只持有自己的 storeFile，拿不到 Context —— 它靠同目录判定
        val storeFile = File(context.filesDir, "plugin_permissions.json")
        assertFalse(PluginKillSwitch.isDisabledBeside(storeFile))

        PluginKillSwitch.setDisabled(context, true)

        assertTrue("同目录判定要能看到标志", PluginKillSwitch.isDisabledBeside(storeFile))
    }

    @Test
    fun `the disabled message tells the user where to restore`() {
        assertTrue(PluginKillSwitch.DISABLED_MESSAGE.contains("安全模式"))
        assertTrue("要指出恢复入口", PluginKillSwitch.DISABLED_MESSAGE.contains("插件管理页"))
    }

    @Test
    fun `the capability gateway refuses everything while the switch is on`() {
        val storeFile = File(context.filesDir, "plugin_permissions.json")
        val gateway = PluginCapabilityGateway(storeFile)
        // 先证明正常情况下 echo 是放行的
        assertTrue(
            "echo 不需要任何能力",
            gateway.check("p1", emptyList(), "echo") is PluginCapabilityGateway.Decision.Allow,
        )

        PluginKillSwitch.setDisabled(context, true)

        val denied = gateway.check("p1", emptyList(), "echo")
        assertTrue("总闸关掉后连 echo 都不放行：$denied", denied is PluginCapabilityGateway.Decision.Deny)
        assertTrue(
            "拒绝理由要指向安全模式",
            (denied as PluginCapabilityGateway.Decision.Deny).reason.contains("安全模式"),
        )
    }
}
