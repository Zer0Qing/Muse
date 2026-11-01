package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 工具定义列表缓存。
 *
 * 这条路径的调用频率远超直觉：每次工具调用前的能力校验、每轮请求组装（两次）、
 * 定时任务、渠道自动回复都会调 `listToolsAsToolDefinitions()`。缓存失效语义必须
 * 与 `_revision` 严格对齐，否则会出现「新注册的工具在 schema 里看不见」这类
 * 静默故障 —— 所以这里重点测失效，而不是测快不快。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistryDefinitionCacheTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun registry(): ToolRegistry = ToolRegistry(context)

    private fun echoTool(name: String) = ToolRegistry.ToolDef(
        name = name,
        description = "测试用工具 $name",
        parameters = mapOf("text" to "要回显的文本"),
        parameterTypes = mapOf("text" to "string"),
        required = setOf("text"),
        riskLevel = ToolRiskLevel.SAFE,
    )

    @Test
    fun repeat_calls_return_the_same_instances() {
        val registry = registry()
        registry.register(echoTool("cache_probe_a")) { args -> args["text"] ?: "" }

        val first = registry.listToolsAsToolDefinitions()
        val second = registry.listToolsAsToolDefinitions()
        assertSame("注册表没变时必须命中缓存", first, second)
    }

    @Test
    fun registering_a_tool_invalidates_the_cache() {
        val registry = registry()
        registry.register(echoTool("cache_probe_b")) { args -> args["text"] ?: "" }
        val before = registry.listToolsAsToolDefinitions()
        val beforeNames = before.map { it.name }

        registry.register(echoTool("cache_probe_c")) { args -> args["text"] ?: "" }
        val after = registry.listToolsAsToolDefinitions()

        assertNotSame("注册新工具后必须重建", before, after)
        assertTrue("新工具必须出现在定义里", "cache_probe_c" in after.map { it.name })
        assertTrue("重建不应丢旧工具", beforeNames.all { it in after.map { d -> d.name } })
    }

    @Test
    fun unregistering_a_tool_invalidates_the_cache() {
        val registry = registry()
        registry.register(echoTool("cache_probe_d")) { args -> args["text"] ?: "" }
        assertTrue(registry.listToolsAsToolDefinitions().any { it.name == "cache_probe_d" })

        registry.unregister("cache_probe_d")
        assertTrue(
            "注销后不得再出现在定义里",
            registry.listToolsAsToolDefinitions().none { it.name == "cache_probe_d" },
        )
    }

    @Test
    fun filtered_list_is_cached_and_matches_manual_filter() {
        val registry = registry()
        registry.register(echoTool("cache_probe_e")) { args -> args["text"] ?: "" }
        registry.register(echoTool("cache_probe_f")) { args -> args["text"] ?: "" }

        val filter = listOf("cache_probe_e")
        val first = registry.listToolsAsToolDefinitions(filter)
        val second = registry.listToolsAsToolDefinitions(filter)
        assertSame("同一份过滤条件必须命中缓存", first, second)
        assertEquals(listOf("cache_probe_e"), first.map { it.name })

        val manual = registry.listToolsAsToolDefinitions().filter { it.name in filter }
        assertEquals("缓存结果必须与手工过滤一致", manual.map { it.name }, first.map { it.name })
    }

    @Test
    fun filter_cache_is_dropped_when_registry_changes() {
        val registry = registry()
        registry.register(echoTool("cache_probe_g")) { args -> args["text"] ?: "" }
        val filter = listOf("cache_probe_g")
        assertEquals(1, registry.listToolsAsToolDefinitions(filter).size)

        registry.register(echoTool("cache_probe_h")) { args -> args["text"] ?: "" }
        val after = registry.listToolsAsToolDefinitions(filter)
        assertEquals("过滤缓存必须随版本失效", listOf("cache_probe_g"), after.map { it.name })

        val both = registry.listToolsAsToolDefinitions(listOf("cache_probe_g", "cache_probe_h"))
        // 顺序由注册表内部 map 决定（与改动前一致），这里只比集合。
        assertEquals(setOf("cache_probe_g", "cache_probe_h"), both.map { it.name }.toSet())
    }

    @Test
    fun schema_shape_is_unchanged_for_parameter_tools() {
        val registry = registry()
        registry.register(echoTool("cache_probe_i")) { args -> args["text"] ?: "" }
        val definition = registry.listToolsAsToolDefinitions().first { it.name == "cache_probe_i" }
        assertTrue("仍要生成 object 类型 schema", definition.parametersJsonSchema.contains("\"type\":\"object\""))
        assertTrue("仍要包含参数描述", definition.parametersJsonSchema.contains("要回显的文本"))
        assertTrue("required 不能丢", definition.parametersJsonSchema.contains("\"required\""))
    }
}
