package io.zer0.muse.web.a2a

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-20261011-A2A-PROTOCOL: JSON-RPC 层的参数/错误码语义。
 *
 * 缺陷背景（.audit REPORT_DOCS_TESTS F2）：
 *  - GetTask / CancelTask 读的是 JSON-RPC **信封 id**，而 A2A 规范把 task id
 *    放在 `params.id` —— 正常客户端永远取不回任务；信封 id 与 task id 恰好相同
 *    只是"客户端照抄"的巧合。
 *  - 一切解码失败（含"合法 JSON 但缺 method"）都被映射为 PARSE_ERROR(-32700)，
 *    而 A2AErrors.INVALID_REQUEST(-32600) 是死码，不符合 JSON-RPC 2.0：
 *    解析失败才是 -32700，请求结构非法应是 -32600。
 */
class A2aJsonRpcSemanticsTest {

    private val json = Json

    @Test
    fun `task id is read from params not from the envelope id`() {
        val params = json.parseToJsonElement("""{"id":"task-from-params"}""")
        assertEquals(
            "A2A 规范把 task id 放在 params.id;读信封 id 会让正常客户端取不回任务",
            "task-from-params",
            A2aJsonRpc.taskIdFrom(params, envelopeId = JsonPrimitive("envelope-id")),
        )
    }

    @Test
    fun `task id falls back to envelope id for legacy clients`() {
        // 兼容 v2.5.8 已发出的既有调用:信封 id 即 task id 的旧用法
        assertEquals(
            "legacy-envelope",
            A2aJsonRpc.taskIdFrom(params = null, envelopeId = JsonPrimitive("legacy-envelope")),
        )
    }

    @Test
    fun `missing method on valid json is invalid request not parse error`() {
        val decision = A2aJsonRpc.classify("""{"jsonrpc":"2.0","id":1}""")
        assertEquals(
            "合法 JSON 但缺 method 属请求结构非法(-32600),不是解析失败(-32700)",
            A2aErrors.INVALID_REQUEST,
            decision.errorCode,
        )
    }

    @Test
    fun `valid non-object json is invalid request not parse error`() {
        val decision = A2aJsonRpc.classify("[]")
        assertEquals(A2aErrors.INVALID_REQUEST, decision.errorCode)
    }

    @Test
    fun `malformed json is a parse error`() {
        val decision = A2aJsonRpc.classify("""{"jsonrpc":""")
        assertEquals(A2aErrors.PARSE_ERROR, decision.errorCode)
    }

    @Test
    fun `valid send message request is accepted and keeps the envelope id`() {
        val decision = A2aJsonRpc.classify(
            """{"jsonrpc":"2.0","id":7,"method":"message/send","params":{"message":{"parts":[{"kind":"text","text":"hi"}]}}}""",
        )
        assertNull("合法请求不应产生错误码", decision.errorCode)
        assertEquals("message/send", decision.method)
        assertEquals(JsonPrimitive(7), decision.envelopeId)
    }

    @Test
    fun `unknown method is rejected with method not found`() {
        val decision = A2aJsonRpc.classify("""{"jsonrpc":"2.0","id":1,"method":"tasks/nope"}""")
        assertEquals(
            "未知方法应由分发层返回 -32601(此处只校验结构,不应提前判定为解析错误)",
            null,
            decision.errorCode,
        )
        assertTrue(decision.method == "tasks/nope")
    }

    @Test
    fun `empty body is a parse error with null envelope id`() {
        val decision = A2aJsonRpc.classify("")
        assertEquals(A2aErrors.PARSE_ERROR, decision.errorCode)
        assertEquals(JsonNull, decision.envelopeId ?: JsonNull)
    }

    @Test
    fun `blank task id is treated as missing`() {
        val params = buildJsonObject { put("id", "   ") }
        assertNull(A2aJsonRpc.taskIdFrom(params, envelopeId = null))
    }
}
