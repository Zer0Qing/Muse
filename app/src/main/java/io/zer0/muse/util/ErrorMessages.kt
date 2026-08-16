package io.zer0.muse.util

import android.content.Context
import io.zer0.muse.R

/**
 * 解析 [io.zer0.common.ErrorCode.toMessage] 生成的 ERR_ 前缀消息并映射为本地化字符串。
 * ai/memory 模块通过 ErrorCode 枚举传递错误类型,app 层在此处解析并展示用户可读文本。
 */
object ErrorMessages {

    private val PREFIX_REGEX = Regex("^ERR_([a-z_]+)(?::(.+))?")

    /** 若消息是 ERR_ 格式则返回本地化字符串,否则返回原消息。 */
    fun resolve(context: Context, message: String): String {
        val m = PREFIX_REGEX.find(message) ?: return message
        val key = m.groupValues[1]
        val args = m.groupValues[2].split(",").filter { it.isNotEmpty() }
        val rest = message.substring(m.range.last + 1).trimStart()
        val resolved = resolveKey(context, key, args)
        // C-13: resolveKey 返回 null 表示无法本地化(见下),此时原样返回原始消息 —
        // 它仍含 ERR_ 前缀与全部参数,保证错误详情不因本地化失败而静默丢失。
        if (resolved == null) return message
        return if (rest.isNotEmpty()) "$resolved $rest" else resolved
    }

    /**
     * 解析单个 key 的本地化字符串。
     *
     * @return 本地化结果;若 key 未映射返回 "ERR_$key"(既有行为);
     *         若带参数 getString 失败(说明该 locale 译文缺少 %1$s/%1$d 格式符)返回 null,
     *         由 [resolve] 回退到原始消息。
     */
    private fun resolveKey(context: Context, key: String, args: List<String>): String? {
        val resId = mapping[key] ?: return "ERR_$key"
        return try {
            context.getString(resId, *args.toTypedArray())
        } catch (e: Exception) {
            // C-13: 非 zh 译文移除格式符后,带参数 getString 抛 NotFoundException。
            // 原实现回退到无参 getString(resId) 会静默丢弃 %1$s/%1$d 参数细节;
            // 改为返回 null 回退到原始消息,错误详情不丢失。zh 译文保留格式符,
            // 此分支不触发,zh 行为不变。
            null
        }
    }

    private val mapping: Map<String, Int> = mapOf(
        "no_provider_configured" to R.string.err_no_provider_configured,
        "no_model_selected" to R.string.err_no_model_selected,
        "auth_failed" to R.string.err_auth_failed,
        "rate_limited" to R.string.err_rate_limited,
        "request_timeout" to R.string.err_request_timeout,
        "service_unavailable" to R.string.err_service_unavailable,
        "permission_denied" to R.string.err_permission_denied,
        "invalid_response" to R.string.err_invalid_response,
        "api_error" to R.string.err_api_error,
        "resource_exhausted" to R.string.err_resource_exhausted,
        "invalid_argument" to R.string.err_invalid_argument,
        "precondition_failed" to R.string.err_precondition_failed,
        "overloaded" to R.string.err_overloaded,
        "not_found" to R.string.err_not_found,
        "stream_interrupted" to R.string.err_stream_interrupted,
        "network_error" to R.string.err_network_error,
        "image_gen_failed" to R.string.err_image_gen_failed,
        "image_response_too_large" to R.string.err_image_response_too_large,
        "image_empty_response" to R.string.err_image_empty_response,
        "image_no_results" to R.string.err_image_no_results,
        "image_unsupported_model" to R.string.err_image_unsupported_model,
        "image_reference_too_large" to R.string.err_image_reference_too_large,
        "image_reference_download_failed" to R.string.err_image_reference_download_failed,
        "image_invalid_uri" to R.string.err_image_invalid_uri,
        "image_api_key_missing" to R.string.err_image_api_key_missing,
        "vertex_ai_config_invalid" to R.string.err_vertex_ai_config_invalid,
        "vertex_ai_token_failed" to R.string.err_vertex_ai_token_failed,
        "memory_token_budget_invalid" to R.string.err_memory_token_budget_invalid,
        "memory_config_invalid" to R.string.err_memory_config_invalid,
    )
}
