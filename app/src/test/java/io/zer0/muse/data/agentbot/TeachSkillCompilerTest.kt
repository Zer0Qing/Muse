package io.zer0.muse.data.agentbot

import io.zer0.muse.tools.SkillImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachSkillCompilerTest {

    private val fixedNow = 1_700_000_000_000L

    @Test
    fun `compile maps recorded steps into a prompt skill without a model`() = runBlocking {
        val compiler = compiler()
        val compiled = compiler.compileDetailed(draft())

        assertEquals(2, compiled.steps.size)
        assertEquals("点击「新建日程」", compiled.steps[0].action)
        assertEquals("com.example.calendar", compiled.steps[0].targetApp)
        assertEquals("teach_test_skill", compiled.skill.id)
        assertEquals("user", compiled.skill.category)
        assertEquals(SkillImporter.EMPTY_PARAMETERS_JSON, compiled.skill.parametersJson)
        assertEquals("[]", compiled.skill.requiredJson)
        assertEquals(fixedNow, compiled.skill.createdAt)
        assertTrue(SkillImporter.isPromptSkill(compiled.skill.implementationKotlin))
        assertEquals(compiled.executionPrompt, SkillImporter.decodePromptText(compiled.skill.implementationKotlin))

        val promptText = SkillImporter.decodePromptText(compiled.skill.implementationKotlin).orEmpty()
        assertTrue(promptText.contains("点击「新建日程」"))
        assertTrue(promptText.contains("输入「备注」（已脱敏，3 字符）"))
    }

    @Test
    fun `model refinement supplies title description and confirmation index`() = runBlocking {
        val compiler = compiler(
            refiner = TeachSkillRefiner {
                """{"name":"每日日程整理","description":"按示范创建明天的日程","confirmStepNumbers":[1]}"""
            },
        )
        val compiled = compiler.compileDetailed(draft())

        assertEquals("每日日程整理", compiled.skill.name)
        assertEquals("按示范创建明天的日程", compiled.skill.description)
        assertEquals(1, compiled.confirmationPoints.single().stepIndex)
        assertEquals("点击「新建日程」", compiled.confirmationPoints.single().action)
    }

    @Test
    fun `high risk actions are always marked for human confirmation`() = runBlocking {
        val riskyDraft = draft(
            goalPrompt = """
                请按我之前录制的以下流程完成「资金处理」：
                1. （com.example.chat）点击「转账」
                2. （com.example.files）点击「删除」
                3. （com.example.calendar）点击「查看日程」
            """.trimIndent(),
            stepsSummary = "点击「转账」 → 点击「删除」 → 点击「查看日程」",
        )
        val compiled = compiler().compileDetailed(riskyDraft)

        assertEquals(listOf(1, 2), compiled.confirmationPoints.map { it.stepIndex })
        assertTrue(compiled.confirmationPoints[0].reason.contains("转账"))
        assertTrue(compiled.confirmationPoints[1].reason.contains("删除"))
        assertFalse(compiled.steps[2].requiresConfirmation)
    }

    @Test
    fun `model confirmation is merged with rule based risk detection`() = runBlocking {
        val compiler = compiler(
            refiner = TeachSkillRefiner {
                """{"name":"组合确认","description":"模型只标了第一步","confirmStepNumbers":[1]}"""
            },
        )
        val riskyDraft = draft(
            goalPrompt = """
                请按我之前录制的以下流程完成「组合操作」：
                1. （com.example.calendar）点击「新建日程」
                2. （com.example.chat）点击「发送消息」
            """.trimIndent(),
            stepsSummary = "点击「新建日程」 → 点击「发送消息」",
        )
        val compiled = compiler.compileDetailed(riskyDraft)

        assertEquals(listOf(1, 2), compiled.confirmationPoints.map { it.stepIndex })
        assertEquals("发送", compiled.confirmationPoints[1].reason.substringAfter("「").substringBefore("」"))
    }

    @Test
    fun `malformed model response falls back to deterministic compilation`() = runBlocking {
        val compiler = compiler(refiner = TeachSkillRefiner { "not json at all" })
        val compiled = compiler.compileDetailed(draft())

        assertEquals("测试流程", compiled.skill.name)
        assertTrue(compiled.skill.description.contains("2 步"))
        assertEquals(2, compiled.steps.size)
    }

    @Test
    fun `model exception falls back instead of dropping the recording`() = runBlocking {
        val compiler = compiler(
            refiner = TeachSkillRefiner { throw IllegalStateException("model unavailable") },
        )
        val compiled = compiler.compileDetailed(draft())

        assertEquals(2, compiled.steps.size)
        assertNotNull(compiled.skill)
    }

    @Test
    fun `prompt skill carries explicit pause instructions for every confirmation point`() = runBlocking {
        val compiled = compiler().compileDetailed(draft())
        val prompt = SkillImporter.decodePromptText(compiled.skill.implementationKotlin).orEmpty()

        compiled.confirmationPoints.forEach { point ->
            assertTrue(prompt.contains("第 ${point.stepIndex} 步"))
            assertTrue(prompt.contains(point.action))
            assertTrue(prompt.contains("必须暂停"))
        }
    }

    @Test
    fun `risk classifier covers payment account and destructive actions`() {
        assertNotNull(TeachSkillCompiler.classifyRisk("确认支付 20 元", "com.example.shop"))
        assertNotNull(TeachSkillCompiler.classifyRisk("输入登录密码", "com.example.app"))
        assertNotNull(TeachSkillCompiler.classifyRisk("清空聊天记录", "com.example.chat"))
        assertNotNull(TeachSkillCompiler.classifyRisk("Open wallet balance", "com.example.bank"))
        assertEquals(null, TeachSkillCompiler.classifyRisk("查看今日日程", "com.example.calendar"))
    }

    private fun compiler(refiner: TeachSkillRefiner? = null): TeachSkillCompiler =
        TeachSkillCompiler(
            refiner = refiner,
            now = { fixedNow },
            idFactory = { "teach_test_skill" },
        )

    private fun draft(
        goalPrompt: String = """
            请按我之前录制的以下流程完成「测试流程」：
            1. （com.example.calendar）点击「新建日程」
            2. （com.example.calendar）输入「备注」（已脱敏，3 字符）
        """.trimIndent(),
        stepsSummary: String = "点击「新建日程」 → 输入「备注」（已脱敏，3 字符）",
    ): AgentTeachRecorder.FinishedDraft = AgentTeachRecorder.FinishedDraft(
        botId = "bot-test",
        title = "测试流程",
        goalPrompt = goalPrompt,
        stepsSummary = stepsSummary,
    )
}