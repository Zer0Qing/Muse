package io.zer0.muse.miniphone.data.checkup

import io.zer0.common.Logger
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.entity.MpCheckupCaseEntity
import io.zer0.muse.miniphone.data.db.entity.MpCheckupEvidenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** 查岗方向：本机用户查角色，或角色查本机用户。落库用 [wire]。 */
internal enum class MpCheckupDirection(val wire: String) {
    OUTGOING("outgoing"),
    INCOMING("incoming"),
    ;

    companion object {
        fun fromWire(wire: String): MpCheckupDirection = entries.firstOrNull { it.wire == wire } ?: OUTGOING
    }
}

/** 取证强度：决定从既有记录里取多少条证据。 */
internal enum class MpCheckupIntensity(val wire: String, val messages: Int, val moments: Int, val calls: Int) {
    LIGHT("light", messages = 3, moments = 1, calls = 0),
    STANDARD("standard", messages = 10, moments = 3, calls = 3),
    DEEP("deep", messages = 30, moments = 5, calls = 5),
    ;

    companion object {
        fun fromWire(wire: String): MpCheckupIntensity = entries.firstOrNull { it.wire == wire } ?: STANDARD
    }
}

/**
 * 查岗案卷门面：立案 → 从既有记录取证 → 让角色解读 → 结案。
 *
 * 取证只读小手机自己的库（消息/动态/通话/备忘录），不碰宿主数据。
 * 证据正文只存「原文或记号」，不存任何面向用户的成品文案 ——
 * 通话证据存 `call_voice` / `call_video` 加秒数，由界面按当前语言渲染，
 * 与 [io.zer0.muse.miniphone.ui.chat.MpSentinel] 同一约定。
 *
 * 解读走宿主的通用一次性生成桥；拿不到结论就把案卷标成「未完成」并留空结论，
 * 不编一句看起来像结论的话 —— 用户要能分清「查过了没发现」和「根本没查成」。
 */
