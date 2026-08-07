package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.x: 全工具烟雾测试 — 遍历 ToolRegistry 已注册的全部内置工具,
 * 用空参数(或最小参数)逐个执行,断言:
 *  - 执行不抛异常(注册/参数解析/内部链路无崩溃)
 *  - 返回非空字符串(工具结果以 JSON 或错误文本返回,不能是空串)
 *
 * 说明:
 *  - 不校验执行"成功"(多数工具依赖真实系统能力,Robolectric 环境会返回错误),
 *    只校验"链路无崩溃 + 返回合法" —— 这是每个工具的最低健康线。
 *  - 覆盖注册完整性: 工具定义存在 + 执行入口可达。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistrySmokeTest {

    private lateinit var registry: ToolRegistry

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        registry = ToolRegistry(context)
    }

    @Test
    fun `all built-in tools execute without crash and return non-empty result`() = runBlocking {
        val toolNames = io.zer0.muse.tools.ToolRegistry.BUILT_IN_TOOL_IDS
        assertTrue("应注册至少 20 个内置工具,实际 ${toolNames.size}", toolNames.size >= 20)

        val failures = mutableListOf<String>()
        val executed = mutableListOf<String>()

        for (name in toolNames.sorted()) {
            val result = try {
                registry.executeFromJson(name, "{}")
            } catch (e: Throwable) {
                failures += "$name 抛异常: ${e.message}"
                continue
            }
            executed += name
            if (result.isBlank()) {
                failures += "$name 返回空串"
            }
        }

        // 全部工具都应执行(可能返回错误,但不能崩溃/空返回)
        assertTrue(
            "部分工具未通过烟雾测试:\n${failures.joinToString("\n")}\n已执行 ${executed.size}/${toolNames.size}",
            failures.isEmpty(),
        )
        assertTrue("应覆盖全部注册工具", executed.size == toolNames.size)
    }

    @Test
    fun `browser tools execute without crash`() = runBlocking {
        // 浏览器工具走会话级 BrowserManager,需要主线程;烟雾测试只验证注册入口可达
        val names = listOf("browser_navigate", "browser_extract", "browser_get_html")
        for (name in names) {
            val result = registry.executeFromJson(name, "{}")
            assertNotNull("$name 返回不应为 null", result)
            assertTrue("$name 返回不应为空", result.isNotBlank())
        }
    }

    @Test
    fun `registry contains all documented built-in tool ids`() {
        val names = io.zer0.muse.tools.ToolRegistry.BUILT_IN_TOOL_IDS.toSet()
        // 核心工具必须注册(回归保护: 注册表被误删时立刻暴露)
        val required = listOf(
            "get_current_time", "calculator", "echo", "get_weather",
            "browser_navigate", "browser_extract",
            "schedule_reminder", "translate",
        )
        val missing = required.filterNot { it in names }
        assertTrue("缺少核心工具: $missing", missing.isEmpty())
    }
}
