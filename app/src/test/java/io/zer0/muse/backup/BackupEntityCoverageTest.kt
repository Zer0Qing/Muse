package io.zer0.muse.backup

import io.zer0.muse.backup.BackupService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P5-5: 备份完整性两重守护。
 *
 * 1) 实体覆盖: 遍历 MuseDb.kt 声明的 @Database 实体清单(单一真源),
 *    断言每一张数据表都被 [BackupService.Backup] 纳入;新增数据表若未接备份
 *    (也未显式归入运维表清单)直接失败,杜绝换机丢数据回归。
 * 2) 密码降级回归: P0-1 — 已设密码但 Keystore 丢失(读回为空)必须拒绝
 *    导出/上传,不允许静默降级为明文。
 */
class BackupEntityCoverageTest {

    /** 用户数据实体 → Backup 序列化字段名(必须存在)。 */
    private val dataEntityToField = mapOf(
        "SessionEntity" to "sessions",
        "MessageEntity" to "messages",
        "ArtifactEntity" to "artifacts",
        "AssistantEntity" to "assistants",
        "LorebookEntity" to "lorebooks",
        "QuickMessageEntity" to "quickMessages",
        "PromptInjectionEntity" to "promptInjections",
        "SkillEntity" to "skills",
        "FolderEntity" to "folders",
        "ScheduledTaskEntity" to "scheduledTasks",
        "KnowledgeDocEntity" to "knowledgeDocs",
        "KnowledgeChunkEntity" to "knowledgeChunks",
        "ScheduledTaskExecutionEntity" to "scheduledTaskExecutions",
        "GroupChatEntity" to "groupChats",
        "GroupChatMessageEntity" to "groupChatMessages",
        "GroupChatMemoryEntity" to "groupChatMemories",
        "io.zer0.muse.data.moment.MomentEntity" to "moments",
        "io.zer0.muse.data.moment.MomentCommentEntity" to "momentComments",
        "io.zer0.muse.data.moment.MomentLikeEntity" to "momentLikes",
        "io.zer0.muse.data.diary.DiaryEntity" to "diaries",
        "ExperienceEntity" to "experiences",
        "MilestoneEntity" to "milestones",
        "AgentMessageEntity" to "agentMessages",
        "KnowledgeBaseEntity" to "knowledgeBases",
        "MessageOutboxEntity" to "messageOutboxes",
        "TranslateHistoryEntity" to "translateHistories",
        "QuickNoteEntity" to "quickNotes",
        "io.zer0.muse.worldbook.WorldBookEntryEntity" to "worldBookEntries",
        "io.zer0.muse.data.subagent.SubagentThreadEntity" to "subagentThreads",
        "ConversationEventEntity" to "conversationEvents",
        "ConversationTurnEntity" to "conversationTurns",
        "ToolRoundEntity" to "toolRounds",
        "MessagePartEntity" to "messageParts",
        "SessionBranchHeadEntity" to "sessionBranchHeads",
    )

    /** 运维/派生表: 明确不纳入备份(重建成本低、含机器元数据)。新数据表不得落入此集合。 */
    private val operationalEntities = setOf(
        "StatsCacheEntity", "DbIntegrityLogEntity", "AutoBackupLogEntity",
        "AuditLogEntity", "io.zer0.muse.data.patrol.PatrolLogEntity",
        "GenerationCheckpointEntity", "GroupChatGenerationLedgerEntity",
    )

    /** 从 MuseDb.kt 源码提取 @Database 声明实体(保持与 Room 真源一致,规避注解反射不可见问题)。 */
    private fun declaredEntities(): List<String> {
        val source = File("src/main/java/io/zer0/muse/data/session/MuseDb.kt").readText()
        val block = Regex("entities\\s*=\\s*\\[([\\s\\S]*?)\\]").find(source)?.groupValues?.get(1)
            ?: error("未能在 MuseDb.kt 找到 entities 声明块")
        return Regex("([\\w.]+)::class").findAll(block).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `every muse db data entity is covered by backup`() {
        val backupFields = BackupService.Backup::class.java.declaredFields.map { it.name }.toSet()
        val unknown = mutableListOf<String>()
        val missing = mutableListOf<String>()

        for (entity in declaredEntities()) {
            val field = dataEntityToField[entity]
            when {
                field != null && backupFields.contains(field) -> Unit
                field != null -> missing += "$entity → $field(备份缺少该字段)"
                entity in operationalEntities -> Unit
                else -> unknown += entity
            }
        }

        assertEquals(
            "新增数据实体未接入备份(或未显式归入运维表),换机将丢数据:\n" + missing.joinToString("\n"),
            emptyList<String>(), missing,
        )
        assertEquals(
            "无法识别的实体,请接入 backup 或加入 operationalEntities(运维表)清单:\n" + unknown.joinToString("\n"),
            emptyList<String>(), unknown,
        )
    }

    @Test
    fun `every backup field maps to a declared entity or known derived slot`() {
        val declared = declaredEntities().toSet()
        val knownDerived = setOf(
            "settingsSnapshot", "scopedFacts",
            // v4: 文件型存储快照(渠道/连接器/批注/收件箱/插件),无对应 MuseDb 实体
            "fileStores",
            // memory 模块表(独立 MemoryDb,不在 MuseDb 清单)已随备份一并导出
            "sessionSummaries", "dailyStates", "compiledSections", "scopedCompiledSections", "facts",
        )
        val orphans = BackupService.Backup::class.java.declaredFields
            .filter { List::class.java.isAssignableFrom(it.type) || Map::class.java.isAssignableFrom(it.type) }
            .map { it.name }
            .filter { it !in knownDerived && it !in dataEntityToField.values }
            .filterNot { it in operationalEntities }
            .filter { it !in declared }
        assertEquals("备份字段没有对应实体来源,请核对:\n" + orphans.joinToString("\n"), emptyList<String>(), orphans)
    }

    @Test
    fun `password degradation is rejected not downgraded`() {
        // 已设密码 + Keystore 丢失读回为空 → 拒绝导出/上传
        assertTrue(
            "已设密码但读回为空必须判定不可用(P0-1,禁止降级为明文)",
            BackupService.isBackupPasswordUnavailable(
                CloudBackupConfig(backupPasswordSet = true, backupPassword = ""),
            ),
        )
        // 从未设过密码 → 明文流程继续(兼容旧备份)
        assertFalse(
            BackupService.isBackupPasswordUnavailable(
                CloudBackupConfig(backupPasswordSet = false, backupPassword = ""),
            ),
        )
        // 正常密码 → 可用
        assertFalse(
            BackupService.isBackupPasswordUnavailable(
                CloudBackupConfig(backupPasswordSet = true, backupPassword = "s3cret"),
            ),
        )
    }
}
