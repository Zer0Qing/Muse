package io.zer0.ai.core

import io.zer0.common.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BaseProviderRegistry 单元测试(P8)。
 *
 * 覆盖 P5-D 抽取的通用注册中心逻辑:
 *  - register / get / all 基础注册查询
 *  - resolveConfigKey(specId / preset_ 前缀剥离)
 *  - extractHost(baseUrl 解析)
 *  - matchByHost(host 模式匹配)
 *  - selectForInternal(三段式选择:精确 → host → 兜底)
 *
 * 使用 [TestRegistry] 子类暴露 protected 方法,并用 [FakeProvider] 作为泛型参数,
 * 避免依赖具体的 ImageProvider / VideoProvider 接口。
 */
class BaseProviderRegistryTest {

    /** 测试用的简单 Provider 标识,只携带 id 用于断言。 */
    private data class FakeProvider(val providerId: String)

    // Logger 依赖 android.util.Log(JVM 测试环境为 stub,会抛 RuntimeException),
    // 测试期间禁用日志输出,避免 register() 中的 Logger.i 调用崩溃。
    @Before
    fun disableLogger() {
        Logger.enabled = false
    }

    @After
    fun restoreLogger() {
        Logger.enabled = true
    }

    /** 测试用 BaseProviderRegistry 子类,暴露 protected 方法供测试调用。 */
    private class TestRegistry(
        hostPatterns: List<Pair<String, String>> = emptyList(),
    ) : BaseProviderRegistry<FakeProvider>("TestRegistry") {

        override val hostPatterns: List<Pair<String, String>> = hostPatterns

        // 暴露 protected 方法供测试直接调用(protected 在子类可见,无需 override)
        fun publicResolveConfigKey(config: ProviderConfig): String? = resolveConfigKey(config)
        fun publicExtractHost(baseUrl: String): String = extractHost(baseUrl)
        fun publicMatchByHost(host: String): FakeProvider? = matchByHost(host)

        fun selectFor(config: ProviderConfig, fallback: () -> FakeProvider? = { null }): FakeProvider? =
            selectForInternal(config, fallback)
    }

    // ── register / get / all ──────────────────────────────────────────────

    @Test
    fun `register stores provider by id`() {
        val registry = TestRegistry()
        val provider = FakeProvider("openai")
        registry.register("openai", provider)
        assertSame(provider, registry.get("openai"))
    }

    @Test
    fun `get returns null for unregistered id`() {
        val registry = TestRegistry()
        assertNull(registry.get("nonexistent"))
    }

    @Test
    fun `register overwrites previous provider with same id`() {
        val registry = TestRegistry()
        val first = FakeProvider("openai")
        val second = FakeProvider("openai")
        registry.register("openai", first)
        registry.register("openai", second)
        assertSame(second, registry.get("openai"))
    }

    @Test
    fun `all returns all registered providers`() {
        val registry = TestRegistry()
        registry.register("a", FakeProvider("a"))
        registry.register("b", FakeProvider("b"))
        registry.register("c", FakeProvider("c"))
        assertEquals(3, registry.all().size)
    }

    @Test
    fun `all returns empty collection when nothing registered`() {
        val registry = TestRegistry()
        assertTrue(registry.all().isEmpty())
    }

    // ── resolveConfigKey ───────────────────────────────────────────────────

    @Test
    fun `resolveConfigKey returns specId when present`() {
        val config = ProviderConfig(id = "preset_openai", displayName = "OpenAI", specId = "openai")
        val registry = TestRegistry()
        assertEquals("openai", registry.publicResolveConfigKey(config))
    }

    @Test
    fun `resolveConfigKey strips preset_ prefix from id when specId is null`() {
        val config = ProviderConfig(id = "preset_deepseek", displayName = "DeepSeek")
        val registry = TestRegistry()
        assertEquals("deepseek", registry.publicResolveConfigKey(config))
    }

    @Test
    fun `resolveConfigKey returns id as-is when no prefix and no specId`() {
        val config = ProviderConfig(id = "custom_provider", displayName = "Custom")
        val registry = TestRegistry()
        assertEquals("custom_provider", registry.publicResolveConfigKey(config))
    }

    // ── extractHost ────────────────────────────────────────────────────────

    @Test
    fun `extractHost returns lowercase host for https url`() {
        val registry = TestRegistry()
        assertEquals("api.openai.com", registry.publicExtractHost("https://api.openai.com/v1/chat"))
    }

    @Test
    fun `extractHost returns lowercase host for http url`() {
        val registry = TestRegistry()
        assertEquals("localhost", registry.publicExtractHost("http://localhost:8080/api"))
    }

    @Test
    fun `extractHost returns empty string for blank input`() {
        val registry = TestRegistry()
        assertEquals("", registry.publicExtractHost(""))
    }

    @Test
    fun `extractHost handles malformed url gracefully`() {
        val registry = TestRegistry()
        // "not-a-url" 是合法的相对 URI(不抛异常),但 host 为 null,返回空串
        val host = registry.publicExtractHost("not-a-url")
        assertEquals("", host)
    }

