package io.zer0.muse.automation.executors

import io.zer0.common.Logger
import io.zer0.muse.tools.system.ShizukuAuthorizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * device_shell 通道级失败（exit=-1）的反馈文案测试。
 *
 * -1 有两种截然不同的来源：通道没起来（命令没跑）与命令跑了但结果没回来。
 * 只报一个数字，模型的默认动作是原样重发 —— 带副作用的命令就被做两遍。
 * 这里锁住三件事：文案要说清含义、要给出下一步、且只在真的有 Shizuku 通道时才出现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShellExecutorChannelFailureTest {

    @Before
    fun setUp() {
        Logger.enabled = false
    }

    private val context get() = org.robolectric.RuntimeEnvironment.application

    @Test
    fun `Shizuku 模式下的失败反馈带执行路径、含义与下一步`() {
        val executor = ShellExecutor(context, ShizukuAuthorizer(context))
        val text = requireNotNull(executor.describeChannelFailure())
        assertTrue("要报出走到的路径: $text", text.contains(ShizukuAuthorizer.PATH_NONE))
        assertTrue("要说清 -1 不是命令退出码: $text", text.contains("通道级失败"))
        assertTrue("要拦住原样重发: $text", text.contains("不要原样重发"))
        assertTrue("要给出可执行的下一步: $text", text.contains("echo ok"))
    }

    @Test
    fun `执行路径取自 authorizer，不是写死在文案里`() {
        val authorizer = ShizukuAuthorizer(context)
        // 单测里跑不了真实 Shizuku，直接写字段模拟「上一条命令走了快路径」。
        val field = ShizukuAuthorizer::class.java.getDeclaredField("lastExecutionPath")
        field.isAccessible = true
        field.set(authorizer, ShizukuAuthorizer.PATH_REMOTE)

        val text = ShellExecutor(context, authorizer).describeChannelFailure()

        assertTrue("要如实报出快路径: $text", text?.contains(ShizukuAuthorizer.PATH_REMOTE) == true)
    }

    @Test
    fun `本地 su 模式不编造 Shizuku 解释`() {
        // RootExecutor 继承 ShellExecutor 但没有 Shizuku 通道；它的 -1 只有超时一种来源，
        // 输出文本里已写明，再套一段 Shizuku 说明就是编造。
        assertNull(RootExecutor(context).describeChannelFailure())
        assertNull(ShellExecutor(context).describeChannelFailure())
    }

    @Test
    fun `PATH 常量彼此不同，避免文案里出现无法区分的标识`() {
        val paths = setOf(
            ShizukuAuthorizer.PATH_REMOTE,
            ShizukuAuthorizer.PATH_USER_SERVICE,
            ShizukuAuthorizer.PATH_NONE,
        )
        assertEquals(3, paths.size)
    }
}
