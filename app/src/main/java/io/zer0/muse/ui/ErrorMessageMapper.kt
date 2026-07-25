package io.zer0.muse.ui

import android.content.Context
import io.zer0.muse.R
import io.zer0.common.ErrorMessage

/**
 * ErrorMessage → 用户可见本地化字符串的适配层。
 *
 * 职责:
 *  - 在 app 模块(common 模块无 Android 依赖)桥接 [ErrorMessage] 与 [R.string]
 *  - 支持带参数的错误(如 [ErrorMessage.ApiError.RATE_LIMIT] 的 retryAfter)
 *  - 默认 locale 缺失时回退到 [ErrorMessage.defaultMessage](英文兜底)
 *
 * 调用方:
 *  - ViewModel / Activity / Fragment 持有 [Context] 时调用 [toLocalizedString]
 *  - 不持有 [Context] 的纯 Kotlin 类(如 ai / memory 模块)只产出 [ErrorMessage],
 *    由上层 ViewModel 在 catch / Result.Error 分支调用本扩展
 *
 * 设计原则:
 *  - 单一映射函数,使用 exhaustive when(Kotlin 编译器强制覆盖所有 [ErrorMessage] 子类)
 *  - 新增 [ErrorMessage] 子类后必须在此补对应 when 分支,否则编译失败(强制同步)
 *  - 字符串资源命名约定:`error_<code>`(与 [ErrorMessage.code] 一一对应)
 */
fun ErrorMessage.toLocalizedString(context: Context): String = when (this) {
    // ── 网络错误 ──
    ErrorMessage.NetworkError.TIMEOUT ->
        context.getString(R.string.error_network_timeout)
    ErrorMessage.NetworkError.NO_CONNECTION ->
        context.getString(R.string.error_network_no_connection)
    ErrorMessage.NetworkError.DNS_FAILED ->
        context.getString(R.string.error_network_dns_failed)

    // ── 鉴权错误 ──
    ErrorMessage.AuthError.INVALID_KEY ->
        context.getString(R.string.error_auth_invalid_key)
    ErrorMessage.AuthError.EXPIRED ->
        context.getString(R.string.error_auth_expired)
    ErrorMessage.AuthError.UNAUTHORIZED ->
        context.getString(R.string.error_auth_unauthorized)

    // ── API 服务错误 ──
    is ErrorMessage.ApiError.RATE_LIMIT -> {
        // 带 retryAfter 时格式化为"请 N 秒后重试",否则用无参变体
        val seconds = this.retryAfter
        if (seconds != null && seconds > 0) {
            context.getString(R.string.error_api_rate_limit, seconds)
        } else {
            context.getString(R.string.error_api_rate_limit_nodelay)
        }
    }
    ErrorMessage.ApiError.SERVER_ERROR ->
        context.getString(R.string.error_api_server_error)
    ErrorMessage.ApiError.MODEL_NOT_FOUND ->
        context.getString(R.string.error_api_model_not_found)
    ErrorMessage.ApiError.CONTEXT_LENGTH ->
        context.getString(R.string.error_api_context_length)

    // ── 存储错误 ──
    ErrorMessage.StorageError.DB_FULL ->
        context.getString(R.string.error_storage_db_full)
    ErrorMessage.StorageError.IO_ERROR ->
        context.getString(R.string.error_storage_io_error)
    ErrorMessage.StorageError.MIGRATION_FAILED ->
        context.getString(R.string.error_storage_migration_failed)

    // ── 校验错误 ──
    ErrorMessage.ValidationError.EMPTY_INPUT ->
        context.getString(R.string.error_validation_empty_input)
    ErrorMessage.ValidationError.INVALID_FORMAT ->
        context.getString(R.string.error_validation_invalid_format)

    // ── 通用兜底 ──
    ErrorMessage.GenericError.UNKNOWN ->
        context.getString(R.string.error_generic_unknown)
}
