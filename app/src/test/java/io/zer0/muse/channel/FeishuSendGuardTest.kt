package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 飞书长连接写入结果的判定，以及**这条判定真的接到了心跳循环上**。
 *
 * 起因：`FeishuReceiver` 里心跳与事件 ACK 都写成 `runCatching { webSocket.send(…) }`。
 * OkHttp 的 `send()` 失败方式是**返回 false**，不抛异常 —— 所以那种写法连失败都拿不到，
 * 结果：
 *  - 心跳发不出去却没人知道 → 服务端按超时判死这条连接，期间**用户新发的消息根本进不来**，
 *    而界面仍显示"已连接"；
 *  - ACK 没发出去 → 平台按未确认**重投事件**，用户看到"同一条消息被回了两次"。
 */
class FeishuSendGuardTest {

    @Test
    fun `成功时不报问题`() {
        assertNull(feishuSendIssue(sent = true, error = null))
    }

    @Test
    fun `返回 false 是一种独立失败（原来的 runCatching 看不见这一种）`() {
        val issue = feishuSendIssue(sent = false, error = null)
        assertNotNull("`send()` 返回 false 必须被当成失败", issue)
        assertTrue("要说清是没进缓冲区，而不是笼统的失败：$issue", issue!!.contains("false"))
    }

    @Test
    fun `抛异常时带上可定位的原因`() {
        val withMessage = feishuSendIssue(sent = false, error = IllegalStateException("socket closed"))
        assertTrue("异常文字要进日志：$withMessage", withMessage!!.contains("socket closed"))

        val noMessage = feishuSendIssue(sent = false, error = NullPointerException())
        assertTrue("异常没有 message 时退回类名，别只剩「null」：$noMessage", noMessage!!.contains("NullPointerException"))
    }

    @Test
    fun `阈值语义是一次不算死两次才重连`() {
        assertFalse("单次失败可能只是瞬时缓冲满，不该立刻掐连接", shouldReconnectOnPingFailure(1))
        assertTrue(
            "连续两次发不出去就该判定这条链路已废并走重连",
            shouldReconnectOnPingFailure(PING_FAILURES_BEFORE_RECONNECT),
        )
        assertTrue(shouldReconnectOnPingFailure(PING_FAILURES_BEFORE_RECONNECT + 5))
        assertEquals(2, PING_FAILURES_BEFORE_RECONNECT)
    }

    /**
     * 判定函数本身测好了还不够 —— 它必须真的被心跳循环使用，
     * 否则"写了没人接"正是这类功能静默失效的原样。
     */
    @Test
    fun `心跳循环真的接上了失败计数与主动重连`() {
        val source = readReceiverSource()

        // 把断言范围缩到 pingJob 那块：`disconnected.complete(Unit)` 在别处（onFailure/onClosed）
        // 也出现，整文件级 contains 是弱断言，改坏了也测不出来。
        val block = source
            .substringAfter("val pingJob = appScope.launch {")
            .substringBefore("val request = Request.Builder()")
        assertTrue("心跳块要能取到（说明锚点还在）", block.contains("webSocket.send(pingFrame"))

        assertTrue("心跳要检查写入结果", block.contains("feishuSendIssue(outcome.getOrDefault(false)"))
        assertTrue("要统计连续失败次数", block.contains("pingFailures++"))
        assertTrue("达到阈值要走主动重连判定", block.contains("shouldReconnectOnPingFailure(pingFailures)"))
        assertTrue(
            "重连的实现方式：让当前连接结束、交给外层循环重建（必须在心跳块内，否则只是别处的正常断开）",
            block.contains("disconnected.complete(Unit)"),
        )
        assertTrue("成功时要清零计数（否则偶发失败会累积到误判断连）", block.contains("pingFailures = 0"))

        val ackPart = source.substringAfter("if (shouldAcknowledgeFeishuEventFrame(")
        assertTrue("事件 ACK 的失败也要留痕", ackPart.contains("feishuSendIssue(ackOutcome"))
        assertTrue("ACK 失败日志要点明重投后果：$ackPart", ackPart.contains("重投"))
    }

    private fun readReceiverSource(): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/channel/FeishuReceiver.kt"),
            File("app/src/main/java/io/zer0/muse/channel/FeishuReceiver.kt"),
        )
        val file = requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到 FeishuReceiver.kt，工作目录=${File(".").absolutePath}"
        }
        return file.readText()
    }
}
