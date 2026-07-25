package io.zer0.ai.core

/**
 * v1.73: AI Provider HTTP 客户端共享默认值 — 三个 Provider(OpenAI / Gemini / Anthropic)
 * 原先各自硬编码 OkHttp 超时(30s connect / 300s read / 0s callTimeout),数值重复且
 * 修改需同步三处易遗漏。集中此处便于统一调优。
 *
 * v1.52 调整背景:深度思考(reasoning)模型可能长时间不出 token,readTimeout 提高到 300s;
 * callTimeout=0 不设总超时,流式靠取消信号。
 */
object ProviderHttpDefaults {
    /** OkHttp 连接超时(秒)。 */
    const val CONNECT_TIMEOUT_SEC = 30L

    /** OkHttp 读取超时(秒)— 提高以适应深度思考模型长任务。 */
    const val READ_TIMEOUT_SEC = 300L

    /** OkHttp 写入超时(秒)— 多模态 base64 请求体较大,默认 60s。 */
    const val WRITE_TIMEOUT_SEC = 60L

    /** OkHttp 总调用超时(秒)— 0 表示不设总超时,流式靠取消信号。 */
    const val CALL_TIMEOUT_SEC = 0L
}
