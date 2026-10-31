package io.zer0.muse.tools

import io.zer0.muse.data.stats.UsageRecordDao
import io.zer0.muse.data.stats.UsageTotals
import java.time.Instant
import java.time.ZoneId

/**
 * get_token_usage 工具：模型自查自己的 token 消耗与上下文占用。
 *
 * 数据分层，各自来自真正记着它的地方：
 *  - 全量/今日：`usage_records` 流水（每次模型请求一行，含压缩、起标题等非对话请求），
 *    直接复用 [UsageRecordDao] 已有的聚合查询，不引入第二套口径。
 *  - 本会话：`usage_records` 没有会话维度，本会话的数字取自 messages 上 provider 实测的
 *    用量字段（由宿主注入读取器）。会话 id 由 [ToolExecutionContext] 提供，模型无法伪造，
 *    拿不到就只报全局，不按时间窗口猜。
 *  - 上下文占用：属于 ViewModel 内存态，由宿主注入供应商；未注入则整行省略。
 *
 * 输出固定为几行短文本，数字按 k/M 简写，一屏内看完。
 */
object TokenUsageTool {

    const val NAME = "get_token_usage"

    private const val SCOPE_TODAY = "today"
    private const val SCOPE_SESSION = "session"
    private const val SCOPE_ALL = "all"

    private const val TOP_MODEL_LIMIT = 5
    private const val TOP_SOURCE_LIMIT = 4

    /** 本会话累计用量（requests / 输入 / 输出）。 */
    data class SessionUsage(
        val requests: Int,
        val promptTokens: Long,
        val completionTokens: Long,
    )

