package io.zer0.muse.backup

import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 备份的 NDJSON 读回校验：类型映射表与三个校验入口。
 *
 * 从 `BackupService.kt` 切出——它们不依赖服务实例状态，只吃 payload 与密码，
 * 各自可独立单测（云备份导入前的最后一道防线）。
 */
/** NDJSON 导出 type → meta 计数键(导出生成与导入/读回校验共用同一映射)。 */
internal val ndjsonTypeToMetaKey: Map<String, String> = mapOf(
    "session" to "sessions",
    "message" to "messages",
    "summary" to "sessionSummaries",
    "contextCheckpoint" to "contextCheckpoints",
    "dailyState" to "dailyStates",
    "compiledSection" to "compiledSections",
    "scopedCompiledSection" to "scopedCompiledSections",
    "fact" to "facts",
    "fileStore" to "fileStores",
    "binaryFile" to "binaryFiles",
    BackupStreamFiles.START to "binaryFiles",
    "assistant" to "assistants",
    "lorebook" to "lorebooks",
    "skill" to "skills",
    "artifact" to "artifacts",
    "quickMessage" to "quickMessages",
    "promptInjection" to "promptInjections",
    "folder" to "folders",
    "groupChat" to "groupChats",
    "groupChatMessage" to "groupChatMessages",
    "scheduledTask" to "scheduledTasks",
    "scheduledTaskExecution" to "scheduledTaskExecutions",
    "knowledgeDoc" to "knowledgeDocs",
    "knowledgeChunk" to "knowledgeChunks",
    "knowledgeV2Portable" to "knowledgeV2Portable",
    "experience" to "experiences",
    "milestone" to "milestones",
    "agentMessage" to "agentMessages",
    // P0-10: 此前缺失的实体
    "quickNote" to "quickNotes",
    "worldBookEntry" to "worldBookEntries",
    "conversationEvent" to "conversationEvents",
    "conversationTurn" to "conversationTurns",
    "conversationCommandReceipt" to "conversationCommandReceipts",
    "conversationSelection" to "conversationSelections",
    "conversationDisplaySlot" to "conversationDisplaySlots",
    "messagePart" to "messageParts",
    "messageOutbox" to "messageOutboxes",
    "scopedFact" to "scopedFacts",
    "translateHistory" to "translateHistories",
    "knowledgeBase" to "knowledgeBases",
    "knowledgeFolder" to "knowledgeFolders",
    "subagentThread" to "subagentThreads",
    "toolRound" to "toolRounds",
    "sessionBranchHead" to "sessionBranchHeads",
    "sessionTodo" to "sessionTodos",
    "pendingThought" to "pendingThoughts",
    "proactiveOutcome" to "proactiveOutcomes",
    "proactiveRelation" to "proactiveRelations",
    "proactiveBacklogDispatch" to "proactiveBacklogDispatches",
    "proactiveBacklogSchedule" to "proactiveBacklogSchedule",
    "sessionProactivePreference" to "sessionProactivePreferences",
    "botBinding" to "botBindings",
    "botTask" to "botTasks",
    "botTaskRun" to "botTaskRuns",
    "botTaskStep" to "botTaskSteps",
    "botEvent" to "botEvents",
    "botApproval" to "botApprovals",
    "botPublication" to "botPublications",
    "botAnnotation" to "botAnnotations",
    "botMessageCard" to "botMessageCards",
    "botRoutine" to "botRoutines",
    "agentWatcher" to "agentWatchers",
    "agentGoal" to "agentGoals",
    "remoteComputeNode" to "remoteComputeNodes",
    "sessionTerm" to "sessionTerms",
    "assistantRelationship" to "assistantRelationships",
    "cardEntity" to "cardEntities",
    "mediaGenTask" to "mediaGenTasks",
    "mediaGenAsset" to "mediaGenAssets",
    "groupChatMemory" to "groupChatMemories",
    "settings" to "settings",
)

/** Phase 3 (P1): 读回校验用的宽松 JSON 解析器(忽略未知字段,与导出侧一致)。 */
internal val readBackJson = AppJson

