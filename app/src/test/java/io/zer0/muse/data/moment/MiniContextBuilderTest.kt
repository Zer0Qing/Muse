package io.zer0.muse.data.moment

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.UserProfile
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.transformer.SystemPromptAssembler
import io.zer0.muse.worldbook.WorldBookEntryEntity
import io.zer0.muse.worldbook.WorldBookHook
import io.zer0.muse.worldbook.WorldBookRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 回归：小手机 / 朋友圈 / 日记等旁路生成必须走完整生成链路。
 *
 * 修复前的缺陷：这些场景只做 `"你是 $name" + 素材` 的三段拼接，绕过
 * [SystemPromptAssembler]，导致世界书 / 记忆 / 用户画像 / 提示词注入全部不生效。
 * 本测试锁住"世界书条目确实被注入到了最终消息里"这条不变量。
 */
class MiniContextBuilderTest {

    private val assembler = mockk<SystemPromptAssembler>()
    private val lorebookRepository = mockk<LorebookRepository>(relaxed = true)
    private val promptInjectionRepository = mockk<PromptInjectionRepository>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    private val worldBookRepository = mockk<WorldBookRepository>(relaxed = true)

    private fun builder(withWorldBook: Boolean = false) = MiniContextBuilder(
        systemPromptAssembler = assembler,
        lorebookRepository = lorebookRepository,
        promptInjectionRepository = promptInjectionRepository,
        settingsRepository = settingsRepository,
        worldBookHook = if (withWorldBook) WorldBookHook(worldBookRepository) else null,
    )

    private fun assistant(lorebookIds: String = """["lb1"]""", injectionIds: String = "[]", memoryEnabled: Boolean = true) =
        AssistantEntity(
            id = "asst-1",
            name = "Muse",
            systemPrompt = "你是一个温柔的角色。",
            memoryEnabled = memoryEnabled,
            lorebookIdsJson = lorebookIds,
            modeInjectionIdsJson = injectionIds,
        )

    private fun lorebookEntry() = LorebookEntity(
        id = "lb1",
        name = "巫师协会",
        keywordsJson = """["魔法"]""",
        content = "巫师协会是一个古老的秘密组织。",
    )

