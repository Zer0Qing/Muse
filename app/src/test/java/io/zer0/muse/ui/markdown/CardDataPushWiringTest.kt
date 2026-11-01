package io.zer0.muse.ui.markdown

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 卡片数据推送的接线契约。
 *
 * 行为验证在 `ci/script/card_script_contract_test.js`（Node 沙箱里真跑那段 JS）。
 * 这里锁的是**Kotlin 侧有没有把线接上**：注入能力标记、拿到 WebView 后调用推送、
 * 以及「不支持推送时仍然退回轮询」这条降级不许被删。
 *
 * 为什么值得锁：这三处任何一处失效都不会让编译失败 ——
 *   - 少了能力标记 → 卡片永远走轮询（回到每卡一个定时器）；
 *   - 少了 evaluateJavascript → 卡片订阅了但永远收不到更新；
 *   - 删掉轮询分支 → 无 CardDataStore 的宿主（工件查看等）失去刷新能力。
 */
class CardDataPushWiringTest {

    private fun readAppSource(relative: String): String =
        File("src/main/java/io/zer0/muse/$relative").readText()

    @Test
    fun `卡片脚本按能力标记选择推送或轮询`() {
        val source = readAppSource("ui/markdown/card/CardScript.kt")
        val subscribeAt = source.indexOf("window.muse.data.onChange = function(cb){")
        require(subscribeAt >= 0) { "找不到 onChange 定义" }
        val body = source.substring(subscribeAt, (subscribeAt + 2_000).coerceAtMost(source.length))

        val pushAt = body.indexOf("if (window.__museDataPush)")
        val intervalAt = body.indexOf("setInterval(")
        assertTrue("必须保留推送能力判断", pushAt >= 0)
        assertTrue("必须保留轮询降级", intervalAt >= 0)
        assertTrue(
            "轮询必须排在推送分支之后：先开定时器就等于永远在轮询",
            pushAt < intervalAt,
        )
        assertTrue("推送入口必须由宿主可调用", source.contains("window.__museDataEvent = function()"))
    }

    @Test
    fun `宿主拿到 WebView 后按 pushToken 推送`() {
        val source = readAppSource("ui/markdown/card/CardRuntime.kt")
        assertTrue(
            "必须调用推送脚本",
            source.contains("evaluateJavascript(CardScript.DATA_EVENT_JS"),
        )
        assertTrue(
            "推送必须以 pushToken 为键（否则数据变了也不会触发）",
            source.contains("LaunchedEffect(webViewRef, host.pushToken)"),
        )
        assertTrue(
            "不支持推送时 must 早退，避免无意义地反复执行 JS",
            source.contains("if (host.pushToken == null) return@LaunchedEffect"),
        )
        assertTrue(
            "离开组合要清掉 WebView 引用",
            source.contains("onWebViewReleased = { webViewRef = null }"),
        )
    }

    @Test
    fun `推送能力标记只在支持推送时注入`() {
        val source = readAppSource("ui/markdown/card/CardRuntime.kt")
        assertTrue(
            "必须注入 __museDataPush 标记",
            source.contains("window.__museDataPush=true"),
        )
        assertTrue(
            "标记必须由 pushEnabled 控制，不能无条件注入",
            source.contains("val pushBoot = if (pushEnabled)"),
        )
        assertTrue(
            "HTML 必须带上 pushBoot",
            source.contains("\$idBoot\$pushBoot\$safe\$scripts"),
        )
    }

    @Test
    fun `卡片调用点提供 pushToken`() {
        val source = readAppSource("ui/markdown/RichContentCard.kt")
        assertTrue(
            "SvgCard/HtmlCard 都必须把 pushToken 交给 CardRuntime",
            Regex("pushToken = rememberCardPushToken\\(store, cardId\\)")
                .findAll(source)
                .count() >= 2,
        )
        assertTrue(
            "订阅必须只盯本卡的键，否则任一卡片数据变化都会触发全部卡片",
            source.contains("store.data.map { it[cardId] }.distinctUntilChanged()"),
        )
    }
}
