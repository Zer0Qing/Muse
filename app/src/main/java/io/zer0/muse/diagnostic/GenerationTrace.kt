package io.zer0.muse.diagnostic

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.x（诊断导出项目 A）：生成链路事件轨迹记录仪。
 *
 * 目的：用户遇到"流截断 / 卡住 / 超时"类问题时，能导出一份**无敏感内容**的
 * 结构化轨迹，开发者拿到后不再盲猜。
 *
 * 设计：
 *  - 内存环形缓冲最近 [RING_CAPACITY] 轮（进程级），生成结束即时追加一行 JSONL
 *    落盘（filesDir/diagnostics/generation-trace.jsonl，滚动上限 [MAX_FILE_BYTES]）；
 *  - **红线：不记录任何消息正文、prompt、API key**。只记统计量、事件时间线、
 *    配置标识（modelId/providerType/host）。sessionId 以 SHA-256 前 8 位哈希脱敏；
 *  - 纯观察者：任何 trace 写入异常一律吞掉打 warn，绝不影响生成主链路；
 *  - 与 debugMode 无关——普通用户也记录（内容本身已脱敏），导出时才读取。
 *
 * 埋点：ChatGenerationController.recordDebugSummary（终态汇总，天然挂点）。
 */
object GenerationTrace {

    /** 环形缓冲容量（轮）。 */
    const val RING_CAPACITY = 20

    /** JSONL 文件滚动上限。超过后截断为后半段。 */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private const val TRACE_FILE_NAME = "generation-trace.jsonl"

    private val ring = ArrayDeque<TraceRecord>()
    private val lock = Any()

    @Volatile
    private var traceFile: File? = null

    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 一轮生成的结构化轨迹（全部字段都是统计量/标识，无正文无密钥）。 */
    @Serializable
    data class TraceRecord(
        /** 会话 ID 的 SHA-256 前 8 位（脱敏，仅用于区分会话）。 */
        val sessionHash: String,
        val modelId: String,
        val providerType: String,
        /** 终态：completed / failed / cancelled / context_overflow 等。 */
        val outcome: String,
        val elapsedMs: Long,
        /** 首 token 延迟；-1 表示未收到首 token。 */
        val ttftMs: Long,
        val contentChars: Int,
        val reasoningChars: Int,
        val toolCallCount: Int,
        val round: Int,
        val uiFlushCount: Int,
        /** Provider 上报的流中断/收尾分类（由 [noteStreamEnd] 写入）。 */
        val streamEndKind: String = "",
        /** finish_reason（null→空串）。 */
        val finishReason: String = "",
        /** 压缩是否在本轮触发（由 [noteCompression] 写入）。 */
        val compressionTriggered: Boolean = false,
        val ts: String,
    )

    /** 应用启动时初始化（MuseApp.onCreate）。幂等。 */
    fun init(context: Context) {
        runCatching {
            val dir = context.getExternalFilesDir("diagnostics") ?: File(context.filesDir, "diagnostics")
            dir.mkdirs()
            traceFile = File(dir, TRACE_FILE_NAME)
        }.onFailure { Logger.w("GenerationTrace", "init 失败: ${it.message}") }
    }

    /**
     * 记录一轮生成的终态汇总。由 ChatGenerationController 在每个终态路径调用。
     * 任何异常内部吞掉——trace 是纯观察者，不得影响生成。
     */
    fun record(summary: GenerationSummary) {
        runCatching {
            val rec = TraceRecord(
                sessionHash = hashSession(summary.sessionId),
                modelId = summary.modelId,
                providerType = summary.providerType,
                outcome = summary.outcome,
                elapsedMs = summary.elapsedMs,
                ttftMs = summary.ttftMs,
                contentChars = summary.contentChars,
                reasoningChars = summary.reasoningChars,
                toolCallCount = summary.toolCallCount,
                round = summary.round,
                uiFlushCount = summary.uiFlushCount,
                streamEndKind = takePending(pendingStreamEndKind, ""),
                finishReason = takePending(pendingFinishReason, ""),
                compressionTriggered = takePending(pendingCompression, false),
                ts = tsFormat.format(Date()),
            )
            synchronized(lock) {
                ring.addLast(rec)
                while (ring.size > RING_CAPACITY) ring.removeFirst()
            }
            appendToFile(rec)
        }.onFailure { Logger.w("GenerationTrace", "record 失败(已吞): ${it.message}") }
    }

    /** 一轮生成的终态汇总（record 的入参聚合，避免 LongParameterList）。 */
    data class GenerationSummary(
        val sessionId: String,
        val modelId: String,
        val providerType: String,
        val outcome: String,
        val elapsedMs: Long,
        val ttftMs: Long,
        val contentChars: Int,
        val reasoningChars: Int,
        val toolCallCount: Int,
        val round: Int,
        val uiFlushCount: Int,
    )

    // ── 终态前的分类注记（由 Provider/压缩路径先行写入，record 时一并落入记录）──

    private val pendingStreamEndKind = ThreadLocal.withInitial { "" }
    private val pendingFinishReason = ThreadLocal.withInitial { "" }
    private val pendingCompression = ThreadLocal.withInitial { false }

    /**
     * 记录流的收尾分类（由 OpenAIProvider 的终态路径调用）：
     *  - "done"          正常收到 [DONE]/finishReason
     *  - "closed_no_finish" 连接关闭但未收到 finishReason（疑似中转站静默截断）
     *  - "failure"       onFailure（网络错误/超时）
     *  - "fallback"      stream-guard 触发非流式回退
     */
    fun noteStreamEnd(kind: String, finishReason: String? = null) {
        runCatching {
            pendingStreamEndKind.set(kind)
            pendingFinishReason.set(finishReason.orEmpty())
        }
    }

    /** 记录本轮触发了上下文压缩（由压缩编排路径调用）。 */
    fun noteCompression() {
        runCatching { pendingCompression.set(true) }
    }

    /** 当前缓冲内容（供导出与测试）。 */
    fun snapshot(): List<TraceRecord> = synchronized(lock) { ring.toList() }

    /** trace 文件（供导出打包）。可能为 null（未 init）。 */
    fun traceFileOrNull(): File? = traceFile?.takeIf { it.exists() }

    /** 清空缓冲与文件（测试用）。 */
    internal fun clearForTest() {
        synchronized(lock) { ring.clear() }
        runCatching { traceFile?.delete() }
    }

    private fun <T> takePending(local: ThreadLocal<T>, fallback: T): T {
        val v = local.get() ?: fallback
        local.set(fallback)
        return v
    }

    private fun hashSession(sessionId: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray())
        digest.joinToString("") { "%02x".format(it) }.take(8)
    }.getOrDefault("unknown")

    private fun appendToFile(rec: TraceRecord) {
        val file = traceFile ?: return
        synchronized(lock) {
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                // 滚动：保留后半段（简单截断策略，避免完整重写）
                val bytes = file.readBytes()
                val half = bytes.copyOfRange(bytes.size / 2, bytes.size)
                file.writeBytes(half)
            }
            file.appendText(AppJson.encodeToString(rec) + "\n")
        }
    }
}
