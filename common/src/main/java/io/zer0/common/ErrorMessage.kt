package io.zer0.common

/**
 * 跨模块错误代码枚举(无 Android Context 依赖)
 *
 * ============================================================================
 * i18n 迁移指南(必读)
 * ============================================================================
 *
 * 背景:
 *   v1.90 在 ai / memory / app 三层共留下约 800 处 `// TODO i18n` 标注,
 *   根因是 ai / memory 是独立 Gradle 模块,ViewModel 无 context,无法直接引用
 *   app 的 R.string,导致硬编码中文消息散落各处。
 *
 * 本文件作用:
 *   提供"错误代码 → UI 字符串"解耦的骨架。所有跨模块错误统一表达为
 *   [ErrorMessage] 子类,仅携带稳定 [code] + 英文兜底 [defaultMessage] +
 *   可选 [metadata],不引用任何 Android / R 资源。
 *
 * 三层架构:
 *   1. data / ai / memory 层:抛出或返回 [ErrorMessage],不构造用户文案
 *   2. app/ui 层:调用 [io.zer0.muse.ui.ErrorMessageMapper.toLocalizedString]
 *      将 [ErrorMessage] 翻译为当前 locale 的字符串(经 R.string)
 *   3. ViewModel:接住 [ErrorMessage] 后通过 `appContext.getString(R.string.xxx)`
 *      渲染给 UI,无需向上冒泡硬编码字符串
 *
 * 迁移步骤(对每个标 `// TODO i18n` 的位置):
 *   a. 识别现有硬编码中文错误消息是哪一类(网络 / 鉴权 / API / 存储 / 校验 / 通用)
 *   b. 替换为对应 [ErrorMessage] 子类(如 `ErrorMessage.NetworkError.TIMEOUT`)
 *   c. 在 app/src/main/res/values/strings_errors.xml(及 values-en / values-ja
 *      等已有 locale)中补对应 error_xxx 字符串
 *   d. 在 [ErrorMessageMapper] 中补映射条目
 *   e. 删除源代码处的 `// TODO i18n` 标注
 *
 * 注意事项:
 *   - [code] 一旦发布不可变更(UI / 日志 / 埋点 / 服务端联调都会引用)
 *   - [defaultMessage] 必须为英文(用作品质兜底,避免 locale 缺失时显示空白)
 *   - 带可变参数的错误(如 RATE_LIMIT 的 retryAfter)用 data class 而非 object
 *   - 与 [io.zer0.common.ErrorCode] 旧枚举共存:新代码优先用 [ErrorMessage],
 *     旧枚举将在全部 TODO 清理完毕后废弃(详见 [ErrorCode] 顶部注释)
 * ============================================================================
 */
sealed class ErrorMessage {

    /** 稳定错误代码(不可变,用作日志 / 埋点 / UI 映射键)。 */
    abstract val code: String

    /** 英文兜底消息(locale 资源缺失时使用)。 */
    abstract val defaultMessage: String

    /** 可选元数据(如 RATE_LIMIT 的 retryAfter 秒数,不参与 code 唯一性)。 */
    open val metadata: Map<String, String> get() = emptyMap()

    /**
     * 日志友好的字符串表示([code] + [defaultMessage] + [metadata]),
     * 给 `Logger.w(TAG, errorMessage.toLogString(), t)` 等场景使用,
     * 全英文,无 locale 依赖,可在任何线程 / 模块调用。
     */
    fun toLogString(): String = buildString {
        append("[ERR:")
        append(code)
        append("] ")
        append(defaultMessage)
        if (metadata.isNotEmpty()) {
            append(" | ")
            metadata.forEach { (k, v) ->
                append(k)
                append('=')
                append(v)
                append(';')
            }
        }
    }

    // ── 网络错误 ──────────────────────────────────────────────────────────

    /** 网络层错误:连接超时、断网、DNS 失败等传输层问题。 */
    sealed class NetworkError : ErrorMessage() {
        /** 请求超时(读 / 写 / 连接阶段)。 */
        data object TIMEOUT : NetworkError() {
            override val code = "network_timeout"
            override val defaultMessage = "Network request timed out, please retry"
        }

        /** 无可用网络连接(飞行模式 / WiFi 断开)。 */
        data object NO_CONNECTION : NetworkError() {
            override val code = "network_no_connection"
            override val defaultMessage = "No network connection available"
        }

        /** DNS 解析失败(域名无法解析为目标 IP)。 */
        data object DNS_FAILED : NetworkError() {
            override val code = "network_dns_failed"
            override val defaultMessage = "DNS resolution failed"
        }
    }

    // ── 鉴权错误 ──────────────────────────────────────────────────────────

    /** 鉴权层错误:API Key / Token 失效或权限不足。 */
    sealed class AuthError : ErrorMessage() {
        /** API Key 无效或格式错误。 */
        data object INVALID_KEY : AuthError() {
            override val code = "auth_invalid_key"
            override val defaultMessage = "Invalid API key, please check model service settings"
        }

        /** Token / Key 已过期。 */
        data object EXPIRED : AuthError() {
            override val code = "auth_expired"
            override val defaultMessage = "Authentication token expired"
        }

