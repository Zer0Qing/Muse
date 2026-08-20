package io.zer0.muse.data.audit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * F-08: Agent Run 收据内存账本 — 保留最近 [MAX_RECORDS] 条工具执行收据。
 *
 * 设计:
 *  - 内存环形账本(只保留最近记录,不持久化;持久化走 AuditLogger.category="agent_run");
 *  - 供调试页/审计页展示最近的 Agent 工具调用链(含 parentRunId 层级);
 *  - 线程安全:全部操作在 [MutableStateFlow] 的 update 内完成。
 */
class AgentRunTracker {

    companion object {
        /** 内存保留上限(最近 N 条,防止无限增长)。 */
        const val MAX_RECORDS = 200
    }

    private val _records = MutableStateFlow<List<AgentRunRecord>>(emptyList())

    /** 最近工具执行收据(新→旧)。 */
    val records: Flow<List<AgentRunRecord>> = _records.asStateFlow()

    /** 记录一条工具执行收据,超出上限时丢弃最旧条目。 */
    fun record(entry: AgentRunRecord) {
        _records.update { current ->
            val next = (listOf(entry) + current).take(MAX_RECORDS)
            next
        }
    }

    /** 清空(调试用)。 */
    fun clear() {
        _records.value = emptyList()
    }
}
