package io.zer0.muse.ui.markdown

import io.zer0.common.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 卡片内部失败上报通道的契约。
 *
 * 桥是单向的：JS 调宿主失败时（桥被回收、WebView 正在销毁、参数不合法），
 * 卡片脚本原先只能 `catch (e) { }` —— 用户看到的是"点了卡片按钮没反应"，
 * 而宿主这边**一行日志都没有**。现在多了一条只写不读的 [MuseCardBridge.reportError]。
 *
 * 断言分两面：
 *  - 行为：调上报 → 日志里有卡片 id 与原因，且不产生任何回传动作；
 *  - 接线：两份 JS 模板里那两处静默 `catch` 确实改成调用上报函数
 *    （桥方法写了没人调 = 等于没加，这在仓库里已有多起先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CardBridgeErrorReportTest {

    private val seen = mutableListOf<Pair<String, String>>()
    private val actions = mutableListOf<CardAction>()

    @After
    fun tearDown() {
        Logger.sink = null
        seen.clear()
        actions.clear()
    }

    private fun capture() {
        seen.clear()
        Logger.sink = { _, tag, msg, _ -> seen += tag to msg }
    }

    private fun ourLogs() = seen.filter { it.first == "MuseCardBridge" }

    private fun bridge(cardId: String? = "msg-7") = MuseCardBridge(
        onAction = { actions += it },
        readCardData = { null },
        cardId = cardId,
    )

    @Test
    fun `上报会留下一条带卡片身份的日志`() {
        capture()

        bridge().reportError("send: TypeError: cannot read property")

        val log = ourLogs().singleOrNull()
        assertNotNull("桥调用失败本该有痕迹，实际日志：$seen", log)
        assertTrue("要带上卡片 id，才知道是哪一张卡：${log!!.second}", log.second.contains("msg-7"))
        assertTrue("要带上原因原文：${log.second}", log.second.contains("cannot read property"))
    }

    @Test
    fun `上报不回传任何动作也不改变行为`() {
        capture()
        val b = bridge()

        b.reportError("boom")

        assertEquals("上报只该写日志，不该被当成用户回传", 0, actions.size)
    }

    @Test
    fun `没有卡片身份时也不能写出看不出是谁的日志`() {
        capture()

        bridge(cardId = null).reportError("boom")

        val log = ourLogs().singleOrNull()
        assertNotNull(log)
        assertTrue("无 id 时要如实写「无」而不是留空：${log!!.second}", log.second.contains("cardId=无"))
    }

    @Test
    fun `空原因也留一条可诊断的日志`() {
        capture()

        bridge().reportError("   ")

        val log = ourLogs().singleOrNull()
        assertNotNull(log)
        assertTrue(
            "message 为空时不能只留「卡片内部报错 | 」这种没有信息量的行：${log!!.second}",
            log.second.contains("卡片未提供原因"),
        )
    }

    @Test
    fun `上报文本被截断防止一张坏卡刷满日志`() {
        capture()
        val long = "x".repeat(2_000)

        bridge().reportError(long)

        val log = ourLogs().single()
        val payload = log.second.substringAfter("| ")
        assertTrue(
            "上报内容要受上限约束（桥方法可被脚本任意次调用），实际长度 ${payload.length}",
            payload.length <= 600,
        )
    }

    @Test
    fun `两份卡片运行时都把静默 catch 接到了上报通道`() {
        val action = readAppSource("ui/markdown/CardAction.kt")
        val script = readAppSource("ui/markdown/card/CardScript.kt")

        for ((name, src) in listOf("CardAction" to action, "CardScript" to script)) {
            assertTrue("$name 应定义 reportBridgeFailure", src.contains("function reportBridgeFailure(where, err){"))
            assertTrue("$name 的 send 失败要上报", src.contains("reportBridgeFailure('send', e)"))
            assertTrue("$name 的 sendWithData 失败要上报", src.contains("reportBridgeFailure('sendWithData', e)"))
            // 空 catch 回来就等于把这条通道又关掉：这一断言防止有人"顺手清理"
            assertTrue(
                "$name 不该再出现空 catch 的桥调用：",
                !Regex("""bridge\.send\w*\(body[^)]*\)\s*;\s*\}\s*catch\s*\(e\)\s*\{\s*\}""").containsMatchIn(src) &&
                    !Regex("""MuseCardBridge\.send\w*\(body[^)]*\)\s*;\s*\}\s*catch\s*\(e\)\s*\{\s*\}""").containsMatchIn(src),
            )
        }
    }

    private fun readAppSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/$relative"),
            File("app/src/main/java/io/zer0/muse/$relative"),
        )
        val file = requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到 $relative，工作目录=${File(".").absolutePath}"
        }
        return file.readText()
    }
}
