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
    fun memoryEngineRemovesResidualTokensAfterUnmask() {
        val masked = PiiEngine.maskMemory("电话 13800138000")
        val restored = PiiEngine.unmaskMemory("[PHONE_1] [PHONE_2]", masked.map)
        assertTrue(restored.contains("13800138000"))
        assertFalse(restored.contains("[PHONE_2]"))
    }
}
