package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto 回退链的**用户顺序**（整改计划 第 1 条 ② 的核心）。
 *
 * 之前这条链的顺序是硬编码的（自定义引擎 → 配了 Key 的 API → 免费抓取），
 * 用户只能看不能调；而"谁家 API 更稳、谁更快"恰恰是用户比自己代码更清楚的事。
 *
 * 要守住的三条判据：
 *  1. 没设置顺序时**一个都不变**（旧行为，老配置零迁移）；
 *  2. 列出的按用户顺序排前，**没列出的保持原本的相对顺序**跟在后面（稳定重排，
 *     不能让"我只想调两家"变成"其余九家随机洗牌"）；
 *  3. 写了不存在/没配置的引擎名要**忽略而不是抛错**（用户改了别家配置不该让搜索整条挂掉）。
 */
class WebSearchChainOrderTest {

    private class FakeProvider(override val name: String) : WebSearchService {
        override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = emptyList()
        override suspend fun searchWithOptions(query: String, maxResults: Int, options: Map<String, String>): List<WebSearchResult> =
            emptyList()
    }

    private val defaultChainKeys = listOf("custom:我的引擎", "Tavily", "Brave", "Serper", "SearXNG", "Bing", "Baidu")
    private val chain: List<Pair<String, WebSearchService>> = defaultChainKeys.map { it to FakeProvider(it) }

    private fun keysOf(result: List<Pair<String, WebSearchService>>): List<String> = result.map { it.first }

    @Test
    fun `没有设置顺序时链一模一样`() {
        assertEquals(defaultChainKeys, keysOf(orderWebSearchChain(chain, emptyList())))
    }

    @Test
    fun `列出的按用户顺序排前，其余保持原相对顺序`() {
        assertEquals(
            listOf("Bing", "Tavily", "custom:我的引擎", "Brave", "Serper", "SearXNG", "Baidu"),
            keysOf(orderWebSearchChain(chain, listOf("Bing", "Tavily"))),
        )
    }

    @Test
    fun `写了不存在或没配置的引擎名被忽略而不是报错`() {
        assertEquals(
            "不存在的键不该改变链，也不该抛异常",
            listOf("Serper", "custom:我的引擎", "Tavily", "Brave", "SearXNG", "Bing", "Baidu"),
            keysOf(orderWebSearchChain(chain, listOf("Serper", "完全不存在的引擎"))),
        )
    }

    @Test
    fun `关闭回退时取到的是用户排第一的那家`() {
        val ordered = orderWebSearchChain(chain, listOf("SearXNG", "Bing"))
        assertEquals("take(1) 必须是用户的第一选择", "SearXNG", ordered.first().first)
    }

    @Test
    fun `只有一家的链不需要重排`() {
        val single = listOf("Tavily" to FakeProvider("Tavily"))
        assertEquals(listOf("Tavily"), keysOf(orderWebSearchChain(single, listOf("Bing", "Tavily"))))
    }

    @Test
    fun `自定义引擎的键带前缀，避免与内置引擎同名互顶`() {
        assertEquals("custom:Tavily", customSearchOrderKey("Tavily"))
        val mixed = listOf("custom:Tavily" to FakeProvider("c"), "Tavily" to FakeProvider("t"))
        assertEquals(
            "用户说排 Tavily 时排的是内置那家，不该把同名的自定义引擎顶上去",
            listOf("Tavily", "custom:Tavily"),
            keysOf(orderWebSearchChain(mixed, listOf("Tavily"))),
        )
    }

    /**
     * 集成角度的一条：设置页存的 `providerOrder` 必须真的改变实际尝试顺序。
     *
     * 界面与链之间最容易出的事故是"两边各有一份顺序"：拖完列表看起来变了，
     * 请求却还是按硬编码走。这里直接用链的构造函数验证，覆盖那条路径。
     * 构造 provider 本身不发请求，所以不需要联网。
     */
    @Test
    fun `配置里的顺序决定链的实际顺序`() {
        val client = okhttp3.OkHttpClient()
        val cfg = WebSearchConfig(
            enabled = true,
            apiKeys = mapOf("Tavily" to "t-key", "Brave" to "b-key"),
            providerOrder = listOf("Brave", "Tavily"),
        )
        val names = buildSearchChain(client, cfg).map { it.name }
        assertEquals("用户把 Brave 排前面，第一个就应该是它", "Brave", names.first())
        assertEquals("第二才是 Tavily（两家都配了 Key）", "Tavily", names[1])
    }

    @Test
    fun `没配密钥的引擎不进链`() {
        val client = okhttp3.OkHttpClient()
        val names = buildSearchChain(client, WebSearchConfig(enabled = true, apiKeys = mapOf("Brave" to "b-key")))
            .map { it.name }
        assertTrue("配了 Key 的 Brave 必须在链上", names.contains("Brave"))
        assertFalse("没配 Key 的 Tavily 不该占一次空转请求", names.contains("Tavily"))
        assertTrue("免费抓取始终兜底", names.contains("Bing") && names.contains("Baidu"))
    }

    @Test
    fun `关掉回退时只用用户排第一的那家`() {
        val client = okhttp3.OkHttpClient()
        val cfg = WebSearchConfig(
            enabled = true,
            apiKeys = mapOf("Tavily" to "t-key", "Brave" to "b-key"),
            providerOrder = listOf("Brave", "Tavily"),
            fallbackEnabled = false,
        )
        val names = buildSearchChain(client, cfg).map { it.name }
        assertEquals(listOf("Brave"), names)
    }

    @Test
    fun `重排不丢任何一家`() {
        val ordered = orderWebSearchChain(chain, listOf("Baidu", "Brave", "幽灵引擎"))
        assertEquals(
            "重排前后成员必须完全一致（漏一家就等于那个引擎静默失效）",
            defaultChainKeys.toSet(),
            keysOf(ordered).toSet(),
        )
        assertTrue("长度也不能变", ordered.size == chain.size)
    }
}
