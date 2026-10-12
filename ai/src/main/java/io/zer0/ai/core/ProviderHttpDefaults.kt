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
    /** OkHttp 总调用超时(秒)— R-AI-03: 10 分钟兜底,极端挂起不再无限等待。 */
    const val CALL_TIMEOUT_SEC = 600L

    /**
     * v2.5.1: 流式专用总超时(秒)。
     *
     * 用户实测: glm-5.3 等深度思考模型在中转站上思考期(10min+)不吐任何 delta,
     * 共享 client 的 600s callTimeout 把整个流硬杀,回复被截断。
     * 流式改为不设总超时(0):静默挂起仍由 readTimeout(300s) 抓住,
     * 用户取消由 abortSignal 兑底 —— 两者已覆盖全部异常路径。
     */
    const val STREAM_CALL_TIMEOUT_SEC = 0L
}
