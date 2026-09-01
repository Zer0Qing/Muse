package io.zer0.muse.asr

/**
 * v1.73: ASR 客户端共享常量 — 三个 ASR 客户端(StepAsrClient / DashScopeAsrClient /
 * DashScopeFileAsrClient)原先各自硬编码 OkHttp 超时与识别超时,数值重复且修改需同步多处。
 *
 * 集中此处便于统一调优。
 */
object AsrConstants {
    /** OkHttp 连接超时(秒)。 */
    const val CONNECT_TIMEOUT_SEC = 15L

    /** OkHttp 读取超时(秒)。 */
    const val READ_TIMEOUT_SEC = 60L

    /** 整体识别流程超时(毫秒)— 防止 ASR 卡死。 */
    const val RECOGNIZE_TIMEOUT_MS = 60_000L

    /** 短音频分段 HTTP 失败时的最大尝试次数。 */
    const val MAX_HTTP_ATTEMPTS = 2

    /** HTTP 重试退避基数(毫秒)。 */
    const val HTTP_RETRY_BACKOFF_MS = 350L
}
