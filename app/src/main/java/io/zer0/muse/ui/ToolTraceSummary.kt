package io.zer0.muse.ui

import android.content.res.Resources
import io.zer0.muse.R

/**
 * 按工具名聚合的任务级工具轨迹摘要。
 *
 * [records] 保留该组的全部原始记录；聚合只改变历史面板的展示方式，不删除或改写消息。
 */
internal data class ToolTraceSummary(
    val toolName: String,
    val records: List<ToolCallRecord>,
    val successCount: Int,
    val failureCount: Int,
    val runningCount: Int,
    val latestRecord: ToolCallRecord,
) {
    val totalCount: Int
        get() = records.size

    /** 失败或执行中的工具组默认展开，便于用户及时发现需要关注的项。 */
    val shouldExpandByDefault: Boolean
        get() = failureCount > 0 || runningCount > 0

    /** 最近一次结果的低噪摘要，供折叠态直接预览。res 为 null(预览/纯函数场景)时回退空串。 */
    val latestSummary: String
        get() = toolTraceRecordSummary(latestRecord)
}

/** 工具记录与 [ToolCallCard] 保持一致：空结果且未成功表示执行中。 */
internal fun ToolCallRecord.isRunningToolTrace(): Boolean = !isSuccess && result.isBlank()

/** 有结果但未成功表示失败；空结果的未成功记录不重复计入失败。 */
internal fun ToolCallRecord.isFailedToolTrace(): Boolean = !isSuccess && result.isNotBlank()

/**
 * 将工具历史按工具名聚合，同时保留首次出现的工具顺序和每组的全部原始记录。
 *
 * 最近一次记录按 timestamp 选择；相同 timestamp 时保留输入列表中更靠后的记录。
 */
internal fun summarizeToolTrace(records: List<ToolCallRecord>): List<ToolTraceSummary> {
    if (records.isEmpty()) return emptyList()

    val grouped = linkedMapOf<String, MutableList<Pair<Int, ToolCallRecord>>>()
    records.forEachIndexed { index, record ->
        grouped.getOrPut(record.toolName) { mutableListOf() } += index to record
    }

    return grouped.map { (toolName, indexedRecords) ->
        val groupRecords = indexedRecords.map { it.second }
        val latestRecord = indexedRecords
            .maxWithOrNull(compareBy<Pair<Int, ToolCallRecord>> { it.second.timestamp }.thenBy { it.first })
            ?.second
            ?: groupRecords.last()
        ToolTraceSummary(
            toolName = toolName,
            records = groupRecords,
            successCount = groupRecords.count { it.isSuccess },
            failureCount = groupRecords.count { it.isFailedToolTrace() },
            runningCount = groupRecords.count { it.isRunningToolTrace() },
            latestRecord = latestRecord,
        )
    }
}

private const val TOOL_TRACE_PREVIEW_LIMIT = 120

/** 折叠态摘要文案:状态先走资源(res),null(预览/纯函数)时回退空串。 */
internal fun toolTraceRecordSummary(record: ToolCallRecord, res: Resources? = null): String {
    val normalized = record.result.replace(Regex("\\s+"), " ").trim()
    return when {
        record.isRunningToolTrace() -> res?.getString(R.string.tool_trace_running) ?: ""
        normalized.isBlank() -> res?.getString(R.string.tool_trace_empty) ?: ""
        else -> {
            if (normalized.length > TOOL_TRACE_PREVIEW_LIMIT) {
                normalized.take(TOOL_TRACE_PREVIEW_LIMIT) + "…"
            } else {
                normalized
            }
        }
    }
}