internal class MpCheckupStore(
    private val db: MiniPhoneDb,
    private val host: MiniPhoneHost,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    fun observeRecent(limit: Int = 50): Flow<List<MpCheckupCaseEntity>> = db.checkupCaseDao().observeRecent(limit)

    fun observeByCharacter(characterId: String): Flow<List<MpCheckupCaseEntity>> = db.checkupCaseDao().observeByCharacter(characterId)

    fun observeEvidence(caseId: String): Flow<List<MpCheckupEvidenceEntity>> = db.checkupEvidenceDao().observeByCase(caseId)

    /** 立案并取证；返回案卷 id。取证为空也照样立案，让「没查到」这件事本身可见。 */
    suspend fun openCase(
        characterId: String,
        direction: MpCheckupDirection,
        intensity: MpCheckupIntensity,
        initiatorId: String = "",
    ): String {
        val id = newId()
        val createdAt = now()
        db.checkupCaseDao().insert(
            MpCheckupCaseEntity(
                id = id,
                characterId = characterId,
                initiatorId = initiatorId,
                direction = direction.wire,
                intensity = intensity.wire,
                status = STATUS_RUNNING,
                createdAt = createdAt,
            ),
        )
        collectEvidence(id, characterId, intensity, createdAt)
        return id
    }

    /**
     * 批量让角色结构化解读证据并给出总结结论；拿不到结论就标未完成。
     *
     * 用一次结构化 JSON 调用（证据超过 [MAX_SUMMARY_EVIDENCE] 条时分批）替代逐条串行调用，
     * 避免深度查岗触发 40+ 次 LLM 请求。兼容 JSON、编号列表与纯文本三种模型输出形状。
     */
    suspend fun interpret(caseId: String) {
        val case = db.checkupCaseDao().get(caseId) ?: return
        val direction = MpCheckupDirection.fromWire(case.direction)
        val evidence = db.checkupEvidenceDao().observeByCase(caseId).first()

        var summary: String? = null
        if (evidence.isEmpty()) {
            summary = host.composeScene(case.characterId, buildEmptyEvidenceBrief(direction))?.trim()
        } else {
            val batches = evidence.chunked(MAX_SUMMARY_EVIDENCE)
            for ((batchIdx, batch) in batches.withIndex()) {
                val includeSummary = batchIdx == 0
                val raw = host.composeScene(
                    case.characterId,
                    buildBatchInterpretBrief(direction, batch, totalCount = evidence.size, includeSummary = includeSummary),
                )
                if (raw.isNullOrBlank()) continue
                val parsed = parseBatchResponse(raw, batch.size)
                batch.forEachIndexed { idx, item ->
                    val verdict = parsed.verdicts[idx + 1]?.trim()
                    if (!verdict.isNullOrBlank()) {
                        db.checkupEvidenceDao().setVerdict(item.id, verdict)
                    }
                }
                if (includeSummary && !parsed.summary.isNullOrBlank()) {
                    summary = parsed.summary.trim()
                }
            }
        }

        val finishedAt = now()
        if (summary.isNullOrBlank()) {
            db.checkupCaseDao().finish(caseId, STATUS_FAILED, "", finishedAt)
        } else {
            db.checkupCaseDao().finish(caseId, STATUS_DONE, summary.trim(), finishedAt)
        }
    }

    private data class ParsedBatchResult(
        val verdicts: Map<Int, String>,
        val summary: String?,
    )

    private fun formatEvidenceLabel(item: MpCheckupEvidenceEntity): String = when (item.kind) {
        KIND_MESSAGE -> "消息：${item.excerpt}"
        KIND_MOMENT -> "动态：${item.excerpt}"
        KIND_MEMO -> "备忘录：${item.excerpt}"
        KIND_CALL_VOICE -> "语音通话 ${item.excerpt} 秒"
        KIND_CALL_VIDEO -> "视频通话 ${item.excerpt} 秒"
        else -> "${item.kind}：${item.excerpt}"
    }

    private fun buildEmptyEvidenceBrief(direction: MpCheckupDirection): String = when (direction) {
        MpCheckupDirection.OUTGOING ->
            "用户刚刚查了你的岗，但没有翻到任何记录。用一两句话给出你的客观回应，不要编造不存在的记录。"
        MpCheckupDirection.INCOMING ->
            "你刚刚查了用户的岗，但没有翻到任何记录。用一两句话说出你的结论，不要编造不存在的记录。"
    }

    private fun buildBatchInterpretBrief(
        direction: MpCheckupDirection,
        batch: List<MpCheckupEvidenceEntity>,
        totalCount: Int,
        includeSummary: Boolean,
    ): String = buildString {
        when (direction) {
            MpCheckupDirection.OUTGOING -> {
                appendLine("用户正在查你的岗（共取到 $totalCount 条证据，本批 ${batch.size} 条）。")
                appendLine("请客观说明每条记录意味着什么，不要替自己强行辩解。")
            }
            MpCheckupDirection.INCOMING -> {
                appendLine("你正在查用户的岗（共取到 $totalCount 条线索，本批 ${batch.size} 条）。")
                appendLine("请站在你的视角解读每条记录说明了什么。")
            }
        }
        appendLine("证据列表：")
        batch.forEachIndexed { idx, item ->
            appendLine("${idx + 1}. [${formatEvidenceLabel(item)}]")
        }
        appendLine()
        if (includeSummary) {
            appendLine("请只输出严格 JSON（不要 markdown 代码块），包含每条的一句话解读与 2–3 句总结结论：")
            append("""{"verdicts":[{"index":1,"verdict":"一句话解读"}],"summary":"两三句话总结结论"}""")
        } else {
            appendLine("请只输出严格 JSON（不要 markdown 代码块），包含每条的一句话解读：")
            append("""{"verdicts":[{"index":1,"verdict":"一句话解读"}]}""")
        }
    }

    private fun parseBatchResponse(raw: String, batchSize: Int): ParsedBatchResult {
        val unfenced = normalizeBatchResponse(raw)
        parseJsonBatch(unfenced, batchSize)?.let { return it }
        parseNumberedBatch(unfenced, batchSize)?.let { return it }
        val plain = unfenced.trim()
        if (plain.isEmpty()) return ParsedBatchResult(emptyMap(), null)
        return ParsedBatchResult(
            verdicts = (1..batchSize).associateWith { plain },
            summary = plain,
        )
    }

    private fun normalizeBatchResponse(raw: String): String {
        val cleaned = raw.replace(THINK_CLOSED_REGEX, "").replace(THINK_UNCLOSED_REGEX, "").trim()
        return FENCE_REGEX.find(cleaned)?.groupValues?.get(1)?.trim() ?: cleaned
    }

    private fun parseJsonBatch(text: String, batchSize: Int): ParsedBatchResult? {
        val jsonCandidate = extractBalancedJsonObject(text) ?: return null
        val result = runCatching {
            val root = JSONObject(jsonCandidate)
            val verdicts = parseJsonVerdicts(root.optJSONArray("verdicts"), batchSize)
            ParsedBatchResult(verdicts, root.optString("summary").trim().ifBlank { null })
        }.onFailure { e ->
            Logger.w(TAG, "查岗 JSON 解析失败，降级文本解析: ${e.message}")
        }.getOrNull()
        return result?.takeIf { it.verdicts.isNotEmpty() || it.summary != null }
    }

    private fun parseJsonVerdicts(array: org.json.JSONArray?, batchSize: Int): LinkedHashMap<Int, String> {
        val verdicts = LinkedHashMap<Int, String>()
        if (array == null) return verdicts
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            val index = obj?.optInt("index", i + 1) ?: (i + 1)
            val text = obj?.optString("verdict")?.trim() ?: array.optString(i).trim()
            if (index in 1..batchSize && text.isNotBlank()) verdicts[index] = text
        }
        return verdicts
    }

    private fun parseNumberedBatch(text: String, batchSize: Int): ParsedBatchResult? {
        val verdicts = LinkedHashMap<Int, String>()
        var summary: String? = null
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            val numbered = NUMBERED_LINE_REGEX.find(trimmed)
            if (numbered != null) {
                val index = numbered.groupValues[1].toIntOrNull()
                val verdict = numbered.groupValues[2].trim()
                if (index != null && index in 1..batchSize && verdict.isNotBlank()) verdicts[index] = verdict
            } else if (trimmed.startsWith("结论") || trimmed.startsWith("总结")) {
                summary = trimmed.substringAfter("：", trimmed.substringAfter(":", "")).trim().takeIf { it.isNotBlank() }
            }
        }
        return verdicts.takeIf { it.isNotEmpty() }?.let {
            ParsedBatchResult(it, summary ?: text.trim().takeIf(String::isNotBlank))
        }
    }

    private fun extractBalancedJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    suspend fun deleteCase(caseId: String) {
        db.checkupEvidenceDao().deleteByCase(caseId)
        db.checkupCaseDao().delete(caseId)
    }

    private suspend fun collectEvidence(caseId: String, characterId: String, intensity: MpCheckupIntensity, createdAt: Long) {
        val threads = db.threadDao().observeThreads().first()
            .filter { it.characterId == characterId || it.isGroup }
        threads.forEach { thread ->
            db.messageDao().pageMessages(thread.id, intensity.messages, 0)
                .filter { it.sender == MpActorType.CHARACTER && it.characterId == characterId }
                .forEach { message -> addEvidence(caseId, KIND_MESSAGE, message.content, message.id, createdAt) }
        }
        db.momentDao().observeByAuthor(characterId).first()
            .take(intensity.moments)
            .forEach { moment -> addEvidence(caseId, KIND_MOMENT, moment.content, moment.id, createdAt) }
        if (intensity.calls > 0) {
            db.callSessionDao().observeHistory(intensity.calls).first()
                .filter { it.characterId == characterId }
                .forEach { call ->
                    val kind = if (call.kind == "video") KIND_CALL_VIDEO else KIND_CALL_VOICE
                    addEvidence(caseId, kind, (call.durationMs / 1000).toString(), call.id, createdAt)
                }
        }
        if (intensity == MpCheckupIntensity.DEEP) {
            db.memoDao().observeForCharacter(characterId).first()
                .forEach { memo -> addEvidence(caseId, KIND_MEMO, memo.content, memo.id, createdAt) }
        }
    }

    private suspend fun addEvidence(caseId: String, kind: String, excerpt: String, refId: String, createdAt: Long) {
        val text = excerpt.trim()
        if (text.isEmpty()) return
        db.checkupEvidenceDao().insert(
            MpCheckupEvidenceEntity(
                id = newId(),
                caseId = caseId,
                kind = kind,
                excerpt = text,
                refId = refId,
                createdAt = createdAt,
            ),
        )
    }

    companion object {
        private const val TAG = "MpCheckupStore"

        const val KIND_MESSAGE: String = "message"
        const val KIND_MOMENT: String = "moment"
        const val KIND_MEMO: String = "memo"
        const val KIND_CALL_VOICE: String = "call_voice"
        const val KIND_CALL_VIDEO: String = "call_video"

        const val STATUS_RUNNING: String = "running"
        const val STATUS_DONE: String = "done"
        const val STATUS_FAILED: String = "failed"

        private const val MAX_SUMMARY_EVIDENCE = 12

        private val THINK_CLOSED_REGEX = Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE)
        private val THINK_UNCLOSED_REGEX = Regex("<think(?:ing)?>[\\s\\S]*$", RegexOption.IGNORE_CASE)
        private val FENCE_REGEX = Regex("""```(?:json)?\s*\r?\n([\s\S]*?)\r?\n```""", RegexOption.IGNORE_CASE)
        private val NUMBERED_LINE_REGEX = Regex("""^\[?(\d+)\]?[\.、:：\)\]]\s*(.+)$""")
    }
}
