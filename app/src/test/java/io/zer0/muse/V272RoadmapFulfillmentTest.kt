package io.zer0.muse

import io.zer0.common.AppJson
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.agentbot.AgentTeachRecorder
import io.zer0.muse.data.agentbot.TeachSkillCompiler
import io.zer0.muse.data.card.CardSilentAiCoordinator
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ProactiveTimingEngine
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.data.profile.ProfileShardEngine
import io.zer0.muse.data.profile.ProfileShardRepository
import io.zer0.muse.proactive.ProactiveCandidateFilter
import io.zer0.muse.rag.HybridSearchService
import io.zer0.muse.tools.ShowCardTool
import io.zer0.muse.transformer.InternalMarkupSanitizer
import io.zer0.muse.ui.markdown.CjkLatinSpacing
import io.zer0.muse.ui.markdown.MuseCardBridge
import io.zer0.muse.ui.markdown.appendCardStateToMessage
import io.zer0.muse.ui.markdown.card.CardScript
import io.zer0.muse.ui.markdown.hasUrlAnnotations
import io.zer0.muse.ui.markdown.parseInline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

class V272RoadmapFulfillmentTest {

    @Test
    fun `proactive thought parser extracts after_minutes and ISO at with safety clamping`() {
        val now = 1_795_000_000_000L
        val raw = """
            好的，我先记下了。
            <proactive_thought kind="REMINDER" after_minutes="45">记得按时吃午饭后要吃的药</proactive_thought>
            <proactive_thought kind="REMINDER" after_minutes="1">极短延时也应夹紧到五分钟以上</proactive_thought>
            <proactive_thought kind="FOLLOW_UP" at="2026-11-27T12:00:00Z">确认下午三点演示文稿改完没有</proactive_thought>
        """.trimIndent()

        val extracted = InternalMarkupSanitizer.extractProactiveThoughts(raw)
        assertEquals(3, extracted.size)

        assertEquals(45, extracted[0].triggerAfterMinutes)
        assertEquals(now + 45 * 60_000L, extracted[0].resolveTriggerAtMillis(now))

        // 下限夹紧 >= 5 分钟
        assertEquals(now + 5 * 60_000L, extracted[1].resolveTriggerAtMillis(now))

        val isoResolved = extracted[2].resolveTriggerAtMillis(
            now = java.time.Instant.parse("2026-11-27T10:00:00Z").toEpochMilli(),
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(
            java.time.Instant.parse("2026-11-27T12:00:00Z").toEpochMilli(),
            isoResolved,
        )
    }

    @Test
    fun `proactive candidate filter holds future triggerAt and prioritizes arrived scheduled thoughts`() {
        val now = 1_000_000_000L
        val futureThought = PendingThoughtEntity(
            id = "future",
            assistantId = "default",
            kind = ThoughtKind.REMINDER.name,
            seed = "还未到点",
            createdAt = 100L,
            expiresAt = now + 3_600_000L,
            triggerAt = now + 600_000L,
            weight = 0.99f,
        )
        val untimedThought = PendingThoughtEntity(
            id = "untimed",
            assistantId = "default",
            kind = ThoughtKind.CARE.name,
            seed = "普通关心",
            createdAt = 200L,
            expiresAt = now + 3_600_000L,
            triggerAt = null,
            weight = 0.95f,
        )
        val dueThought = PendingThoughtEntity(
            id = "due",
            assistantId = "default",
            kind = ThoughtKind.REMINDER.name,
            seed = "已到点提醒",
            createdAt = 300L,
            expiresAt = now + 3_600_000L,
            triggerAt = now - 30_000L,
            weight = 0.80f,
        )

        val selected = ProactiveCandidateFilter.select(
            candidates = listOf(futureThought, untimedThought, dueThought),
            intensity = ProactiveIntensity.STANDARD,
            now = now,
        )

        assertEquals(listOf("due", "untimed"), selected.map { it.id })
        assertEquals(
            futureThought.triggerAt,
            ProactiveTimingEngine.nextScheduledTriggerAt(listOf(futureThought, untimedThought, dueThought), now),
        )
    }

    @Test
    fun `quiet hours adjustment shifts overnight triggerAt into allowed daytime window`() {
        val prevTz = TimeZone.getDefault()
        try {
            val tz = TimeZone.getTimeZone("Asia/Shanghai")
            TimeZone.setDefault(tz)
            val night2Am = Calendar.getInstance(tz).apply {
                set(2026, Calendar.NOVEMBER, 27, 2, 15, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val adjusted = ProactiveTimingEngine.adjustTriggerForQuietHours(
                targetAt = night2Am,
                allowedHourStart = 8,
                allowedHourEnd = 22,
                allDayAllowed = false,
            )
            val adjustedCal = Calendar.getInstance(tz).apply { timeInMillis = adjusted }
            assertTrue(adjusted >= night2Am)
            assertEquals(8, adjustedCal.get(Calendar.HOUR_OF_DAY))
        } finally {
            TimeZone.setDefault(prevTz)
        }
    }

    @Test
    fun `card state message round-trip strips invisible state block cleanly`() {
        val appended = appendCardStateToMessage(
            text = "我在五子棋落了一手",
            cardId = "space-gomoku-1",
            data = """{"size":11,"next":"W"}""",
        )
        assertTrue(appended.contains("card_state"))
        assertTrue(appended.contains("\"next\":\"W\""))
        assertEquals("我在五子棋落了一手", InternalMarkupSanitizer.stripForDisplay(appended))
    }

    @Test
    fun `teach recorder and skill compiler preserve structured accessibility clues`() = runTest {
        val recorder = AgentTeachRecorder()
        recorder.start(botId = "default", titleDraft = "每日健康打卡")
        recorder.recordStructuredStep(
            action = "点击健康打卡提交按钮",
            targetApp = "校园通行",
            targetPackage = "edu.example.campus",
            targetText = "提交打卡",
            contentDesc = "提交今日健康状态",
            resourceIdHint = "edu.example.campus:id/btn_submit",
            actionKind = "click",
        )
        val draft = recorder.finish()
        assertNotNull(draft)

        val compiled = TeachSkillCompiler().compileDetailed(draft!!)
        assertEquals(1, compiled.steps.size)
        val step = compiled.steps.first()
        assertEquals("edu.example.campus", step.targetPackage)
        assertEquals("提交打卡", step.targetText)
        assertEquals("提交今日健康状态", step.contentDesc)
        assertEquals("edu.example.campus:id/btn_submit", step.resourceIdHint)
        assertEquals("click", step.actionKind)
        assertTrue(compiled.executionPrompt.contains("控件线索"))
        assertTrue(compiled.executionPrompt.contains("edu.example.campus:id/btn_submit"))
    }

    @Test
    fun `profile shard engine splits five domains enforces budgets and preserves isolated kb ids`() {
        val facts = listOf(
            ProfileShardEngine.ProfileFactInput(1L, "用户名字叫阿青，请称呼我阿青", "identity", pinned = true, importance = 2f),
            ProfileShardEngine.ProfileFactInput(2L, "青霉素和海鲜严重过敏，就医一定要避开", "medical", importance = 2f),
            ProfileShardEngine.ProfileFactInput(3L, "习惯凌晨一点才睡，早上必须喝美式咖啡", "habit", importance = 1f),
            ProfileShardEngine.ProfileFactInput(4L, "性格慢热，和陌生同事沟通喜欢先发文字", "relationship", importance = 1f),
            ProfileShardEngine.ProfileFactInput(5L, "正在做 Android Kotlin 客户端研发，年底有大版本上线", "work", importance = 1f),
            ProfileShardEngine.ProfileFactInput(6L, "喜欢看科幻电影和下五子棋，讨厌香菜", "preference", importance = 1f),
        )

        assertEquals(ProfileShardEngine.ProfileDomain.CORE, ProfileShardEngine.classifyFact(facts[0].content, facts[0].category))
        assertEquals(ProfileShardEngine.ProfileDomain.HEALTH, ProfileShardEngine.classifyFact(facts[1].content, facts[1].category))
        assertEquals(ProfileShardEngine.ProfileDomain.HABITS, ProfileShardEngine.classifyFact(facts[2].content, facts[2].category))
        assertEquals(ProfileShardEngine.ProfileDomain.INTERPERSONAL, ProfileShardEngine.classifyFact(facts[3].content, facts[3].category))
        assertEquals(ProfileShardEngine.ProfileDomain.WORK_STUDY, ProfileShardEngine.classifyFact(facts[4].content, facts[4].category))
        assertEquals(ProfileShardEngine.ProfileDomain.PREFERENCES, ProfileShardEngine.classifyFact(facts[5].content, facts[5].category))

        val coreCard = ProfileShardEngine.buildCoreCard(facts, maxTokens = 120)
        assertTrue(coreCard.contains("阿青"))
        assertTrue(ProfileShardEngine.estimateTokens(coreCard) <= 120)

        val docs = ProfileShardEngine.buildDomainDocuments(facts)
        assertEquals(
            setOf(
                ProfileShardEngine.ProfileDomain.HABITS,
                ProfileShardEngine.ProfileDomain.HEALTH,
                ProfileShardEngine.ProfileDomain.INTERPERSONAL,
                ProfileShardEngine.ProfileDomain.WORK_STUDY,
                ProfileShardEngine.ProfileDomain.PREFERENCES,
            ),
            docs.keys,
        )

        val medicalHit = ProfileShardEngine.selectLocalRelevant(
            query = "今天去医院开药要注意什么过敏史",
            domainDocs = docs,
            maxTokens = 350,
        )
        assertTrue(medicalHit.contains("青霉素"))
        assertTrue(ProfileShardEngine.estimateTokens(medicalHit) <= 350)

        assertEquals("profile_shards:bot-1", ProfileShardRepository.kbIdFor("bot-1"))
        assertEquals(
            "profile_shard_bot-1_health",
            ProfileShardRepository.docIdFor("bot-1", ProfileShardEngine.ProfileDomain.HEALTH),
        )
    }

    @Test
    fun `chat preferences persists independent aboutYouProfileEnabled switch`() {
        val defaultPrefs = ChatPreferences()
        assertTrue(defaultPrefs.aboutYouProfileEnabled)
        val encoded = AppJson.encodeToString(ChatPreferences.serializer(), defaultPrefs.copy(aboutYouProfileEnabled = false))
        val decoded = AppJson.decodeFromString(ChatPreferences.serializer(), encoded)
        assertFalse(decoded.aboutYouProfileEnabled)
        assertNull(ProfileShardEngine.ProfileDomain.fromKey("nonexistent"))
    }

    @Test
    fun `card silent AI bridge exposes ask and saveState capabilities and prompt guidance`() = runTest {
        val bridgeWithAsk = MuseCardBridge(
            onAction = {},
            cardId = "card-gomoku",
            onSilentAsk = { _, _ -> },
            onSaveState = { _, _ -> },
        )
        val caps = bridgeWithAsk.capabilities()
        assertTrue(caps.contains("\"ask\":\"available\""))
        assertTrue(caps.contains("\"saveState\":\"available\""))
        assertTrue(CardScript.build().contains("window.muse.ask = function"))
        assertTrue(CardScript.build().contains("window.muse.saveState = function"))
        assertTrue(ShowCardTool.toolDef().description.contains("window.muse.ask"))

        val coord = CardSilentAiCoordinator()
        val invalid = coord.handleAsk("card-gomoku", "{not-json}")
        assertTrue(invalid.contains("\"ok\":false"))
        val obj = coord.extractJsonObject(
            """```json
            {"r":5,"c":6,"comment":"稳一手"}
            ```
            """.trimIndent(),
        )
        assertEquals("5", obj?.get("r").toString())
    }

    @Test
    fun `cjk latin spacing formats mixed prose cleanly without corrupting urls formulas or fast path`() {
        assertEquals("plain paragraph", CjkLatinSpacing.format("plain paragraph"))
        assertEquals("用\u2009Kotlin\u2009写\u20092\u2009个函数", CjkLatinSpacing.format("用Kotlin写2个函数"))
        assertEquals(
            "访问 https://example.com/a1b2 查看",
            CjkLatinSpacing.format("访问 https://example.com/a1b2 查看"),
        )
        assertEquals("计算\$x_1+y_2\$的结果", CjkLatinSpacing.format("计算\$x_1+y_2\$的结果"))

        val parsed = parseInline("查看`MainActivity`文档或[GitHub](https://github.com)主页")
        assertTrue(parsed.text.contains("查看\u2009\u200AMainActivity\u200A\u2009文档"))
        assertTrue(hasUrlAnnotations(parsed))
        assertEquals(
            "https://github.com",
            parsed.getStringAnnotations("URL", 0, parsed.length).first().item,
        )
    }

    @Test
    fun `profile shard repository stores standalone json outside knowledge base and retrieves locally`() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("profile_shard_repo_test").toFile()
        try {
            val facts = listOf(
                ProfileShardEngine.ProfileFactInput(
                    id = 1L,
                    content = "对青霉素过敏，去医院开药前一定要先说明",
                    category = "health",
                    importance = 2f,
                    pinned = false,
                ),
                ProfileShardEngine.ProfileFactInput(
                    id = 2L,
                    content = "每天凌晨两点睡，早上有早八课",
                    category = "habit",
                    importance = 1f,
                    pinned = false,
                ),
            )
            val writerRepo = ProfileShardRepository(storageDir = tempDir)
            writerRepo.syncShards("bot/alpha", facts)
            val shardFile = java.io.File(tempDir, "bot_alpha.json")
            assertTrue("分域应落盘为独立 JSON 文件", shardFile.exists())

            val readerRepo = ProfileShardRepository(storageDir = tempDir)
            val healthHit = readerRepo.retrieveRelevantShards(
                assistantId = "bot/alpha",
                query = "明天去医院挂号要注意什么过敏",
                maxTokens = 300,
            )
            assertTrue("重读独立分域文件应检索出过敏事实", healthHit.contains("青霉素"))

            readerRepo.deleteDomainShard("bot/alpha", ProfileShardEngine.ProfileDomain.HEALTH)
            val afterDelete = readerRepo.retrieveRelevantShards(
                assistantId = "bot/alpha",
                query = "明天去医院挂号要注意什么过敏",
                maxTokens = 300,
            )
            assertFalse("删除医疗分域后不应再命中", afterDelete.contains("青霉素"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `profile recall uses current facts from only the selected space after a fact is removed`() = runTest {
        val fact = ProfileShardEngine.ProfileFactInput(
            id = 1,
            content = "对青霉素过敏",
            category = "health",
            importance = 4f,
            pinned = false,
        )
        var activeFacts = listOf(fact)
        val repository = ProfileShardRepository(currentFacts = { scope ->
            if (scope.scope == "main" && scope.spaceId == "medical") activeFacts else emptyList()
        })
        repository.syncShards("default", listOf(fact))
        val query = "去医院要说明什么过敏"
        assertTrue(repository.retrieveRelevantShards("default", query, spaceId = "medical").contains("青霉素"))
        assertTrue(repository.retrieveRelevantShards("default", query, spaceId = "work").isEmpty())
        activeFacts = emptyList()
        assertTrue(repository.retrieveRelevantShards("default", query, spaceId = "medical").isEmpty())
    }

    @Test
    fun `rag sanitization strips card state and lexical relevance rejects unrelated vocabulary hits`() {
        val rawPrompt = """
            帮我生成一个卡片我想测试一下 [card_state]{"cardId":"msg-1","倍率":88,"时间":"15:48"}[/card_state]
        """.trimIndent()
        val sanitized = HybridSearchService.sanitizeQueryForRag(rawPrompt)
        assertFalse(sanitized.contains("card_state"))
        assertFalse(sanitized.contains("88"))

        val strayVocabHit = HybridSearchService.computeLexicalRelevance(
            query = "帮我做一个五子棋交互卡片",
            content = "### Scene & Time Vocabulary\n- 时间 time\n- 场景 scene",
            title = "05-主题场景词汇.md",
        )
        assertTrue(
            "纯词库擦边单字命中不应越过未打分 FTS4 门槛: actual=$strayVocabHit",
            strayVocabHit < HybridSearchService.MIN_UNSCORED_LEXICAL_RELEVANCE,
        )

        val realHit = HybridSearchService.computeLexicalRelevance(
            query = "Android 10 proot 加载 ELF 执行约束",
            content = "Android 10+ 禁止 exec 应用可写目录，proot 负责加载 ELF 二进制。",
            title = "proot 运行时架构",
        )
        assertTrue(
            "真实多词命中应高于未打分 FTS4 门槛: actual=$realHit",
            realHit >= HybridSearchService.MIN_UNSCORED_LEXICAL_RELEVANCE,
        )
    }
}
