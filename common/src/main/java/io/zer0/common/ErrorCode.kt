package io.zer0.common

enum class ErrorCode(val key: String) {
    NO_PROVIDER_CONFIGURED("no_provider_configured"),
    NO_MODEL_SELECTED("no_model_selected"),
    AUTH_FAILED("auth_failed"),
    RATE_LIMITED("rate_limited"),
    REQUEST_TIMEOUT("request_timeout"),
    SERVICE_UNAVAILABLE("service_unavailable"),
    PERMISSION_DENIED("permission_denied"),
    INVALID_RESPONSE("invalid_response"),
    API_ERROR("api_error"),
    RESOURCE_EXHAUSTED("resource_exhausted"),
    INVALID_ARGUMENT("invalid_argument"),
    PRECONDITION_FAILED("precondition_failed"),
    OVERLOADED("overloaded"),
    NOT_FOUND("not_found"),
    STREAM_INTERRUPTED("stream_interrupted"),
    NETWORK_ERROR("network_error"),
    IMAGE_GEN_FAILED("image_gen_failed"),
    IMAGE_RESPONSE_TOO_LARGE("image_response_too_large"),
    IMAGE_EMPTY_RESPONSE("image_empty_response"),
    IMAGE_NO_RESULTS("image_no_results"),
    IMAGE_UNSUPPORTED_MODEL("image_unsupported_model"),
    IMAGE_REFERENCE_TOO_LARGE("image_reference_too_large"),
    IMAGE_REFERENCE_DOWNLOAD_FAILED("image_reference_download_failed"),
    IMAGE_INVALID_URI("image_invalid_uri"),
    IMAGE_API_KEY_MISSING("image_api_key_missing"),
    VERTEX_AI_CONFIG_INVALID("vertex_ai_config_invalid"),
    VERTEX_AI_TOKEN_FAILED("vertex_ai_token_failed"),
    MEMORY_TOKEN_BUDGET_INVALID("memory_token_budget_invalid"),
    MEMORY_CONFIG_INVALID("memory_config_invalid"),
}

fun ErrorCode.toMessage(vararg args: Any?): String {
    val prefix = "ERR_$key"
    if (args.isEmpty()) return prefix
    return "$prefix:${args.joinToString(",")}"
}
