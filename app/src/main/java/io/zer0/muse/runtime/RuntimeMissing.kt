package io.zer0.muse.runtime

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 工具层「缺运行环境」的结构化错误。
 *
 * 为什么不用一句人话：宿主需要靠这个错误码插安装卡片、并把该次调用从「失败计数」里摘出去。
 * 模型看到的是一段能照做的话，宿主看到的是可判定的字段。
 */
object RuntimeMissing {

    /** 错误码。工具编排层按它识别。 */
    const val CODE = "runtime_missing"

    class RequiresInstallException : IllegalStateException(CODE)

    /**
     * 生成结构化错误 JSON。
     *
     * @param installable 当前 ABI 有没有可下载的包（没有的话只能引导用户换设备或换源）
     */
    fun toJson(
        id: RuntimeId = RuntimeId.UBUNTU_NODE,
        downloadBytes: Long = 0,
        installedBytes: Long = 0,
        installable: Boolean = true,
    ): String = buildJsonObject {
        put("error", JsonPrimitive(CODE))
        put("runtime", JsonPrimitive(id.manifestKey))
        put("installable", JsonPrimitive(installable))
        put("download_bytes", JsonPrimitive(downloadBytes))
        put("installed_bytes", JsonPrimitive(installedBytes))
        // 模型看到的是 message 里的人话；宿主只认 error 字段。两者同源，避免各写一份。
        put("message", JsonPrimitive(guidance(id)))
    }.toString()

    /** 只按 JSON 字段识别错误码，也支持被 JSON 字符串包裹的工具输出。 */
    fun isRuntimeMissingPayload(output: String): Boolean {
        val root = runCatching { Json.parseToJsonElement(output) }.getOrNull()
        if (root != null && containsRuntimeMissing(root)) return true
        return containsEmbeddedRuntimeMissing(output)
    }

    private fun containsRuntimeMissing(element: JsonElement): Boolean = when (element) {
        is JsonObject -> {
            (element["error"] as? JsonPrimitive)?.contentOrNull == CODE ||
                element.values.any(::containsRuntimeMissing)
        }
        is JsonArray -> element.any(::containsRuntimeMissing)
        is JsonPrimitive -> {
            if (!element.isString) {
                false
            } else {
                val nested = element.contentOrNull
                nested?.let { runCatching { containsRuntimeMissing(Json.parseToJsonElement(it)) }.getOrDefault(false) }
                    ?: false
            }
        }
    }

    private fun containsEmbeddedRuntimeMissing(text: String): Boolean {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        text.forEachIndexed { index, character ->
            if (start < 0) {
                if (character == '{' || character == '[') {
                    start = index
                    depth = 1
                }
                return@forEachIndexed
            }
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (character) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) {
                        val embedded = runCatching {
                            Json.parseToJsonElement(text.substring(start, index + 1))
                        }.getOrNull()
                        if (embedded != null && containsRuntimeMissing(embedded)) return true
                        start = -1
                    }
                }
            }
        }
        return false
    }

    /** 给模型的引导语：一句话说清缺什么、装完能干什么、去哪装。 */
    fun guidance(id: RuntimeId = RuntimeId.UBUNTU_NODE): String =
        "这台手机上还没有装「${id.displayName}」，所以脚本类工具暂时用不了。" +
            "装上之后就能跑 Node 脚本、Python 和终端命令。你可以在下面点安装，或者到「设置 → 运行时」里装。"
}
