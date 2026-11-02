package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 手机上下文的真实来源接线（对比清单 P2-02）。
 *
 * 这里只测"没权限时的表述"——它是最容易写错、也最影响用户下一步的一处：
 * 把"没授权"说成"没有数据"，用户就不会去开权限，只会以为通讯录是空的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhoneContextToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `contacts without permission is reported as unauthorized`() {
        val text = PhoneContextTool.execute(mapOf("sources" to "contacts"), context)

        assertTrue("要说清是未授权: $text", text.contains("未授权"))
        assertTrue("要给出原因: $text", text.contains("通讯录读取权限"))
        assertTrue("不能把它说成没有数据: $text", !text.contains("确实没有"))
    }

    @Test
    fun `app usage without special access is reported as unauthorized`() {
        // Robolectric 的 AppOps 影子默认返回"允许"，所以要显式改成拒绝才能走到这条分支
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
        org.robolectric.Shadows.shadowOf(appOps).setMode(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
            android.app.AppOpsManager.MODE_IGNORED,
        )

        val text = PhoneContextTool.execute(mapOf("sources" to "app_usage"), context)

        assertTrue("要说清是未授权: $text", text.contains("未授权"))
        assertTrue("要指向具体开关: $text", text.contains("使用情况访问"))
    }

    @Test
    fun `an empty notification history says there is genuinely nothing`() {
        val text = PhoneContextTool.execute(mapOf("sources" to "notifications"), context)

        // 历史已接上：没数据时应当说"确实没有"，而不是"还没接入"
        assertTrue("应报可用: $text", text.contains("可用"))
        assertTrue("要说清是真没有: $text", text.contains("确实没有"))
        assertTrue("不该再出现未接入的说法: $text", !text.contains("还没有通知历史"))
    }

    @Test
    fun `an unknown source name falls back to the default instead of crashing`() {
        val text = PhoneContextTool.execute(mapOf("sources" to "bogus"), context)

        // 默认是日历：没有权限时应报未授权（Robolectric 默认不授予日历权限）
        assertTrue("应回落到日历来源: $text", text.contains("日历"))
    }
}
