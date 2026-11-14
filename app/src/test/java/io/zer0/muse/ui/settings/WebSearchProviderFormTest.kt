package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.mockk.mockk
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.web.WebSearchConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 组件级渲染判据：单家详情表单读 Catalog，不同 providerKey 的字段集正确切换。
 *
 * 与 `WebSearchProviderFormStateTest`（纯函数）和模拟器走查互补：这里在 CI 里固化
 * 「进入详情 → 动态表单」这条渲染链，防止不同供应商的字段互相串用而不被单测发现。
 * 用文本而非 testTag 定位，因为该组件面向用户，文本才是契约。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebSearchProviderFormTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(providerKey: String = "Tavily", config: WebSearchConfig = WebSearchConfig(), repo: SettingsRepository = settings) {
        composeTestRule.setContent {
            MaterialTheme {
                // 生产里这一段是 SettingsSubPageScaffold 的 LazyColumn item（可滚动）。
                // 测试也必须给滚动容器：15 个 chip + 动态字段远超一屏，
                // 不滚动时屏外节点 performClick 会静默点空、assertIsDisplayed 会假红。
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WebSearchProviderTypeSection(
                        providerKey = providerKey,
                        config = config,
                        settings = repo,
                        scope = CoroutineScope(SupervisorJob()),
                        webSearchClient = OkHttpClient(),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun countOf(text: String) = composeTestRule.onAllNodes(
        androidx.compose.ui.test.hasText(text),
    ).fetchSemanticsNodes().size

    /** 滚到可见再断言：屏外节点存在于语义树但没被布局到视口内。 */
    private fun assertVisible(text: String) {
        composeTestRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `默认 Tavily 显示 Key 池与默认端点 hint 不显示模型名`() {
        show()
        assertVisible("API Key")
        assertVisible("Connection endpoint")
        assertVisible("Managed automatically by Muse; changes are usually unnecessary")
        // 普通供应商的默认端点只读展示，不再要求用户填写 URL。
        assertEquals(1, countOf("https://api.tavily.com"))
        assertEquals(0, countOf("Custom URL (optional)"))
        assertEquals("Tavily 不需要模型名", 0, countOf("Model name (optional)"))
    }

    @Test
    fun `普通供应商不显示旧配置里的自定义地址`() {
        show(
            config = WebSearchConfig(
                providerEntries = listOf(
                    io.zer0.muse.web.WebSearchProviderEntry(
                        id = "Tavily",
                        type = "Tavily",
                        customUrl = "https://legacy.example.invalid/search",
                    ),
                ),
            ),
        )

        assertEquals("仍显示目录默认地址", 1, countOf("https://api.tavily.com"))
        assertEquals("不把旧 customUrl 当成当前地址", 0, countOf("https://legacy.example.invalid/search"))
    }

    @Test
    fun `切到 Bing 后 Key 与 URL 字段都消失`() {
        show(providerKey = "Bing")

        assertEquals("Bing 不该有 API Key 字段", 0, countOf("API Key"))
        assertEquals("Bing 不该有自定义 URL 字段", 0, countOf("Custom URL (optional)"))
        assertEquals("Bing 不该暴露 tavily 默认端点", 0, countOf("https://api.tavily.com"))
        // 状态胶囊与两个动作仍在
        assertVisible("Not tested")
    }

    @Test
    fun `切到 Perplexity 后显示模型名字段与对应默认端点`() {
        show(providerKey = "Perplexity")

        assertVisible("Model name (optional)")
        assertEquals(1, countOf("https://api.perplexity.ai"))
    }

    @Test
    fun `切到 SearXNG 后显示实例 URL 必填提示`() {
        show(providerKey = "SearXNG")

        // 详情页直接填入目录里的默认实例地址，不要求用户重复输入。
        assertEquals("默认实例地址已填入", 0, countOf("Instance URL is required"))
        assertEquals(1, countOf("https://searx.be"))
    }

    // ---- Key 池逐条展示与删除（对齐模型供应商侧 Key 池的行级 UI） ----

    /** mockk 存放成字段才能在删除后 verify 落库参数。 */
    private val settings = mockk<SettingsRepository>(relaxed = true)

    /** 已保存两把 Key 的 Tavily 配置。掩码形态见 WebSearchKeyFieldStateTest（头尾各 3 位：`tvl…333`）。 */
    private val seededConfig = WebSearchConfig(
        providerEntries = listOf(
            io.zer0.muse.web.WebSearchProviderEntry(
                id = "Tavily",
                type = "Tavily",
                apiKeys = listOf("tvly-alpha111222333", "tvly-beta444555666"),
            ),
        ),
    )

    @Test
    fun `已保存的多把 Key 逐条掩码展示 不回显完整 Key`() {
        show(config = seededConfig)

        // 每把一行：长 Key 掩码只露头尾各 3 位
        assertVisible("tvl…333")
        assertVisible("tvl…666")
        // 完整 Key 不得出现在语义树里（不回填铁律：开屏即明文＝投屏泄露）
        assertEquals("完整 Key 不得回显", 0, countOf("tvly-alpha111222333"))
        assertEquals(0, countOf("tvly-beta444555666"))
    }

    @Test
    fun `删除其中一把后按剩余池落库`() {
        show(config = seededConfig)

        val deleteButtons = composeTestRule.onAllNodes(
            androidx.compose.ui.test.hasContentDescription("Delete this key"),
        )
        org.junit.Assert.assertEquals("应有两条删除按钮", 2, deleteButtons.fetchSemanticsNodes().size)
        // 删第一行（第一把 Key）。行在 chip 网格之下，必须滚到视口内再点，
        // 否则 performClick 对屏外节点会静默点空（本项目已踩过的坑）。
        deleteButtons[0].performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // mockk 存储不会重发 flow，重组断言在测试夹具里不可观测；
        // 改为断言落库参数：剩余池恰好是第二把，且双写兼容位同步为首把。
        io.mockk.coVerify {
            settings.saveWebSearchConfig(
                match { cfg ->
                    val entry = cfg.providerEntries.first { it.type == "Tavily" }
                    entry.apiKeys == listOf("tvly-beta444555666") && cfg.apiKeys["Tavily"] == "tvly-beta444555666"
                },
            )
        }
    }
}
