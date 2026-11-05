package io.zer0.muse.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.tools.BrowserManager
import io.zer0.muse.tools.BrowserSearchEngine
import io.zer0.muse.tools.BrowserSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 浏览器设置页（整改计划 第 2 条）。
 *
 * 第 120 轮我把这一页接上时只测了存储层，页面本身一个测试都没有 —— 而它恰恰是
 * "改了没生效"那类问题的现场：`BrowserManagerRegistry` 里可能同时存在多个 `BrowserManager`
 * （shared + 每会话各一个），如果 store 每实例各建一份，用户在页面上选了 DuckDuckGo，
 * 正在用的 manager 还读着旧值，而且不报错、肉眼也看不出来。
 * 所以这里锁两件事：
 *  1. **页面真的把选择写进了那个全应用单例**，而 `BrowserManager` 读到的就是同一个对象；
 *  2. 每个分组**只出现一次**（本仓有过"同一标题渲染两行"的缺陷，结构断言比截图更早发现问题）。
 *
 * 说清测到哪：Robolectric + NATIVE 图形模式下点 chip 的回调不一定能被观察到
 * （同目录 `AutonomousAccessibilityCardRowTest` 的注释里就记过这件事），
 * 所以"点一下是否生效"用直接调 store 的方式验，UI 侧只断言渲染与选中态；
 * 真机上点按手感仍归验收表 E 段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserSettingsPageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        BrowserSettingsStore.resetForTests()
    }

    private fun show() {
        composeTestRule.setContent {
            MaterialTheme {
                BrowserSettingsPage(onBack = {})
            }
        }
    }

    private fun text(resId: Int): String = context.getString(resId)

    @Test
    fun `页面渲染六个引擎与两个 Cookies 动作且各自不重复`() {
        show()
        composeTestRule.waitForIdle()
        // 引擎名走资源，逐个都必须在场
        listOf(
            R.string.settings_browser_engine_bing,
            R.string.settings_browser_engine_baidu,
            R.string.settings_browser_engine_sogou,
            R.string.settings_browser_engine_360,
            R.string.settings_browser_engine_google,
            R.string.settings_browser_engine_duckduckgo,
        ).forEach { res ->
            composeTestRule.onNodeWithText(text(res)).assertExists()
        }
        composeTestRule.onNodeWithText(text(R.string.settings_browser_search_title)).assertExists()
        composeTestRule.onNodeWithText(text(R.string.settings_browser_accept_cookies)).assertExists()
        composeTestRule.onNodeWithText(text(R.string.settings_browser_clear)).assertExists()
    }

    @Test
    fun `页面写的就是 BrowserManager 读的那一份`() {
        // 这是第 120 轮那个隐患的直接判据：两处必须拿到同一个 store。
        val fromPage = BrowserSettingsStore.of(context)
        val fromManager = BrowserManager(context).browserSettings
        assertSame(
            "设置页写 A 份、浏览器读 B 份，用户就会遇到「保存了但没生效」",
            fromPage,
            fromManager,
        )
    }

    @Test
    fun `改引擎后浏览器侧立即可见且落盘`() {
        val store = BrowserSettingsStore.of(context)
        val engine = kotlinx.coroutines.runBlocking {
            store.setEngine(BrowserSearchEngine.DUCKDUCKGO)
            store.engine.value
        }
        assertEquals(BrowserSearchEngine.DUCKDUCKGO, engine)
        assertEquals(
            "BrowserManager 读到的引擎必须跟着变，否则设置页就是装饰",
            BrowserSearchEngine.DUCKDUCKGO,
            BrowserManager(context).browserSettings.engine.value,
        )
        assertEquals(
            "另建一个 store 引用也必须读回同一个值（真单例，而不是各存一份）",
            BrowserSearchEngine.DUCKDUCKGO,
            BrowserSettingsStore.of(context).engine.value,
        )
    }

    @Test
    fun `关掉接受 Cookies 不影响引擎选择`() {
        val store = BrowserSettingsStore.of(context)
        kotlinx.coroutines.runBlocking {
            store.setEngine(BrowserSearchEngine.BAIDU)
            store.setAcceptCookies(false)
        }
        assertEquals(BrowserSearchEngine.BAIDU, store.engine.value)
        assertEquals(false, store.acceptCookies.value)
    }
}
