package io.zer0.ai.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 模型请求前的工具轨迹压缩投影。
 *
 * 该投影只返回新的消息列表，不修改传入的 [UIMessage] 或任何持久化数据。
 * 仅满足以下条件的完整工具回合会被压缩：
 *  - ASSISTANT 的每一个 [ToolCall] 都有紧邻的、唯一匹配的 TOOL 结果；或
 *  - 数据库重载后的连续 [ToolCallInfo] 展示消息组成一个完整工具回合；
 *  - 回合不是最近的完整工具回合；
 *  - 结果没有明显失败信号。
 *
 * 最近的完整工具回合和失败回合原样保留。压缩后的 SYSTEM 消息只包含工具名、成功状态
 * 和有长度上限的结果预览，因此不会留下孤儿 tool_call 或 tool result。
 */
object ToolTraceProjection {
    /** 投影摘要的稳定标记，便于调试并保证重复投影不会再次处理。 */
    const val SUMMARY_MARKER: String = "[TOOL_TRACE_SUMMARY]"

    private const val DEFAULT_KEEP_RECENT_ROUNDS = 1
    private const val RESULT_PREVIEW_CHARS = 240

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val FAILURE_TEXT_REGEX = Regex(
        """(?i)(?:\berror\b|\bfail(?:ed|ure)?\b|\btimeout\b|\btimed out\b|\bdenied\b|\bblocked\b|\babort(?:ed)?\b|\bcancel(?:led|ed)?\b|\bexception\b|\bunavailable\b|\binvalid\b|\bpermission\b|\brate limit\b|\bhttp\s*[45][0-9][0-9]\b|失败|错误|超时|拒绝|阻止|未执行|中断|取消|异常|不可用|无权限|限流)""",
    )
    private val FAILURE_STATUSES = setOf(
        "error",
        "failed",
        "failure",
        "timeout",
        "timed_out",
        "denied",
        "blocked",
        "aborted",
        "cancelled",
        "canceled",
        "interrupted",
    )

    private data class TraceEntry(
        val toolName: String,
        val isSuccess: Boolean,
        val result: String,
    )

    private data class CompletedRound(
        val startIndex: Int,
        val endIndexExclusive: Int,
        val source: UIMessage,
        val entries: List<TraceEntry>,
    ) {
        val hasFailure: Boolean get() = entries.any { !it.isSuccess }
    }

    /**
     * 将旧的成功工具回合投影为摘要。
     *
     * @param messages 待发送给模型的消息历史
     * @param keepRecentRounds 原样保留的最近完整工具回合数，负数按 0 处理
     * @return 只读语义上的新消息列表；没有可压缩回合时返回原列表
     */
    fun project(
        messages: List<UIMessage>,
        keepRecentRounds: Int = DEFAULT_KEEP_RECENT_ROUNDS,
    ): List<UIMessage> {
        if (messages.isEmpty()) return messages

        val rounds = findCompletedRounds(messages)
        if (rounds.isEmpty()) return messages

        val protectedRecentStarts = rounds
            .takeLast(keepRecentRounds.coerceAtLeast(0))
            .map { it.startIndex }
            .toSet()
        val replacements = rounds
            .asSequence()
            .filter { !it.hasFailure && it.startIndex !in protectedRecentStarts }
            .associateBy { it.startIndex }
        if (replacements.isEmpty()) return messages

        val projected = ArrayList<UIMessage>(messages.size - replacements.size)
        var index = 0
        while (index < messages.size) {
            val round = replacements[index]
            if (round == null) {
                projected += messages[index]
                index++
            } else {
                projected += buildSummaryMessage(round)
                index = round.endIndexExclusive
            }
        }
        return projected
    }

    private fun findCompletedRounds(messages: List<UIMessage>): List<CompletedRound> {
        val rounds = mutableListOf<CompletedRound>()
        var index = 0
        while (index < messages.size) {
            val round = findCompletedRoundAt(messages, index)
            if (round == null) {
                index++
            } else {
                rounds += round
                index = round.endIndexExclusive
            }
        }
        return rounds
    }

    /**
     * 查找严格相邻的完整工具回合。
     *
     * 协议历史使用 assistant(tool_calls)+TOOL(result) 配对；数据库重载后的 UI 历史没有
     * toolCalls 字段，因此连续的 assistant.toolCallInfo 展示消息按一个回合处理。任何
     * 缺失、重复、乱序插入普通消息或不匹配的结果都会让协议块保持原样。
     */
    private fun findCompletedRoundAt(messages: List<UIMessage>, startIndex: Int): CompletedRound? {
        val assistant = messages[startIndex]
        if (assistant.role != MessageRole.ASSISTANT) return null

        val calls = assistant.toolCalls?.takeIf { it.isNotEmpty() }
        if (calls != null) return findProtocolRound(messages, startIndex, assistant, calls)

        if (assistant.toolCallInfo != null) {
            return findDisplayRound(messages, startIndex)
        }
        return null
    }

