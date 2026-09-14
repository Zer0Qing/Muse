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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * F-17: JS 桥接执行器（__bridge__）。
 *
 * SKILLPKG.md 承诺 JS 函数可通过返回桥接请求对象获得 IO 能力（读网络等）：
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

    /** 桥接专用 HTTP 客户端（与主工具链解耦，QingTian 网络栈不可注入时的最小回退）。 */
    private val bridgeHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            // B-2: 禁用自动重定向 — 否则 OkHttp 默认 followRedirects(true) 会跟随到内网,
            //   绕过 validatePublicUrl 只校验初始 URL 的 SSRF 防护。改为逐跳手动校验 Location。
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

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
    suspend fun tryHandle(valueJson: String): HandleResult {
        val obj = runCatching {
            AppJson.parseToJsonElement(valueJson) as? JsonObject
        }.getOrNull() ?: return HandleResult.NotBridge
        val isBridge = (obj["__bridge__"] as? JsonPrimitive)?.booleanOrNull == true
        if (!isBridge) return HandleResult.NotBridge

        val action = (obj["action"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("__bridge__ 缺少 action 字段")
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
     * B-2: OkHttp 默认 followRedirects(true) 会绕过 validatePublicUrl 只校验初始 URL 的
     * 限制,跟随到内网地址(SSRF 重定向链)。此处禁用自动重定向,对 3xx 响应的 Location
     * 逐跳调用 validatePublicUrl 校验(限 [MAX_REDIRECTS] 跳),任一跳非公网地址即返回
     * 失败。响应体经 [readLimitedBody] 限流读取,避免整读后再 take 导致 OOM。
     */
    @Suppress("TooGenericExceptionCaught") // 底层网络异常统一转为用户可见失败信息
    private fun execHttpGetWithRedirects(startUrl: String, maxSize: Int): HandleResult {
        var current = startUrl
        repeat(MAX_REDIRECTS + 1) {
            if (!validatePublicUrl(current)) {
                return HandleResult.Failure("http_get 拒绝访问内网/非公网地址: $current")
            }
            try {
                bridgeHttpClient.newCall(Request.Builder().url(current).get().build()).execute().use { resp ->
                    val status = resp.code
                    if (status in 300..399) {
                        val location = resp.header("Location")?.trim()?.takeIf { it.isNotBlank() }
                            ?: return HandleResult.Failure("http_get 重定向($status)缺少 Location 头")
                        val resolved = URI(current).resolve(location).toString()
                        if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) {
                            return HandleResult.Failure("http_get 重定向目标协议非法: $resolved")
                        }
                        current = resolved
                    } else {
                        val body = readLimitedBody(resp.body, maxSize)
                        return HandleResult.Output(
                            buildJsonObject {
                                put("status", JsonPrimitive(status))
                                put("body", JsonPrimitive(body))
                            }.toString(),
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.w(TAG, "http_get 桥接失败: ${e.message}")
                return HandleResult.Failure("http_get 请求失败: ${e.message ?: "网络异常"}")
            }
        }
        return HandleResult.Failure("http_get 重定向超过 $MAX_REDIRECTS 跳上限")
    }

    /**
     * 限流读取响应体:至多读取 [maxBytes] 字节即停止,避免整读超大响应导致 OOM(B-3)。
     * 空响应体返回空串。
     */
    private fun readLimitedBody(body: okhttp3.ResponseBody?, maxBytes: Int): String {
        if (body == null) return ""
        return body.byteStream().use { input ->
            val buffer = ByteArray(maxBytes)
            var total = 0
            var n = input.read(buffer, total, maxBytes - total)
            while (n != -1 && total < maxBytes) {
                total += n
                if (total >= maxBytes) break
                n = input.read(buffer, total, maxBytes - total)
            }
            buffer.copyOf(total)
        }.toString(Charsets.UTF_8)
    }

    /**
     * SSRF 防护：拒绝指向私网/回环/链路本地/组播地址的 URL。
     * 解析 DNS 后二次校验 IP（防 DNS rebinding），与既有 http_get / web_fetch 语义一致。
     * 解析失败一律视为不可信地址（fail-closed），故 catch 直接返回 false 而非抛错。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ComplexCondition")
    private fun validatePublicUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost") return false
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            return false
        }
        return addresses.all { addr ->
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return@all false
            }
            // IPv6 私网 fc00::/7（InetAddress.isSiteLocalAddress 对 IPv6 返回 false，需手动判断）
            if (addr is Inet6Address) {
                val bytes = addr.address
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return@all false
            }
            true
        }
    }
}