    @Test
    fun `buildMessages 走 SystemPromptAssembler 而不是裸拼人格`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "完整系统提示"))

        coEvery { settingsRepository.getUserProfile() } returns UserProfile()
        val result = builder().buildMessages(assistant(), "我要发一条朋友圈")

        // 系统提示来自 Assembler，而非自己拼的"你是 XX"
        assertTrue(
            "系统提示必须来自 SystemPromptAssembler",
            result.any { it.role == MessageRole.SYSTEM && it.content == "完整系统提示" },
        )
        // 最后一条是用户素材
        assertEquals(MessageRole.USER, result.last().role)
        assertTrue(result.last().content.contains("朋友圈"))
    }

    @Test
    fun `助手绑定的世界书关键词命中后注入 lorebook 标签`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "系统提示"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()
        val entry = lorebookEntry()
        coEvery { lorebookRepository.getByIdsEnabled(listOf("lb1")) } returns listOf(entry)
        // matchAgainst 是纯函数，直接返回命中结果
        every { lorebookRepository.matchAgainst(any(), any()) } returns listOf(entry)

        val result = builder().buildMessages(assistant(), "今天想聊聊魔法")

        val joined = result.joinToString("\n") { it.content }
        assertTrue(
            "世界书条目必须被注入到消息列表\n实际内容:\n$joined",
            joined.contains("<lorebook") && joined.contains("巫师协会是一个古老的秘密组织"),
        )
        coVerify { lorebookRepository.getByIdsEnabled(listOf("lb1")) }
    }

    @Test
    fun `未绑定世界书时不查询条目且不注入`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "系统提示"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()

        val result = builder().buildMessages(assistant(lorebookIds = "[]"), "随便说点什么")

        val joined = result.joinToString("\n") { it.content }
        assertTrue("未绑定世界书时不应出现 lorebook 标签", !joined.contains("<lorebook"))
        coVerify(exactly = 0) { lorebookRepository.getByIdsEnabled(any()) }
    }

    @Test
    fun `提示词注入条目也会被注入`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "系统提示"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()
        coEvery { lorebookRepository.getByIdsEnabled(any()) } returns emptyList()
        val injection = PromptInjectionEntity(
            id = "inj1",
            name = "风格约束",
            content = "回复必须简短。",
            mode = "default",
            enabled = true,
        )
        coEvery { promptInjectionRepository.getByIdsEnabled(listOf("inj1")) } returns listOf(injection)

        val result = builder().buildMessages(
            assistant(lorebookIds = "[]", injectionIds = """["inj1"]"""),
            "写点什么",
        )

        val joined = result.joinToString("\n") { it.content }
        assertTrue("提示词注入必须生效\n实际内容:\n$joined", joined.contains("回复必须简短"))
    }

    @Test
    fun `私信路径的系统指令带上语音标记约定`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "完整系统提示"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()

        val result = builder().buildMessages(assistant(lorebookIds = "[]"), "在吗", voiceMessageEnabled = true)

        val systemText = result.filter { it.role == MessageRole.SYSTEM }.joinToString("\n") { it.content }
        assertTrue("私信必须下发语音约定\n实际内容:\n$systemText", systemText.contains(voiceMessageInstruction()))
        assertTrue("标记写法要写清楚", systemText.contains("[语音]") && systemText.contains("[/语音]"))
        assertEquals("约定排在用户素材之前且素材仍是最后一条", MessageRole.USER, result.last().role)
    }

    @Test
    fun `朋友圈路径不带语音标记约定`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "完整系统提示"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()

        val result = builder().buildMessages(assistant(lorebookIds = "[]"), "请发一条朋友圈动态")

        val systemText = result.filter { it.role == MessageRole.SYSTEM }.joinToString("\n") { it.content }
        assertFalse("朋友圈没有语音出口，不应出现标记\n实际内容:\n$systemText", systemText.contains("[语音]"))
        assertFalse(systemText.contains(voiceMessageInstruction()))
    }

    @Test
    fun `Assembler 抛异常时退化为空系统提示而不崩溃`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } throws IllegalStateException("boom")
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()

        val result = builder().buildMessages(assistant(lorebookIds = "[]"), "内容")

        // 至少保留用户消息，不能整体失败
        assertEquals(MessageRole.USER, result.last().role)
        assertTrue(result.last().content.contains("内容"))
    }

    @Test
    fun `动态世界书的关键词条目命中后也进旁路生成`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "系统提示"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()
        coEvery { lorebookRepository.getByIdsEnabled(any()) } returns emptyList()
        coEvery {
            worldBookRepository.getKeywordEntries(any(), "asst-1")
        } returns listOf(
            WorldBookEntryEntity(
                id = "wb1",
                name = "北境",
                keywordsJson = """["北境"]""",
                content = "北境常年下雪，夜里能听见冰裂。",
            ),
        )

        val result = builder(withWorldBook = true).buildMessages(assistant(lorebookIds = "[]"), "北境冷吗")

        val joined = result.joinToString("\n") { it.content }
        assertTrue(
            "动态世界书的关键词条目必须进小手机的生成上下文\n实际内容:\n$joined",
            joined.contains("<worldbook name=\"北境\">") && joined.contains("北境常年下雪"),
        )
    }

    @Test
    fun `没命中关键词时不出现世界书块`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "系统提示"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()
        coEvery { lorebookRepository.getByIdsEnabled(any()) } returns emptyList()
        coEvery { worldBookRepository.getKeywordEntries(any(), any()) } returns emptyList()

        val result = builder(withWorldBook = true).buildMessages(assistant(lorebookIds = "[]"), "今天吃什么")

        val joined = result.joinToString("\n") { it.content }
        assertFalse("没命中就不该注入世界书块\n实际内容:\n$joined", joined.contains("<worldbook"))
    }

    @Test
    fun `世界书查询抛异常时按未命中处理而不是整条回复失败`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "系统提示"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()
        coEvery { lorebookRepository.getByIdsEnabled(any()) } returns emptyList()
        coEvery { worldBookRepository.getKeywordEntries(any(), any()) } throws IllegalStateException("db closed")

        val result = builder(withWorldBook = true).buildMessages(assistant(lorebookIds = "[]"), "北境冷吗")

        assertEquals("失败时仍要保留用户素材", MessageRole.USER, result.last().role)
        assertTrue(result.last().content.contains("北境"))
    }

    @Test
    fun `没接世界书钩子时行为与以前一致`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "系统提示"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()
        coEvery { lorebookRepository.getByIdsEnabled(any()) } returns emptyList()

        val result = builder(withWorldBook = false).buildMessages(assistant(lorebookIds = "[]"), "北境冷吗")

        coVerify(exactly = 0) { worldBookRepository.getKeywordEntries(any(), any()) }
        assertEquals(MessageRole.USER, result.last().role)
    }
}
