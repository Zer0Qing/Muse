package io.zer0.muse.backup

import io.zer0.muse.data.diary.DiaryEntity
import io.zer0.muse.data.groupchat.GroupChatMemoryEntity
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.data.session.ConversationEventEntity
import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.data.session.MessageOutboxEntity
import io.zer0.muse.data.session.MessagePartEntity
import io.zer0.muse.data.session.SessionBranchHeadEntity
import io.zer0.muse.data.session.ToolRoundEntity
import io.zer0.muse.data.subagent.SubagentThreadEntity
import io.zer0.muse.ui.translate.TranslateHistoryEntity
import io.zer0.muse.worldbook.WorldBookEntryEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-10 ① 验收:round-trip 备份恢复测试 — 此前缺失的实体(6 会话域 + Diary + 6 用户表)
 * 经 Backup 序列化 → 反序列化后字段完整往返,不丢数据。
 *
 * 说明:BackupService 依赖多个 Room DB/Context,单测层用 Backup 结构体的
 * 序列化 round-trip 验证(与 BackupKeyExclusionTest 同层级);DB 落库路径
 * 由 applyBackupInternal / applyNdJsonStreaming 的对称断言(导入侧同字段)
 * 与实体覆盖测试共同保证。
 */
class BackupRoundTripSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `P0-10 entities round trip without data loss`() {
        val quickNote = QuickNoteEntity(
            id = "qn1", title = "标题", content = "内容", tags = listOf("a", "b"),
            folder = "工作", contentType = "markdown", attachmentsJson = "[]",
        )
        val worldBook = WorldBookEntryEntity(
            id = "wb1", name = "条目", content = "正文", keywordsJson = """["关键词"]""",
            alwaysActive = true, scanDepth = 5,
        )
        val convEvent = ConversationEventEntity(
            sessionId = "s1", eventSeq = 1, eventId = "ev1", turnId = "t1",
            type = "stream_delta", payloadJson = "{}", payloadHash = "h", payloadLength = 2,
            createdAt = 100L,
        )
        val convTurn = ConversationTurnEntity(
            turnId = "t1", sessionId = "s1",
            inputUserMessageId = "m1", assistantMessageId = "m2", phase = "user",
            startedAt = 100L, updatedAt = 100L,
        )
        val messagePart = MessagePartEntity(
            messageId = "m1", partIndex = 0, kind = "text",
            text = "正文", createdAt = 100L,
        )
        val outbox = MessageOutboxEntity(
            id = "o1", sessionId = "s1", text = "待发",
            userMessageId = "m1", assistantMessageId = "m2", createdAt = 100L,
        )
        val diary = DiaryEntity(date = "2026-09-20", content = "日记正文", createdAt = 100L)
        val translate = TranslateHistoryEntity(
            id = "tr1", sourceText = "hello", translatedText = "你好",
            sourceLanguage = "en", targetLanguage = "zh",
        )
        val kb = KnowledgeBaseEntity(id = "kb1", name = "知识库", description = "d")
        val thread = SubagentThreadEntity(
            threadId = "th1", parentSessionId = "s1", childSessionPath = "/x.json",
            assistantId = "a1",
        )
        val toolRound = ToolRoundEntity(
            id = "r1", turnId = "t1", roundIndex = 0, toolCallId = "c1",
            toolName = "search", argsJson = "{}", status = "done", startedAt = 100L,
        )
        val branchHead = SessionBranchHeadEntity(sessionId = "s1", updatedAt = 100L)
        val groupChatMemory = GroupChatMemoryEntity(
            id = "gm1", groupChatId = "g1", assistantId = "a1", summary = "摘要",
        )

        val backup = BackupService.Backup(
            version = 3,
            exportedAt = 123L,
            sessions = emptyList(),
            messages = emptyList(),
            quickNotes = listOf(quickNote),
            worldBookEntries = listOf(worldBook),
            conversationEvents = listOf(convEvent),
            conversationTurns = listOf(convTurn),
            messageParts = listOf(messagePart),
            messageOutboxes = listOf(outbox),
            diaries = listOf(diary),
            translateHistories = listOf(translate),
            knowledgeBases = listOf(kb),
            subagentThreads = listOf(thread),
            toolRounds = listOf(toolRound),
            sessionBranchHeads = listOf(branchHead),
            groupChatMemories = listOf(groupChatMemory),
        )

        val text = json.encodeToString(BackupService.Backup.serializer(), backup)
        val restored = json.decodeFromString(BackupService.Backup.serializer(), text)

        assertEquals(quickNote, restored.quickNotes.single())
        assertEquals(worldBook, restored.worldBookEntries.single())
        assertEquals(convEvent, restored.conversationEvents.single())
        assertEquals(convTurn, restored.conversationTurns.single())
        assertEquals(messagePart, restored.messageParts.single())
        assertEquals(outbox, restored.messageOutboxes.single())
        assertEquals(diary, restored.diaries.single())
        assertEquals(translate, restored.translateHistories.single())
        assertEquals(kb, restored.knowledgeBases.single())
        assertEquals(thread, restored.subagentThreads.single())
        assertEquals(toolRound, restored.toolRounds.single())
        assertEquals(branchHead, restored.sessionBranchHeads.single())
        assertEquals(groupChatMemory, restored.groupChatMemories.single())
    }

    @Test
    fun `empty P0-10 fields serialize as absent for old backups`() {
        // 旧备份无新字段 → 反序列化默认空列表,hasAnyData 不受影响
        val oldStyle = """{"version":3,"exportedAt":1,"sessions":[],"messages":[]}"""
        val restored = json.decodeFromString(BackupService.Backup.serializer(), oldStyle)
        assertEquals(emptyList<DiaryEntity>(), restored.diaries)
        assertEquals(emptyList<TranslateHistoryEntity>(), restored.translateHistories)
        assertEquals(emptyMap<String, List<*>>(), restored.scopedFacts)
    }
}
