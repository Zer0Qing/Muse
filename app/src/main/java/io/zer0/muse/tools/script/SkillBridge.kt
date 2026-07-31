package io.zer0.muse.tools.script

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Skill 桥接接口 (P3-1)。
 *
 * 定义 JS 沙盒中可调用的原生能力。由于 [JsSandbox] 出于安全考虑禁用了
 * fetch/XHR/WebSocket（见 JsSandbox.INIT_JS），JS 脚本无法直接进行 IO 操作。
 *
 * 本桥接采用 **request-response 模式**：
 *  1. JS 函数返回一个 [BridgeRequest]（描述需要执行的 IO 操作）
 *  2. Kotlin 侧（SkillExecutor）执行请求，获得 [BridgeResponse]
 *  3. Kotlin 侧将结果注入 JS，继续执行
 *
 * 这种模式不破坏 JsSandbox 的安全模型（无需 addJavascriptInterface），
 * 同时让 JS skill 能复用 Kotlin 已有的安全实现（read_file / http_get 等）。
 *
 * JS 侧用法示例：
 * ```js
 * function fetchWeather(city) {
 *     // 返回桥接请求，由 Kotlin 侧执行
 *     return { bridge: "http_get", url: "https://api.weather.com/" + city };
 * }
 * ```
 *
 * Kotlin 侧处理：
 * ```kotlin
 * val result = engine.callFunction(code, "fetchWeather", """["Beijing"]""")
 * if (result is Success) {
 *     val request = parseBridgeRequest(result.valueJson)
 *     if (request != null) {
 *         val response = executeBridge(request)
 *         // 将 response 注入 JS 继续执行...
 *     }
 * }
 * ```
 */
interface SkillBridge {

    /**
     * 执行桥接请求。
     *
     * @param request 桥接请求描述
     * @return 桥接响应（成功包含数据，失败包含错误信息）
     */
    suspend fun execute(request: BridgeRequest): BridgeResponse

    /**
     * 查询此桥接支持的能力列表（用于 skillpkg 声明所需权限）。
     */
    fun supportedOperations(): Set<String>
}

/**
 * 桥接请求 — JS 函数返回此对象请求 Kotlin 侧执行 IO。
 *
 * @param operation 操作名（如 "read_file" / "http_get" / "write_file"）
 * @param params 操作参数（JsonObject，具体结构由操作定义）
 */
@Serializable
data class BridgeRequest(
    val operation: String,
    val params: JsonObject,
) {
    companion object {
        /** JS 返回值中标记桥接请求的 magic key。 */
        const val BRIDGE_MARKER = "__bridge__"
    }
}

/**
 * 桥接响应密封类（符合 AGENTS.md §5 类型安全规范）。
 */
sealed class BridgeResponse {
    /** 成功。 */
    data class Ok(val data: String) : BridgeResponse()

    /** 失败。 */
    data class Err(val message: String) : BridgeResponse()
}
