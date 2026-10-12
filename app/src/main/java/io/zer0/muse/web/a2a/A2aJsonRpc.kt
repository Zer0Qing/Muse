package io.zer0.muse.web.a2a

import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * AUD-20261011-A2A-PROTOCOL: JSON-RPC 信封解析与 task id 取值的纯逻辑(可单测)。
 *
 * 与 [A2aHandler] 的分工:本对象只负责"报文 → 结构判定",
 * 不触碰 ChatViewModel/会话,便于在无 Android 环境下锁定协议语义。
 */
internal object A2aJsonRpc {

    /** 一次请求的结构判定结果。 */
    data class Decision(
        val method: String? = null,
        val envelopeId: JsonElement? = null,
        val params: JsonObject? = null,
        val errorCode: Int? = null,
        val errorMessage: String? = null,
    )

    /**
     * 解析并判定 JSON-RPC 请求。
     *
     * 区分两类失败（JSON-RPC 2.0）：
     *  - 报文不是合法 JSON → PARSE_ERROR(-32700)
     *  - 合法 JSON 但缺 method（结构非法）→ INVALID_REQUEST(-32600)
     */
    @Suppress("ReturnCount")
    fun classify(raw: String): Decision {
        val parsed = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull()
            ?: return Decision(errorCode = A2aErrors.PARSE_ERROR, errorMessage = "invalid JSON")
        val obj = parsed as? JsonObject
            ?: return Decision(errorCode = A2aErrors.INVALID_REQUEST, errorMessage = "request must be a JSON object")
        val envelopeId = obj["id"] ?: JsonNull
        if ((obj["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") {
            return Decision(
                envelopeId = envelopeId,
                errorCode = A2aErrors.INVALID_REQUEST,
                errorMessage = "jsonrpc must be 2.0",
            )
        }
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return Decision(
                envelopeId = envelopeId,
                errorCode = A2aErrors.INVALID_REQUEST,
                errorMessage = "missing method",
            )
        val params = obj["params"] as? JsonObject
        return Decision(method = method, envelopeId = envelopeId, params = params)
    }

    /**
     * 取 task id：优先 A2A 规范的 `params.id`，回退信封 id（兼容 v2.5.8 旧调用）。
     *
     * 旧实现只读信封 id —— 按规范把 task id 放进 params 的客户端永远取不回任务。
     */
    fun taskIdFrom(params: JsonElement?, envelopeId: JsonElement?): String? {
        val fromParams = (params as? JsonObject)
            ?.get("id")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
        if (fromParams != null) return fromParams
        return (envelopeId as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
