package io.zer0.muse.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path

/**
 * 卡片回调的实例稳定性。
 *
 * 卡片桥以 `onCardAction` 的**实例**作为 remember 的 key：每帧换一个新 lambda，
 * 桥就重建一次，整段卡片 HTML（含 7 次全文正则清洗）跟着重拼。真机上是流式
 * 每帧重复做同一件事，所以这条链必须钉住：
 *
 *  - 同一个 msg.id 在多次重组之间拿到的是同一个 lambda 实例；
 *  - ChatScreen 与 CardRuntime 里确实是这么写的（源码级断言，见各用例注释）。
 *
 * 为什么不用「数 assembleHtml 调用次数」来测：它藏在 `CardRuntime` 私有的
 * `remember` 块里，要观测只能往生产代码里塞计数器 —— 那是为测试改实现。
 * 这里改为测「remember 本身给出的保证」（Compose 运行时用例）+ 钉住生产代码
 * 用的是这个保证（源码断言），两者合起来等价，且不污染生产代码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CardCallbackStabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Compose 运行时：remember(key) 的实例稳定性 ──────────────────────

    @Test
    fun `callback remembered by message id is the same instance across recompositions`() {
        val messageId = "msg-1"
        val tick = mutableStateOf(0)
        val rememberedInstances = mutableListOf<() -> Int>()
        val inlineInstances = mutableListOf<() -> Int>()

        composeTestRule.setContent {
            val current = tick.value
            // 与 ChatScreen 同一写法：key 只有不可变的 msg.id。
            val stable = remember(messageId) { { messageId.length } }
            // 对照：不 remember 的内联 lambda。
            val unstable = { messageId.length + current }
            SideEffect {
                rememberedInstances += stable
                inlineInstances += unstable
            }
            MaterialTheme {
                Text(text = "tick=$current")
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { tick.value = 1 }
        composeTestRule.waitForIdle()

        assertTrue("至少经历两次重组: ${rememberedInstances.size}", rememberedInstances.size >= 2)
        assertSame(
            "同一个 msg.id 的重组之间必须是同一个实例，否则卡片桥每帧重建",
            rememberedInstances.first(),
            rememberedInstances.last(),
        )
        assertNotSame(
            "不 remember 的写法每帧都是新对象 —— 这正是改前被击穿的缓存",
            inlineInstances.first(),
            inlineInstances.last(),
        )
    }

    // ── 源码级：生产代码用的是上面这条保证 ──────────────────────────────

    @Test
    fun `chat screen remembers the per message card bridge`() {
        val screen = readSource("ChatScreen.kt")

        val anchor = screen.indexOf("val onCardAction =")
        assertTrue("ChatScreen 里必须有 onCardAction 声明", anchor >= 0)
        val window = screen.substring(anchor, minOf(screen.length, anchor + 400))
        assertTrue(
            "卡片回传必须按 msg.id remember，否则下游 remember（桥 → HTML）每帧失效",
            window.contains("remember(msg.id)"),
        )
        assertTrue(
            "remember 之后仍要复用共享的 handleCardAction，不能另造第二套处理器",
            window.contains("handleCardAction(msg.id.toString(), action)"),
        )
        assertTrue(
            "MessageBubble 收到的必须是这个稳定实例",
            screen.contains("onCardAction = onCardAction,"),
        )
        assertFalse(
            "旧的内联 lambda 每次重组都是新对象",
            screen.contains("onCardAction = { action -> handleCardAction("),
        )
    }

    @Test
    fun `card runtime builds its web view client once`() {
        val runtime = readSource("markdown/card/CardRuntime.kt")

        assertTrue(
            "WebViewClient 必须 remember 一次，否则下游以它为 key 的 remember 恒失效",
            runtime.contains("val webViewClient = remember { RichContentWebViewClient() }"),
        )
        assertTrue(runtime.contains("webViewClient = webViewClient,"))
        assertFalse(
            "旧写法每帧 new 一个 client",
            runtime.contains("webViewClient = RichContentWebViewClient(),"),
        )
    }

    @Test
    fun `rich content card keeps its data reader across recompositions`() {
        val rich = readSource("markdown/RichContentCard.kt")

        assertTrue(
            "读数据回调也进卡片桥的 remember key，必须按依赖缓存",
            rich.contains("remember(store, inlineCardData) { { id: String -> store?.get(id) ?: inlineCardData } }"),
        )
        assertFalse(
            "旧写法每帧新建 lambda，桥跟着重建",
            rich.contains("readData = { id -> store?.get(id) ?: inlineCardData },"),
        )
    }

    /** 读 ui 目录下的生产源码（测试工作目录可能是模块根，也可能是仓库根）。 */
    private fun readSource(relative: String): String {
        val candidates = listOf(
            Path.of("src/main/java/io/zer0/muse/ui/$relative"),
            Path.of("app/src/main/java/io/zer0/muse/ui/$relative"),
        )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate ui/$relative from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
