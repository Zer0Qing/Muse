package io.zer0.muse.tools.script

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * F-17: JS 桥接执行器（__bridge__）。
 *
 * JS 函数可通过返回桥接请求对象获得 IO 能力（读网络等）：
 * ```js
 * return { __bridge__: true, action: "http_get", params: { url: "..." } };
 * ```
 * WebView 沙盒禁用了 fetch/XHR（见 [io.zer0.muse.tools.JsSandbox.INIT_JS]），因此
 * Kotlin 侧在脚本执行结果处截获该请求，审计后执行对应的安全实现，再把结果作为技能
 * 返回值返回（request-response 模式，不破坏沙盒——所有 IO 都经过 Kotlin 侧）。
 *
 * 当前仅支持两种最小 action（其余返回"不支持"错误）：
 *  - `echo`：原样回显 params，用于验证桥接通路
 *  - `http_get`：发起带 SSRF 防护的 HTTP GET，见 [validatePublicUrl]
 *
 * 调用方（skill 执行结果处理处）先调用 [tryHandle]：若返回值不含 `__bridge__`
 * 字段则返回 [HandleResult.NotBridge]（按普通结果处理）；否则返回执行产物/错误。
 */
object SkillBridge {

    private const val TAG = "SkillBridge"

    /** http_get 响应体读取硬上限(1MB),防脚本拉取超大内容撑爆内存(OOM 防护)。 */
    private const val MAX_BODY_BYTES = 1024 * 1024
    /** B-2: 手动跟随重定向的最大跳数,防止重定向链无限延伸。 */
    private const val MAX_REDIRECTS = 5

    /**
     * 桥接专用 HTTP 出口。DNS 解析、连接地址校验和逐跳重定向都在该出口内完成。
     * 不复用其他网络出口，避免把 DNS 策略泛化到聊天、插件或 WebView 流量。
     */
    private val bridgeHttpClient: SkillBridgeHttpClient by lazy { SkillBridgeHttpClient() }

    /**
     * 桥接处理结果密封类。
     *
     *  - [NotBridge]：返回值不含 `__bridge__`，调用方按普通脚本结果继续处理
     *  - [Output]：桥接请求已执行，产出结果 JSON 字符串
     *  - [Failure]：桥接请求解析或执行失败（含"action 不支持"）
     */
    sealed class HandleResult {
        object NotBridge : HandleResult()
        data class Output(val json: String) : HandleResult()
        data class Failure(val message: String) : HandleResult()
    }

    /**
     * 尝试处理一个 skill 脚本的执行返回值。
     *
     * @param valueJson 脚本返回值的 JSON 字符串（SkillEngineResult.Success.valueJson）
     * @return 见 [HandleResult]
     */
    suspend fun tryHandle(
        valueJson: String,
        allowedActions: Set<String> = setOf("echo", "http_get"),
    ): HandleResult {
        val obj = runCatching {
            AppJson.parseToJsonElement(valueJson) as? JsonObject
        }.getOrNull() ?: return HandleResult.NotBridge
        val isBridge = (obj["__bridge__"] as? JsonPrimitive)?.booleanOrNull == true
        if (!isBridge) return HandleResult.NotBridge

        val action = (obj["action"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("__bridge__ 缺少 action 字段")
        if (action !in allowedActions) {
            return HandleResult.Failure("__bridge__ action '$action' 未获得插件声明的能力")
        }
        val params = obj["params"] as? JsonObject ?: buildJsonObject { }
        return when (action) {
            "echo" -> HandleResult.Output(params.toString())
            "http_get" -> withContext(Dispatchers.IO) { execHttpGet(params) }
            else -> HandleResult.Failure("__bridge__ action '$action' 不受支持（仅支持 http_get / echo）")
        }
    }

    /** 执行 http_get：SSRF 防护(含重定向逐跳校验) + 响应体上限。 */
    private fun execHttpGet(params: JsonObject): HandleResult {
        val url = (params["url"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("http_get 缺少 url 参数")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return HandleResult.Failure("http_get url 仅支持 http/https 协议")
        }
        // 响应体读取上限,默认 1MB;用户请求值收敛到 [1, MAX_BODY_BYTES],
        // 防脚本拉取超大内容整读后撑爆上下文/内存(B-3)
        val maxSize = (params["max_size"] as? JsonPrimitive)?.contentOrNull
            ?.toIntOrNull()?.coerceIn(1, MAX_BODY_BYTES) ?: MAX_BODY_BYTES
        return execHttpGetWithRedirects(url, maxSize)
    }

    /**
     * 手动跟随重定向的 http_get 执行。
     *
     * DNS 解析与连接级校验由 [SkillBridgeHttpClient] 完成；它为每一跳固定同一份已校验
     * 地址列表，避免“先校验 getAllByName、再由系统重新解析”的 TOCTOU。重定向仍由客户端
     * 禁用自动跟随并逐跳校验，任一跳失败即 fail-closed。
     */
    @Suppress("TooGenericExceptionCaught") // 底层网络异常统一转为用户可见失败信息
    private fun execHttpGetWithRedirects(startUrl: String, maxSize: Int): HandleResult {
        return try {
            val result = bridgeHttpClient.get(startUrl, maxSize, MAX_REDIRECTS)
            HandleResult.Output(
                buildJsonObject {
                    put("status", JsonPrimitive(result.status))
                    put("body", JsonPrimitive(result.body))
                }.toString(),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "http_get 桥接失败: ${e.message}")
            val message = if (e is SkillBridgeHttpClient.PinnedAddressException) {
                "http_get 拒绝访问内网/非公网地址: ${e.url}"
            } else {
                "http_get 请求失败: ${e.message ?: "网络异常"}"
            }
            HandleResult.Failure(message)
        }
    }
}