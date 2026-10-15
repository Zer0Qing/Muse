package io.zer0.muse.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * v2.6.2（Codex 登录修复）: JWT claim 解析回归测试。
 *
 * 锁死两个 bug：
 *  1. `chatgpt_account_id` 嵌套在 `https://api.openai.com/auth` 命名空间下，
 *     不是顶层 —— 旧实现只读顶层，恒为 null，导致 ChatGPT-Account-ID 头从未写入。
 *  2. base64url payload 无 padding，旧实现未补齐，Android Base64 解码失败。
 */
class ChatGptAccountIdParseTest {

    /** 用给定 payload JSON 造一个假 JWT（签名段占位，解析不校验签名）。 */
    private fun jwtOf(payloadJson: String): String {
        val header = """{"alg":"RS256","typ":"JWT"}"""
        fun b64(s: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
        return "${b64(header)}.${b64(payloadJson)}.fakesignature"
    }

    @Test
    fun `account id is read from nested auth namespace`() {
        val jwt = jwtOf(
            """{"sub":"u1","https://api.openai.com/auth":{"chatgpt_account_id":"acct-123","chatgpt_plan_type":"plus"}}""",
        )
        assertEquals("acct-123", parseChatGptAccountId(jwt))
    }

    @Test
    fun `top level claim is still honored as fallback`() {
        val jwt = jwtOf("""{"chatgpt_account_id":"acct-top"}""")
        assertEquals("acct-top", parseChatGptAccountId(jwt))
    }

    @Test
    fun `nested wins over top level`() {
        val jwt = jwtOf(
            """{"chatgpt_account_id":"top","https://api.openai.com/auth":{"chatgpt_account_id":"nested"}}""",
        )
        assertEquals("nested", parseChatGptAccountId(jwt))
    }

    @Test
    fun `unpadded base64url payload decodes`() {
        // 构造一个长度非 4 倍数的 payload，确保 padding 补齐生效
        val jwt = jwtOf("""{"https://api.openai.com/auth":{"chatgpt_account_id":"x"}}""")
        assertEquals("x", parseChatGptAccountId(jwt))
    }

    @Test
    fun `missing claim returns null`() {
        assertNull(parseChatGptAccountId(jwtOf("""{"sub":"u1"}""")))
    }

    @Test
    fun `malformed token returns null`() {
        assertNull(parseChatGptAccountId("not-a-jwt"))
        assertNull(parseChatGptAccountId(""))
        assertNull(parseChatGptAccountId(null))
    }

    @Test
    fun `blank account id treated as absent`() {
        val jwt = jwtOf("""{"https://api.openai.com/auth":{"chatgpt_account_id":"  "}}""")
        assertNull(parseChatGptAccountId(jwt))
    }
}