    fun toolDef() = ToolRegistry.ToolDef(
        name = NAME,
        description = "查询 token 消耗和上下文占用：本会话累计、今日/累计全来源合计、按模型分布、" +
            "当前上下文占用量与上限。用户问「用了多少 token」「今天花了多少」「上下文还剩多少」" +
            "或需要自查成本时调用。纯读取，无必填参数。",
        parameters = mapOf(
            "scope" to
                "Optional. 'today' (default) = all sources since local midnight; " +
                "'session' = this conversation only; 'all' = everything recorded so far.",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * 执行查询。
     *
     * @param dao 用量流水只读口；为 null 时明确报不可用，不用空数据假装成功。
     * @param sessionUsage 本会话用量读取器（入参为宿主注入的会话 id）。
     * @param contextOccupancy 上下文占用供应商（当前占用, 上限）；拿不到返回 null。
     * @param now 当前时间，测试可注入。
     * @param zone 时区，"今日"按此时区的 00:00 起算。
     */
    @Suppress("LongParameterList")
    suspend fun execute(
        args: Map<String, String>,
        context: ToolExecutionContext,
        dao: UsageRecordDao?,
        sessionUsage: (suspend (String) -> SessionUsage?)? = null,
        contextOccupancy: (() -> Pair<Int, Int>?)? = null,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (dao == null) return "Error: token usage records are unavailable."
        val scope = args["scope"]?.trim()?.lowercase()?.ifEmpty { SCOPE_TODAY } ?: SCOPE_TODAY
        if (scope != SCOPE_TODAY && scope != SCOPE_SESSION && scope != SCOPE_ALL) {
            return "Error: scope must be 'today', 'session' or 'all'."
        }

        val lines = mutableListOf<String>()
        val sessionId = context.sessionId?.trim().orEmpty()
        val session = readSessionUsage(sessionId, sessionUsage)

        if (scope == SCOPE_SESSION) {
            lines += sessionLine(sessionId, session)
        } else {
            val label =
                if (scope == SCOPE_ALL) {
                    "all time"
                } else {
                    "today " + Instant.ofEpochMilli(now).atZone(zone).toLocalDate() + " (since local midnight)"
                }
            lines += "Token usage · $label"
            lines += windowLines(dao, if (scope == SCOPE_ALL) 0L else startOfLocalDay(now, zone))
            if (sessionId.isNotEmpty() && session != null) lines += sessionLine(sessionId, session)
        }
        contextLine(contextOccupancy)?.let { lines += it }

        return if (lines.isEmpty()) "No token usage recorded yet." else lines.joinToString("\n")
    }

    // ── 数据读取 ──

    private suspend fun readSessionUsage(
        sessionId: String,
        reader: (suspend (String) -> SessionUsage?)?,
    ): SessionUsage? {
        if (sessionId.isEmpty() || reader == null) return null
        return runCatching { reader(sessionId) }.getOrNull()
    }

    /** 时间窗口内的合计 / 来源 / 模型三行；没有任何记录时给一句明确说明。 */
    private suspend fun windowLines(dao: UsageRecordDao, sinceMs: Long): String {
        val totals: UsageTotals? = runCatching { dao.totalsSince(sinceMs) }.getOrNull()
        if (totals == null || totals.requests <= 0) return "  total: no requests recorded in this window"
        val models =
            runCatching { dao.topModelsSince(sinceMs, TOP_MODEL_LIMIT) }.getOrDefault(emptyList())
        val sources =
            runCatching { dao.bySourceSince(sinceMs) }.getOrDefault(emptyList())

        val total = totals.promptTokens + totals.completionTokens
        val head = buildString {
            append("  total: ${totals.requests} req · in ${formatTokens(totals.promptTokens)}")
            append(" · out ${formatTokens(totals.completionTokens)} · all ${formatTokens(total)}")
            if (totals.cachedTokens > 0) append(" · cached ${formatTokens(totals.cachedTokens)}")
        }
        val sourceText =
            sources.take(TOP_SOURCE_LIMIT).joinToString(" · ") { row ->
                "${row.source} ${formatTokens(row.promptTokens + row.completionTokens)} (${row.requests})"
            }
        val modelText =
            models.joinToString(" · ") { row -> "${row.modelId} ${formatTokens(row.totalTokens)} (${row.requests})" }

        return buildString {
            appendLine(head)
            if (sourceText.isNotEmpty()) appendLine("  sources: $sourceText")
            if (modelText.isNotEmpty()) append("  models: $modelText")
        }.trimEnd('\n')
    }

    /** 本会话那一行。会话 id 拿不到、或没有任何实测用量时都给出明确说明，不省略也不瞎猜。 */
    private fun sessionLine(sessionId: String, usage: SessionUsage?): String = when {
        sessionId.isEmpty() -> "  session: unavailable (current session id is unknown)"
        usage == null -> "  session: no measured usage yet in this conversation"
        usage.requests <= 0 -> "  session: no measured usage yet in this conversation"
        else ->
            "  session: ${usage.requests} req · in ${formatTokens(usage.promptTokens)}" +
                " · out ${formatTokens(usage.completionTokens)}" +
                " · all ${formatTokens(usage.promptTokens + usage.completionTokens)}"
    }

    /** 上下文占用那一行；供应商缺失或上限未知（0）时返回 null，整行省略。 */
    private fun contextLine(provider: (() -> Pair<Int, Int>?)?): String? {
        val pair = runCatching { provider?.invoke() }.getOrNull() ?: return null
        val used = pair.first.coerceAtLeast(0)
        val max = pair.second
        if (max <= 0) return null
        return "  context: ${formatTokens(used.toLong())} / ${formatTokens(max.toLong())} (${used * 100 / max}%)"
    }

    // ── 纯函数（供测试钉住口径）──

    /** 本地时区当天 00:00 的毫秒时间戳。 */
    internal fun startOfLocalDay(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** 数字简写：<1k 原样，否则按 k / M / B 缩写，小数位仅在需要时保留（12.3k / 21k）。 */
    internal fun formatTokens(value: Long): String {
        val v = value.coerceAtLeast(0)
        return when {
            v < 1_000L -> v.toString()
            v < 1_000_000L -> scaled(v, 1_000L, "k")
            v < 1_000_000_000L -> scaled(v, 1_000_000L, "M")
            else -> scaled(v, 1_000_000_000L, "B")
        }
    }

    private fun scaled(value: Long, unit: Long, suffix: String): String {
        val tenths = value * 10 / unit
        val fraction = tenths % 10
        return if (fraction == 0L) "${tenths / 10}$suffix" else "${tenths / 10}.$fraction$suffix"
    }
}
