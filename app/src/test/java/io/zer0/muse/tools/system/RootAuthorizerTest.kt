package io.zer0.muse.tools.system

import io.zer0.common.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 权限体系重设计: RootAuthorizer libsu 门面的被动 API 冒烟测试。
 *
 * 锁定关键不变量：被动探测（[RootAuthorizer.isAvailable]/[RootAuthorizer.grantState]/
 * [RootAuthorizer.checkPermission]）在 libsu 静态初始化后**绝不抛异常、绝不创建 shell**。
 * 真实 root 弹窗无法在测试环境复现，授权请求语义由 RootRequestLogicTest /
 * AutomationManagerRootRequestTest 覆盖；这里只验证 libsu 集成不会在冷启动 passive 路径炸掉。
 *
 * 不断言具体授权值：libsu isAppGrantedRoot 依赖进程 uid，Robolectric 环境下取值不稳定；
 * 但管理器包检测在测试环境必然为空（未安装任何 root 管理器），可确定性断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RootAuthorizerTest {

    private var previousLoggerEnabled = true

    @Before
    fun setUp() {
        // 全局开关禁用后必须还原，避免污染同 JVM 后续测试的日志捕获。
        previousLoggerEnabled = Logger.enabled
        Logger.enabled = false
    }

    @After
    fun tearDown() {
        Logger.enabled = previousLoggerEnabled
    }

    private val context get() = RuntimeEnvironment.application

    @Test
    fun `passive probes never throw after libsu init`() {
        val authorizer = RootAuthorizer(context)
        // 这些调用都不得抛异常（libsu builder 配置 + 静态读取必须安全）。
        assertNotNull(authorizer.grantState())
        authorizer.isAvailable()
        authorizer.checkPermission()
    }

    @Test
    fun `no root manager installed in test environment`() {
        val authorizer = RootAuthorizer(context)
        assertEquals(null, authorizer.installedManager())
        assertFalse(authorizer.isManagerInstalled())
    }

    @Test
    fun `grant state is a valid enum value`() {
        val authorizer = RootAuthorizer(context)
        val state = authorizer.grantState()
        assertNotNull(RootAuthorizer.GrantState.valueOf(state.name))
    }
}