        /** 已认证但无权访问目标资源(403)。 */
        data object UNAUTHORIZED : AuthError() {
            override val code = "auth_unauthorized"
            override val defaultMessage = "Not authorized to access this resource"
        }
    }

    // ── API 服务错误 ─────────────────────────────────────────────────────

    /** 上游 API 服务返回的业务层错误(HTTP 4xx/5xx 透传或语义映射)。 */
    sealed class ApiError : ErrorMessage() {
        /** 触发速率限制(429)。携带 [retryAfter] 建议重试等待秒数。 */
        data class RATE_LIMIT(
            /** 建议的重试等待秒数(若上游未返回则为 null)。 */
            val retryAfter: Long? = null,
        ) : ApiError() {
            override val code = "api_rate_limit"
            override val defaultMessage = "Too many requests, please slow down"
            override val metadata: Map<String, String>
                get() = if (retryAfter != null) mapOf("retryAfter" to retryAfter.toString()) else emptyMap()
        }

        /** 上游服务器内部错误(5xx)。 */
        data object SERVER_ERROR : ApiError() {
            override val code = "api_server_error"
            override val defaultMessage = "Upstream service returned an error"
        }

        /** 指定的模型在 Provider 不存在或不支持。 */
        data object MODEL_NOT_FOUND : ApiError() {
            override val code = "api_model_not_found"
            override val defaultMessage = "Requested model is not available"
        }

        /** 输入超出模型的上下文长度上限。 */
        data object CONTEXT_LENGTH : ApiError() {
            override val code = "api_context_length"
            override val defaultMessage = "Input exceeds the model context length limit"
        }
    }

    // ── 存储错误 ──────────────────────────────────────────────────────────

    /** 本地存储层错误:数据库 / 文件 IO / 迁移失败。 */
    sealed class StorageError : ErrorMessage() {
        /** 数据库已满(磁盘空间不足或 DB 配额耗尽)。 */
        data object DB_FULL : StorageError() {
            override val code = "storage_db_full"
            override val defaultMessage = "Database is full"
        }

        /** 通用文件 / DB IO 错误(读写失败、文件锁占用等)。 */
        data object IO_ERROR : StorageError() {
            override val code = "storage_io_error"
            override val defaultMessage = "Local storage read/write failed"
        }

        /** 数据库 schema 迁移失败(版本升级 SQL 出错)。 */
        data object MIGRATION_FAILED : StorageError() {
            override val code = "storage_migration_failed"
            override val defaultMessage = "Database migration failed"
        }
    }

    // ── 校验错误 ──────────────────────────────────────────────────────────

    /** 输入校验错误:用户输入不满足前置条件。 */
    sealed class ValidationError : ErrorMessage() {
        /** 输入为空或仅含空白字符。 */
        data object EMPTY_INPUT : ValidationError() {
            override val code = "validation_empty_input"
            override val defaultMessage = "Input cannot be empty"
        }

        /** 输入格式不合法(参数类型 / 长度 / 模式不匹配)。 */
        data object INVALID_FORMAT : ValidationError() {
            override val code = "validation_invalid_format"
            override val defaultMessage = "Input format is invalid"
        }
    }

    // ── 通用兜底 ──────────────────────────────────────────────────────────

    /** 兜底错误:无法归入上述任一分类的未知错误。 */
    sealed class GenericError : ErrorMessage() {
        data object UNKNOWN : GenericError() {
            override val code = "generic_unknown"
            override val defaultMessage = "An unknown error occurred"
        }
    }
}

/**
 * 把 [Throwable] 启发式映射为 [ErrorMessage]。
 *
 * 用于在尚未改造的旧 catch 块中临时把异常转换为 [ErrorMessage],
 * 后续逐步替换为显式的 [ErrorMessage] 构造。仅基于异常类型做粗粒度归类,
 * 不保证语义精确——精确定义请直接返回具体 [ErrorMessage] 子类。
 */
fun Throwable.toErrorMessage(): ErrorMessage = when (this) {
    is java.net.UnknownHostException -> ErrorMessage.NetworkError.DNS_FAILED
    is java.net.SocketTimeoutException -> ErrorMessage.NetworkError.TIMEOUT
    is java.net.ConnectException -> ErrorMessage.NetworkError.NO_CONNECTION
    is android.database.sqlite.SQLiteFullException -> ErrorMessage.StorageError.DB_FULL
    is android.database.sqlite.SQLiteException -> ErrorMessage.StorageError.IO_ERROR
    is IllegalArgumentException -> ErrorMessage.ValidationError.INVALID_FORMAT
    else -> {
        val msg = message.orEmpty()
        when {
            msg.contains("timeout", ignoreCase = true) -> ErrorMessage.NetworkError.TIMEOUT
            msg.contains("unauthorized", ignoreCase = true) ||
                msg.contains("401", ignoreCase = true) -> ErrorMessage.AuthError.UNAUTHORIZED
            msg.contains("rate limit", ignoreCase = true) ||
                msg.contains("429", ignoreCase = true) -> ErrorMessage.ApiError.RATE_LIMIT()
            msg.contains("context length", ignoreCase = true) -> ErrorMessage.ApiError.CONTEXT_LENGTH
            else -> ErrorMessage.GenericError.UNKNOWN
        }
    }
}