/**
 * Phase 3 (可靠性 P1): 云备份读回校验 — 解密后的 NDJSON 结构/meta 校验。
 *
 * F-04 的读回校验此前只比对上传密文的长度与 SHA-256(字节层)。加密备份在密码轮换/
 * 封装错误时可能仍字节一致,但解密后内容不可解析。这里追加结构层校验:
 *  1. 加密负载必须能用当前配置的备份密码解密;
 *  2. 首个非空行必须是合法 meta 行(type=meta,声明的计数均为非负整数);
 *  3. meta 声明的数据总条数必须 > 0(空备份拒绝);
 *  4. meta 之后至少存在一条非空数据行(行数 > 0)。
 *
 * 只读校验:不清空/写入任何表(区别于 [BackupService] 内部的导入路径)。
 *
 * @return null 表示通过;非 null 为失败原因(写入 auto_backup_log 供诊断展示)
 */
internal fun validateCloudReadBackNdjson(payload: ByteArray, password: String): String? {
    val plaintext = if (BackupCrypto.isEncrypted(payload)) {
        if (password.isEmpty()) {
            return "encrypted read-back but backup password is empty"
        }
        runCatching { BackupCrypto.decrypt(payload, password) }
            .getOrElse { return "decrypt failed: ${it.message ?: it.javaClass.simpleName}" }
    } else {
        payload
    }
    if (plaintext.isEmpty()) return "read-back payload is empty"
    return plaintext.inputStream().bufferedReader(Charsets.UTF_8).use(::validateCloudReadBackReader)
}

internal fun validateCloudReadBackReader(reader: java.io.BufferedReader): String? {
    val firstLine = generateSequence { reader.readLine() }.firstOrNull { !it.isNullOrBlank() }
    val metaResult = parseReadBackMeta(firstLine)
    var error = metaResult.error
    val meta = metaResult.meta
    if (error == null && meta != null) {
        var declaredTotal = 0L
        var countError: String? = null
        for (key in ndjsonTypeToMetaKey.values.distinct()) {
            val value = meta[key] ?: continue
            val count = (value as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            if (count == null) {
                countError = "read-back meta count '$key' is not an integer"
            } else if (count < 0) {
                countError = "read-back meta count '$key' is negative: $count"
            } else if (countError == null) {
                declaredTotal += count
            }
        }
        if (countError != null) {
            error = countError
        } else if (declaredTotal <= 0) {
            error = "read-back meta declares no data (all counts are 0)"
        }
    }
    if (error == null) {
        val hasDataLine = generateSequence { reader.readLine() }.any { it.isNotBlank() }
        if (!hasDataLine) error = "read-back has no record lines after meta"
    }
    return error
}

private data class ReadBackMetaResult(val meta: JsonObject?, val error: String?)

private fun parseReadBackMeta(firstLine: String?): ReadBackMetaResult {
    if (firstLine == null) return ReadBackMetaResult(null, "read-back has no non-blank lines")
    val meta = runCatching { readBackJson.parseToJsonElement(firstLine) as? JsonObject }.getOrNull()
        ?: return ReadBackMetaResult(null, "read-back first line is not a JSON object")
    val type = (meta["type"] as? JsonPrimitive)?.contentOrNull
    return if (type == "meta") {
        ReadBackMetaResult(meta, null)
    } else {
        ReadBackMetaResult(null, "read-back first line type is '$type', expected 'meta'")
    }
}

internal fun validateCloudReadBackNdjsonWithPolicy(payload: ByteArray, password: String, passwordWasSet: Boolean): String? {
    return if (shouldRejectPlaintextCloudBackup(payload, passwordWasSet, password)) {
        "plaintext read-back while backup encryption is configured"
    } else {
        validateCloudReadBackNdjson(payload, password)
    }
}

internal fun shouldRejectPlaintextCloudBackup(payload: ByteArray, passwordWasSet: Boolean, password: String): Boolean {
    return !BackupCrypto.isEncrypted(payload) && (passwordWasSet || password.isNotBlank())
}
