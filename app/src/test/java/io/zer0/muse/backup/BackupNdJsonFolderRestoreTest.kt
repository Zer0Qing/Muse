package io.zer0.muse.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AUD-20261011-NDJSON-FOLDERS: NDJSON 恢复分发完整性守护。
 *
 * 缺陷背景（.audit REPORT_DATA F1）: v2.4.6 知识库文件夹接线时只加了导出行
 * （writeTypedLines "knowledgeFolder"）与 meta 计数，漏了 applyNdJsonStreaming
 * 的 when(type) 分发分支与恢复缓冲 —— 清空阶段 deleteAll 后无法回填，
 * 每次 NDJSON 恢复都会清空用户全部知识库文件夹，且 B-34 对账只数解析行数、不验入库，静默丢失。
 *
 * 本守护以 Backup.Backup 数据类字段清单为真源，断言每个有 NDJSON 导出行的类型
 * 在恢复分发器里都有对应 case，杜绝"导出了但导不回"再次发生。
 */
class BackupNdJsonFolderRestoreTest {

    /** 导出侧行类型 → 对应恢复分发分支必须出现的实体 DAO/缓冲标识。 */
    private val exportedTypeToRestoreMarker = mapOf(
        "session" to "sessionBuf",
        "message" to "messageBuf",
        "assistant" to "assistantBuf",
        "lorebook" to "lorebookBuf",
        "skill" to "skillBuf",
        "artifact" to "artifactBuf",
        "quickMessage" to "quickMsgBuf",
        "promptInjection" to "promptInjBuf",
        "folder" to "folderBuf",
        "groupChat" to "groupChatBuf",
        "groupChatMessage" to "groupChatMsgBuf",
        "scheduledTask" to "schedTaskBuf",
        "scheduledTaskExecution" to "schedExecBuf",
        "experience" to "experienceBuf",
        "milestone" to "milestoneBuf",
        "agentMessage" to "agentMsgBuf",
        "moment" to "momentBuf",
        "momentComment" to "momentCommentBuf",
        "momentLike" to "momentLikeBuf",
        "quickNote" to "quickNoteBuf",
        "worldBookEntry" to "worldBookBuf",
        "conversationEvent" to "convEventBuf",
        "conversationTurn" to "convTurnBuf",
        "messagePart" to "messagePartBuf",
        "messageOutbox" to "messageOutboxBuf",
        "diary" to "diaryBuf",
        "translateHistory" to "translateHistoryBuf",
        "knowledgeBase" to "knowledgeBaseBuf",
        // AUD-20261011-NDJSON-FOLDERS: 本次修复的缺口 —— 恢复分发必须有 knowledgeFolder 分支
        "knowledgeFolder" to "knowledgeFolderBuf",
        "subagentThread" to "subagentThreadBuf",
        "toolRound" to "toolRoundBuf",
        "sessionBranchHead" to "sessionBranchHeadBuf",
        "groupChatMemory" to "groupChatMemoryBuf",
        "knowledgeChunk" to "knowChunkBuf",
        "knowledgeDoc" to "knowDocBuf",
    )

    /** 从源码提取导出侧 writeTypedLines 的全部行类型。 */
    private fun exportedTypes(): List<String> {
        val source = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()
        return Regex("writeTypedLines\\(\\s*writer,\\s*\"([a-zA-Z]+)\"")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `every exported ndjson type has a restore dispatch branch`() {
        val source = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()
        val start = source.indexOf("private suspend fun applyNdJsonStreaming(")
        val end = source.indexOf(
            "private suspend fun applyNdJsonStreamingWithImageCleanup",
            startIndex = start,
        )
        require(start >= 0 && end > start) { "无法定位 NDJSON 恢复方法边界" }
        val dispatch = source.substring(start, end)

        val missing = exportedTypes()
            .filter { it != "meta" && it != "settings" && it != "scopedFact" && it != "fileStore" }
            .mapNotNull { type ->
                val marker = exportedTypeToRestoreMarker[type]
                    ?: return@mapNotNull "$type(测试映射表缺少恢复分支标识,请补表)"
                if (dispatch.contains("\"$type\" ->") && dispatch.contains(marker)) {
                    null
                } else {
                    "$type(分发缺 \" $type\" -> case 或缓冲 $marker)"
                }
            }
        assertEquals(
            "NDJSON 恢复分发缺导出类型的分支(清空后无法回填,恢复即丢数据):\n" + missing.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `ndjson restore flushes knowledge folders after import`() {
        val source = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()
        val start = source.indexOf("private suspend fun applyNdJsonStreaming(")
        val end = source.indexOf(
            "private suspend fun applyNdJsonStreamingWithImageCleanup",
            startIndex = start,
        )
        val dispatch = source.substring(start, end)
        assertTrue(
            "knowledgeFolder 缓冲必须在恢复事务内 flush 落库（knowledgeFolderDao().upsert）",
            dispatch.contains("knowledgeFolderDao().upsert"),
        )
    }

    /**
     * AUD-20261011-NDJSON-FOLDERS (REPORT_DATA F2): buildBackup() 的 Backup 构造
     * 必须把 DbSnapshot.knowledgeFolders 传入 —— 该函数同时是 recovery point 的快照来源
     * （writeRecoveryPoint → applyBackupInternal 回滚路径），漏传=恢复失败回滚清空全部文件夹。
     */
    @Test
    fun `buildBackup passes knowledgeFolders from snapshot into Backup`() {
        val source = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()
        val start = source
            .indexOf("private suspend fun buildBackup(includeSecrets: Boolean = false): Backup {")
            .let { if (it >= 0) it else source.indexOf("private suspend fun buildBackup(): Backup {") }
        require(start >= 0) { "无法定位 buildBackup 方法" }
        val end = source.indexOf("private suspend fun", startIndex = start + 40)
        val body = source.substring(start, if (end > start) end else source.length)

        assertTrue(
            "buildBackup 快照必须读取 knowledgeFolderDao().getAll()",
            body.contains("knowledgeFolderDao().getAll()"),
        )
        // 构造实参列表里必须显式传 knowledgeFolders =（否则 DbSnapshot 读到也没进 Backup）
        assertTrue(
            "buildBackup 的 Backup(...) 构造必须传 knowledgeFolders 参数" +
                "（recovery point 回滚依赖它回填文件夹）",
            Regex("knowledgeFolders\\s*=\\s*snap\\.knowledgeFolders").containsMatchIn(body),
        )
    }
}
