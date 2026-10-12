package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AUD-CHANNEL-F8: 飞书 webhook 入站必须解析 content JSON,与长连接路径同口径。
 *
 * 原 webhook 路径直接取 message.content 原始串({"text":"你好"})当正文,
 * 模型收到 JSON 并可能复读;长连接路径则会解析并剥 @ 占位符。
 */
class FeishuInboundTextTest {

    @Test
    fun `text content json is parsed to plain text`() {
        val out = FeishuReceiver.parseFeishuMessageText(
            content = """{"text":"你好"}""",
            messageType = "text",
        )
        assertEquals("你好", out)
    }

    @Test
    fun `at placeholder is stripped`() {
        val out = FeishuReceiver.parseFeishuMessageText(
            content = """{"text":"@_user_1 在吗"}""",
            messageType = "text",
        )
        assertEquals("在吗", out)
    }

    @Test
    fun `non-text message types map to placeholders`() {
        assertEquals("[图片]", FeishuReceiver.parseFeishuMessageText("""{"image_key":"x"}""", "image"))
        assertEquals("[语音]", FeishuReceiver.parseFeishuMessageText("""{"file_key":"x"}""", "audio"))
        assertEquals("[文件]", FeishuReceiver.parseFeishuMessageText("""{"file_key":"x"}""", "file"))
    }

    @Test
    fun `malformed content falls back to empty`() {
        assertEquals("", FeishuReceiver.parseFeishuMessageText("not-json", "text"))
    }
}