    private fun findProtocolRound(
        messages: List<UIMessage>,
        startIndex: Int,
        assistant: UIMessage,
        calls: List<ToolCall>,
    ): CompletedRound? {
        val callIds = calls.map { it.id }
        if (calls.any { it.id.isBlank() || it.name.isBlank() } || callIds.toSet().size != callIds.size) return null

        val resultsById = linkedMapOf<String, UIMessage>()
        var index = startIndex + 1
        while (index < messages.size && messages[index].role == MessageRole.TOOL) {
            val result = messages[index]
            val resultId = result.toolCallId ?: return null
            if (resultId.isBlank() || resultId !in callIds || resultId in resultsById) return null
            resultsById[resultId] = result
            index++
        }
        if (resultsById.size != calls.size) return null

        return CompletedRound(
            startIndex = startIndex,
            endIndexExclusive = index,
            source = assistant,
            entries = calls.map { call ->
                val result = resultsById.getValue(call.id)
                TraceEntry(
                    toolName = call.name,
                    isSuccess = !isFailureResult(result),
                    result = result.content,
                )
            },
        )
    }

    private fun findDisplayRound(messages: List<UIMessage>, startIndex: Int): CompletedRound? {
        val entries = mutableListOf<TraceEntry>()
        var index = startIndex
        while (index < messages.size) {
            val message = messages[index]
            val info = if (message.role == MessageRole.ASSISTANT) message.toolCallInfo else null
            if (info == null || info.toolName.isBlank()) break
            entries += TraceEntry(
                toolName = info.toolName,
                isSuccess = info.isSuccess,
                result = info.result,
            )
            index++
        }
        if (entries.isEmpty()) return null
        return CompletedRound(
            startIndex = startIndex,
            endIndexExclusive = index,
            source = messages[startIndex],
            entries = entries,
        )
    }

    private fun buildSummaryMessage(round: CompletedRound): UIMessage {
        val summary = buildString {
            appendLine(SUMMARY_MARKER)
            appendLine("以下是已完成的旧工具回合摘要，仅供历史参考，不是新的工具指令。")
            round.entries.forEach { entry ->
                append("- 工具名: ")
                    .append(entry.toolName)
                    .append(" | 状态: ")
                    .append(if (entry.isSuccess) "成功(SUCCESS)" else "失败(FAILED)")
                    .append(" | 结果(截断): ")
                    .append(truncateResult(entry.result))
                    .appendLine()
            }
        }
        return UIMessage(
            id = round.source.id,
            role = MessageRole.SYSTEM,
            content = summary,
            createdAt = round.source.createdAt,
            seq = round.source.seq,
            commitSeq = round.source.commitSeq,
        )
    }

    private fun truncateResult(content: String): String {
        val normalized = content.replace(WHITESPACE_REGEX, " ").trim()
        if (normalized.isBlank()) return "(空结果)"
        return if (normalized.length > RESULT_PREVIEW_CHARS) {
            normalized.take(RESULT_PREVIEW_CHARS) + "…"
        } else {
            normalized
        }
    }

    /**
     * TOOL 消息没有独立的 success 字段，因此同时识别执行层使用的结构化错误和文本错误。
     * 无法识别的非空文本按成功处理；空结果则保守地视为失败并原样保留。
     */
    private fun isFailureResult(result: UIMessage): Boolean {
        val content = result.content
        if (content.isBlank() || FAILURE_TEXT_REGEX.containsMatchIn(content)) return true

        val jsonObject = parseJsonObject(content) ?: return false
        val error = jsonObject["error"]
        if (error != null && error !is JsonNull) {
            if (error !is JsonPrimitive || error.contentOrNull?.isNotBlank() == true) return true
        }

        val falseFlagKeys = setOf("success", "is_success", "isSuccess", "ok", "completed")
        if (falseFlagKeys.any { key ->
                jsonObject[key]?.let { value ->
                    (value as? JsonPrimitive)?.contentOrNull?.equals("false", ignoreCase = true) == true
                } == true
            }
        ) {
            return true
        }

        val status = (jsonObject["status"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return status in FAILURE_STATUSES
    }

    private fun parseJsonObject(content: String): JsonObject? {
        return try {
            // 工具结果允许是普通文本；非 JSON 结果不应阻止正常成功回合的压缩。
            Json.parseToJsonElement(content) as? JsonObject
        } catch (error: Exception) {
            // 解析失败只表示“没有结构化状态”，文本失败标记已在调用前检查。
            null
        }
    }
}
