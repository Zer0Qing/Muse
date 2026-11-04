package io.zer0.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiEngineTest {
    @Test
    fun appEngineRoundTripsAndPreservesOffsets() {
        val input = "手机 13800138000,邮箱 a@b.com"
        val (masked, matches) = PiiEngine.maskApp(input)
        assertFalse(masked.contains("13800138000"))
        assertTrue(matches.any { it.type == PiiEngine.AppType.PHONE })
        assertEquals(input, PiiEngine.unmaskApp(masked, matches))
        assertEquals(
            "13800138000",
            input.substring(
                matches.first {
                    it.type == PiiEngine.AppType.PHONE
                }.start..matches.first { it.type == PiiEngine.AppType.PHONE }.end - 1,
            ),
        )
    }

    @Test
    fun memoryEngineRedactsMultiSegmentApiKeys() {
        // 2026-11-04 的真实缺口：规则原本是 `sk-[a-z0-9_]{20,}`，
        // 而 OpenAI 项目钥匙 `sk-proj-…` / Anthropic `sk-ant-…` 在第一个 `-` 处就断了 ——
        // 用户在聊天里贴一把这种钥匙，会**原样写进长期记忆**（记忆链路走 scrubMemory）。
        val openai = PiiEngine.scrubMemory("我的钥匙是 sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz1234 请保存")
        assertFalse("sk-proj 多段密钥没被抹掉：${openai.cleaned}", openai.cleaned.contains("AbCdEfGhIjKlMnOpQrStUvWxYz1234"))
        assertTrue("要报告成 api_key：${openai.detected}", "api_key" in openai.detected)

        val anthropic = PiiEngine.scrubMemory("备用 sk-ant-9012abcdefABCDEF3456ghiJKLmnopQRST7890")
        assertFalse("sk-ant 也没被抹掉：${anthropic.cleaned}", anthropic.cleaned.contains("9012abcdefABCDEF3456"))

        // 反向：短的正常写法不该被当成密钥（否则记忆里的普通内容会被吃掉）
        val benign = PiiEngine.scrubMemory("别名 sk-foo 和 sk_short 是正常配置名")
        assertEquals("别名 sk-foo 和 sk_short 是正常配置名", benign.cleaned)
        assertTrue(benign.detected.isEmpty())
    }

    @Test
    fun logScrubCoversTransportShapesAndDeliberatelyKeepsNamesAndAddresses() {
        // 2026-11-04：`Logger` 自带的那份正则表合到这里了（一处真源）。
        // 这几条是搬过来的形状，必须在**新家**也有直接用例，不能只靠 Logger 的测试隔着看。
        val header = PiiEngine.scrubForLogs("请求头 Authorization: Bearer sk-live-ABCDEFGHIJKLMNOP 被拒")
        assertFalse("header 形状的凭据没抹：$header", header.contains("ABCDEFGHIJKLMNOP"))

        val json = PiiEngine.scrubForLogs("""{"access_token":"zzz-secret-9999"}""")
        assertFalse("JSON 键值形状没抹：$json", json.contains("zzz-secret-9999"))

        val url = PiiEngine.scrubForLogs("https://api.example/v1/chat?key=SECRET123&model=x")
        assertFalse("URL query 形状没抹：$url", url.contains("SECRET123"))

        val ids = PiiEngine.scrubForLogs("手机 13812345678 邮箱 a@b.com 源 10.1.2.3")
        assertTrue("标识类要留类别标记：$ids", ids.contains("[手机号]") && ids.contains("[邮箱]") && ids.contains("[IP]"))

        // 与 scrubMemory 的差别是刻意的：日志保留姓名与地址（抹掉会让排查看不出是谁的哪件事），
        // 记忆链路才抹。这条断言防止以后有人"顺手统一"把日志也抹干净。
        val kept = PiiEngine.scrubForLogs("张伟的收货地址是北京市朝阳区幸福路 12 号")
        assertTrue("日志不该抹姓名/地址（取舍见 scrubForLogs 注释）：$kept", kept.contains("张伟"))
    }

    @Test
    fun `路径里的机器人令牌也要被隐去`() {
        // Telegram Bot API 的 URL 把令牌放在**路径段**里，
        // 而 header / key= / ?token= 三条规则都只认查询串与头部形状 —— 上游报错常带整个 URL，
        // 不处理就等于把机器人钥匙写进要导出的日志文件。
        val raw = "HTTP 401 https://api.telegram.org/bot123456789:AAHsecretTOKEN-abc/getUpdates 401 Unauthorized"

        val scrubbed = PiiEngine.scrubForLogs(raw)

        assertFalse("令牌段仍可见：$scrubbed", scrubbed.contains("AAHsecretTOKEN-abc"))
        assertFalse("数字 id 与冒号段一起隐去：$scrubbed", scrubbed.contains("123456789:"))
        assertTrue("要保留可定位信息（接口名与状态码）：$scrubbed", scrubbed.contains("getUpdates"))
        assertTrue("要写明是已隐藏而不是删掉整句：$scrubbed", scrubbed.contains("bot<已隐藏>"))
    }

    @Test
    fun memoryEngineRemovesResidualTokensAfterUnmask() {
        val masked = PiiEngine.maskMemory("电话 13800138000")
        val restored = PiiEngine.unmaskMemory("[PHONE_1] [PHONE_2]", masked.map)
        assertTrue(restored.contains("13800138000"))
        assertFalse(restored.contains("[PHONE_2]"))
    }
}
