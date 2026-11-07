package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具产出图片时的载体解析（[ToolScreenshotPayload]）。
 *
 * 钉住两件容易悄悄坏掉的事：
 *  1. 给模型和任务卡的文本里**不许出现 Base64**。一旦漏掉，几 MB 字符串会当成
 *     普通超长输出进截断与落盘管线，上下文里塞进一坨无意义字节，任务卡也变成削头 JSON。
 *  2. 不该提取的时候不许提取：失败结果、没有约定字段的结果、非 JSON 结果，
 *     都必须原样走普通输出路径（浏览器截图与工作区图片读取都靠这条约定）。
 *
 * 这一层是纯字符串规则，所以能脱离编排器的依赖直接测 —— 这正是把它抽出来的理由。
 */
class ToolScreenshotPayloadTest {

    /** 工作区图片读取的结果形状：正文 + 载体 + 说明。逐段拼接，不把一行拉成一丈长。 */
    private val workspaceImageJson =
        "{\"success\":true,\"data\":\"image | a.png | image/png | 240.0KB 原图已随附。\",\"error\":null," +
            "\"screenshot\":\"iVBORw0KGgoAAAANSUhEUg\",\"screenshotCaption\":\"[工作区图片] a.png\"}"

    @Test
    fun `一次解析取齐图片说明与正文`() {
        val payload = ToolScreenshotPayload.of(workspaceImageJson, isSuccess = true)
        assertNotNull("带约定字段的成功结果必须被识别", payload)
        payload!!
        assertEquals("iVBORw0KGgoAAAANSUhEUg", payload.base64)
        assertTrue("说明文本要能取到：${payload.caption}", payload.caption.orEmpty().contains("工作区图片"))
        assertTrue("正文要能取到：${payload.dataText}", payload.dataText.orEmpty().startsWith("image |"))
    }

    @Test
    fun `给模型的文本里不许出现 Base64`() {
        val payload = requireNotNull(ToolScreenshotPayload.of(workspaceImageJson, isSuccess = true))
        val text = ToolScreenshotPayload.resultText(payload)
        assertTrue("文本必须是正文本身，不是 JSON 包装：$text", text.startsWith("image |"))
        assertTrue("绝不允许把载体字节塞进上下文", !text.contains("iVBORw0KGgo"))
        assertTrue("也不该把 screenshot 字段名带进去", !text.contains("screenshot"))
    }

    @Test
    fun `失败结果一律不提取`() {
        // 失败时 Base64 可能是半截的坏数据，回填成带图消息会让模型看到一张打不开的图。
        assertNull(ToolScreenshotPayload.of(workspaceImageJson, isSuccess = false))
    }

    @Test
    fun `没有约定字段的结果原样走普通输出`() {
        assertNull(ToolScreenshotPayload.of("""{"success":true,"data":"普通文本"}""", isSuccess = true))
        // 纯文本工具结果（不是 JSON）同样不能被当图片载体。
        assertNull(ToolScreenshotPayload.of("screenshot 这个词出现在普通报错里也不算", isSuccess = true))
    }

    @Test
    fun `损坏的 JSON 不炸只当无图`() {
        // 工具结果由外部拼装，坏 JSON 是现实情况：解析失败必须降级，不能抛出去打断整轮生成。
        assertNull(ToolScreenshotPayload.of("""{"success":true,"screenshot":""", isSuccess = true))
    }

    @Test
    fun `没有正文时给一句说得清的占位`() {
        val json = """{"success":true,"error":null,"screenshot":"AAAA"}"""
        val payload = requireNotNull(ToolScreenshotPayload.of(json, isSuccess = true))
        assertNull("没有 data 字段时不该编造正文", payload.dataText)
        assertTrue(
            "占位文本必须明说图片在另一条消息里",
            ToolScreenshotPayload.resultText(payload).contains("下一条图片消息"),
        )
    }
}
