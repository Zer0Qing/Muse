package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 插件总闸必须也拦住**斜杠命令**与**面板提交**这两条执行入口。
 *
 * 原来只有工具路径（`execPluginTool`）查这把闸；`invokePluginCommand` 与
 * `invokeUiSubmitHandler` 都走 `prepareDeclaredCall`，那里只看插件自己的 `enabled`
 * 与清单校验。于是安全模式里关掉总闸之后，界面写着「所有插件已在安全模式中停用」，
 * 插件代码却照样能被一条 `/命令` 或一次面板提交跑起来 —— **承诺与实际相反**。
 *
 * 每个用例都配一个反向断言，否则测试会退化成"返回了点什么就算过"：
 *  - 关闸时必须是总闸文案，且**不能**是「插件未安装」（那说明判定顺序没生效）；
 *  - 开闸时必须继续往下判（回到「插件未安装」），证明新闸不是无条件拦截。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginKillSwitchCoversCommandsTest {

    private fun tools(globallyEnabled: Boolean): SkillMediaToolsImpl = SkillMediaToolsImpl(
        context = ApplicationProvider.getApplicationContext<Context>(),
        stickerLibraryRepository = null,
        imageService = null,
        pluginManager = null,
        pluginsGloballyEnabled = { globallyEnabled },
    )

    @Test
    fun `关闸时斜杠命令不执行插件代码`() = runTest {
        val out = tools(globallyEnabled = false).invokePluginCommand("any.plugin", "handler", "input")

        assertTrue("应说明是总闸拦下的：$out", out.contains("总闸"))
        assertFalse("不该先报「插件未安装」，那说明闸没在最前面：$out", out.contains("插件未安装"))
    }

    @Test
    fun `关闸时面板提交也不执行插件代码`() = runTest {
        val out = tools(globallyEnabled = false)
            .invokeUiSubmitHandler("any.plugin", "handler", "{}")

        val reason = (out as? SkillMediaToolsImpl.UiSubmitResult.Rejected)?.reason
        assertTrue("面板提交必须被拒绝并给出可读原因，实际=$out", reason != null)
        assertTrue("原因要指明是总闸：$reason", reason!!.contains("总闸"))
        assertFalse("不该先报「插件未安装」：$reason", reason.contains("插件未安装"))
    }

    @Test
    fun `开闸时仍然走原有的插件查找判定（新闸不是无条件拦截）`() = runTest {
        val out = tools(globallyEnabled = true).invokePluginCommand("any.plugin", "handler", "input")

        assertTrue("闸开着就该继续往下判：$out", out.contains("插件未安装"))
        assertFalse(out.contains("总闸"))
    }
}