    // ── matchByHost ────────────────────────────────────────────────────────

    @Test
    fun `matchByHost returns provider when host contains pattern`() {
        val provider = FakeProvider("agnes")
        val registry = TestRegistry(hostPatterns = listOf("agnes" to "agnes"))
        registry.register("agnes", provider)
        assertSame(provider, registry.publicMatchByHost("agnes-ai.example.com"))
    }

    @Test
    fun `matchByHost is case insensitive`() {
        val provider = FakeProvider("kling")
        val registry = TestRegistry(hostPatterns = listOf("klingai" to "kling"))
        registry.register("kling", provider)
        assertSame(provider, registry.publicMatchByHost("KlingAI.example.com"))
    }

    @Test
    fun `matchByHost returns null when no pattern matches`() {
        val registry = TestRegistry(hostPatterns = listOf("agnes" to "agnes"))
        registry.register("agnes", FakeProvider("agnes"))
        assertNull(registry.publicMatchByHost("api.openai.com"))
    }

    @Test
    fun `matchByHost returns null for empty host`() {
        val registry = TestRegistry(hostPatterns = listOf("agnes" to "agnes"))
        registry.register("agnes", FakeProvider("agnes"))
        assertNull(registry.publicMatchByHost(""))
    }

    @Test
    fun `matchByHost returns first matching pattern when multiple patterns exist`() {
        val agnes = FakeProvider("agnes")
        val kling = FakeProvider("kling")
        val registry = TestRegistry(hostPatterns = listOf(
            "agnes" to "agnes",
            "klingai" to "kling",
        ))
        registry.register("agnes", agnes)
        registry.register("kling", kling)
        assertSame(agnes, registry.publicMatchByHost("agnes-ai.example.com"))
        assertSame(kling, registry.publicMatchByHost("klingai.example.com"))
    }

    // ── selectFor (三段式:精确 → host → 兜底) ─────────────────────────────

    @Test
    fun `selectFor returns provider by specId match first`() {
        val bySpec = FakeProvider("bySpec")
        val byHost = FakeProvider("byHost")
        val registry = TestRegistry(hostPatterns = listOf("api.openai.com" to "byHost"))
        registry.register("openai", bySpec)
        registry.register("byHost", byHost)

        val config = ProviderConfig(
            id = "preset_openai",
            displayName = "OpenAI",
            specId = "openai",
            baseUrl = "https://api.openai.com",
        )
        assertSame(bySpec, registry.selectFor(config))
    }

    @Test
    fun `selectFor falls back to host match when specId not registered`() {
        val byHost = FakeProvider("byHost")
        val registry = TestRegistry(hostPatterns = listOf("agnes" to "agnes"))
        registry.register("agnes", byHost)

        // specId 不在注册表,但 host 匹配
        val config = ProviderConfig(
            id = "custom_1",
            displayName = "Custom",
            specId = "unknown",
            baseUrl = "https://agnes-ai.example.com",
        )
        assertSame(byHost, registry.selectFor(config))
    }

    @Test
    fun `selectFor falls back to host match via preset_ prefix when id not registered`() {
        val byHost = FakeProvider("agnes")
        val registry = TestRegistry(hostPatterns = listOf("agnes" to "agnes"))
        registry.register("agnes", byHost)

        // id = preset_agnes → 剥离前缀得 "agnes",但未注册;走 host 匹配
        val config = ProviderConfig(
            id = "preset_agnes_other",
            displayName = "Agnes",
            baseUrl = "https://agnes-ai.example.com",
        )
        assertSame(byHost, registry.selectFor(config))
    }

    @Test
    fun `selectFor returns fallback when neither specId nor host matches`() {
        val fallback = FakeProvider("fallback")
        val registry = TestRegistry(hostPatterns = listOf("agnes" to "agnes"))
        registry.register("agnes", FakeProvider("agnes"))

        val config = ProviderConfig(
            id = "custom_1",
            displayName = "Custom",
            baseUrl = "https://api.openai.com",
        )
        assertSame(fallback, registry.selectFor(config) { fallback })
    }

    @Test
    fun `selectFor returns null when no match and fallback returns null`() {
        val registry = TestRegistry(hostPatterns = listOf("agnes" to "agnes"))
        registry.register("agnes", FakeProvider("agnes"))

        val config = ProviderConfig(
            id = "custom_1",
            displayName = "Custom",
            baseUrl = "https://api.openai.com",
        )
        assertNull(registry.selectFor(config) { null })
    }

    @Test
    fun `selectFor returns null when host is blank and no specId match`() {
        val registry = TestRegistry(hostPatterns = listOf("agnes" to "agnes"))
        registry.register("agnes", FakeProvider("agnes"))

        // baseUrl 留空,resolvedBaseUrl 走默认值(取决于 type)
        val config = ProviderConfig(id = "custom_1", displayName = "Custom", type = ProviderType.OPENAI)
        // OPENAI 默认 baseUrl 是 api.openai.com,不匹配 agnes host pattern
        val result = registry.selectFor(config) { null }
        assertNull(result)
    }
}
