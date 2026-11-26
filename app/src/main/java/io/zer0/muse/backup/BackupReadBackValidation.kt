package io.zer0.muse.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.zer0.common.AppJson
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactEntity
import io.zer0.memory.summary.CompiledSectionEntity
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import io.zer0.memory.summary.DailyStateEntity
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.memory.summary.ContextCheckpointEntity
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.stats.AutoBackupLogDao
import io.zer0.muse.data.stats.AutoBackupLogEntity
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.session.FolderEntity
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.milestone.MilestoneEntity
import io.zer0.muse.data.agentdm.AgentMessageEntity
import io.zer0.muse.data.session.ConversationEventEntity
import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.data.session.MessagePartEntity
import io.zer0.muse.data.session.MessageOutboxEntity
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.session.ToolRoundEntity
import io.zer0.muse.data.session.SessionBranchHeadEntity
import io.zer0.muse.data.groupchat.GroupChatMemoryEntity
import io.zer0.muse.data.subagent.SubagentThreadEntity
import io.zer0.muse.ui.translate.TranslateHistoryEntity
import io.zer0.common.Hashing
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream

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
    plaintext.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
        val firstLine = generateSequence { reader.readLine() }
            .firstOrNull { !it.isNullOrBlank() }
            ?: return "read-back has no non-blank lines"
        val meta = runCatching {
            readBackJson.parseToJsonElement(firstLine) as? JsonObject
        }.getOrNull() ?: return "read-back first line is not a JSON object"
        val type = (meta["type"] as? JsonPrimitive)?.contentOrNull
        if (type != "meta") return "read-back first line type is '$type', expected 'meta'"

        var declaredTotal = 0L
        for (key in ndjsonTypeToMetaKey.values.distinct()) {
            val value = meta[key] ?: continue
            val count = (value as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: return "read-back meta count '$key' is not an integer"
            if (count < 0) return "read-back meta count '$key' is negative: $count"
            declaredTotal += count
        }
        if (declaredTotal <= 0) return "read-back meta declares no data (all counts are 0)"

        val hasDataLine = generateSequence { reader.readLine() }.any { it.isNotBlank() }
        if (!hasDataLine) return "read-back has no record lines after meta"
        return null
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

