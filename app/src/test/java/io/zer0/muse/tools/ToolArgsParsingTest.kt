package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.2（用户审计）: hook / 参数化审批 与执行路径的**参数解析同源**回归。
 *
 * 此前 parseToolCallArgs 走简单标准解析:畸形 JSON（拼接多对象）返回空 map,
 * 而实际执行走 parseArgumentsLenient 能救回来 —— hook 与 ParamPolicies 看到的
 * 参数与真正执行的不一致（file:// 之类的参数级拒绝还会因此失效）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolArgsParsingTest {

    private val registry: ToolRegistry
        get() = ToolRegistry(ApplicationProvider.getApplicationContext<Context>()).also {
            ParamPolicies.registerBuiltIn()
        }

    private fun parse(json: String): Map<String, Any> =
        toolArgsToMap(registry.parseArgumentsLenient(json))

    @Test
    fun `malformed concatenated json still yields the same args as execution`() {
        // 与 ToolRegistry.parseArgumentsLenient 同源:拼接多对象也能解出键。
        val args = parse("{}" + """{"url":"http://example.com"}""")
        assertEquals("http://example.com", args["url"])
    }

    @Test
    fun `file url is visible to param policies after lenient parse`() {
        // 关键安全回归:file:// URL 必须在参数级策略里可见（此前畸形 JSON → 空 map → 拒绝失效）。
        val args = parse("""{"url":"file:///etc/passwd"}""")
        val state = ParamPolicies.evaluate("open_url", args)
        assertTrue("file:// 应被参数策略拒绝", state is ToolApprovalState.Denied)
    }

    @Test
    fun `numeric and boolean args keep their json types`() {
        val args = parse("""{"count":3,"ratio":1.5,"enabled":true,"off":false,"name":"x"}""")
        assertEquals(3L, args["count"])
        assertEquals(1.5, args["ratio"])
        assertEquals(true, args["enabled"])
        assertEquals(false, args["off"])
        assertEquals("x", args["name"])
    }

    @Test
    fun `nested object is passed as json text`() {
        val args = parse("""{"data":{"labels":["a","b"]}}""")
        val text = args["data"] as String
        assertTrue("嵌套对象应以 JSON 文本透传", text.contains("\"labels\""))
    }
}
