package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R

/**
 * P1-3b 拆域：文本/编码工具注册器。
 *
 * 从 ToolRegistry.kt 抽出的纯计算工具（URL / Base64 / 哈希 / UUID / 随机数）。
 * 只依赖 Context（字符串资源），不持有 ToolRegistry 内部状态。
 */
class EncodingToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "url_encode",
                description = "对文本进行 URL 编码。",
                parameters = mapOf("text" to "必填,要编码的文本"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                context.getString(R.string.tool_url_encoded, java.net.URLEncoder.encode(text, "UTF-8"))
            }.onError { msg, _ -> Logger.w("EncodingTools", "URL 编码失败: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_url_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "url_decode",
                description = "对 URL 编码文本进行解码。",
                parameters = mapOf("text" to "必填,要解码的文本"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                context.getString(R.string.tool_url_decoded, java.net.URLDecoder.decode(text, "UTF-8"))
            }.onError { msg, _ -> Logger.w("EncodingTools", "URL 解码失败: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_url_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "base64_encode",
                description = "对文本进行 Base64 编码。",
                parameters = mapOf("text" to "必填,要编码的文本"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                context.getString(
                    R.string.tool_base64_encoded,
                    android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP),
                )
            }.onError { msg, _ -> Logger.w("EncodingTools", "Base64 编码失败: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_url_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "base64_decode",
                description = "对 Base64 文本进行解码。",
                parameters = mapOf("text" to "必填,要解码的 Base64 文本"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                val bytes = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                context.getString(R.string.tool_base64_decoded, String(bytes, Charsets.UTF_8))
            }.onError { msg, _ -> Logger.w("EncodingTools", "Base64 解码失败: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_base64_failed)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "hash_text",
                description = "计算文本哈希值。支持 MD5、SHA-1、SHA-256,默认 SHA-256。",
                parameters = mapOf(
                    "text" to "必填,要哈希的文本",
                    "algorithm" to "可选,MD5/SHA-1/SHA-256,默认 SHA-256",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_hash_missing)
            val algo = args["algorithm"]?.uppercase() ?: "SHA-256"
            if (algo !in setOf("MD5", "SHA-1", "SHA-256")) {
                return@register context.getString(R.string.tool_hash_unsupported, algo)
            }
            resultOf {
                val digest = java.security.MessageDigest.getInstance(algo)
                val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                context.getString(R.string.tool_hash_result, algo, hash)
            }.onError { msg, _ -> Logger.w("EncodingTools", "哈希计算失败: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_hash_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_uuid",
                description = "生成一个随机的 UUID(通用唯一识别码)。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            context.getString(R.string.tool_uuid_result, java.util.UUID.randomUUID().toString())
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "random_number",
                description = "生成指定范围内的随机整数(包含边界)。",
                parameters = mapOf(
                    "min" to "可选,最小值,默认 0",
                    "max" to "可选,最大值,默认 100",
                ),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("min" to "integer", "max" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            var min = args["min"]?.toIntOrNull() ?: 0
            var max = args["max"]?.toIntOrNull() ?: 100
            if (min > max) {
                min = max.also { max = min }
            }
            context.getString(R.string.tool_random_number_result, kotlin.random.Random.nextInt(min, max + 1))
        }
    }
}
