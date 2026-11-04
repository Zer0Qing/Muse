package io.zer0.memory.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动摘要的格式契约（`:memory` 体检第四处）。
 *
 * 摘要是 `compileFacts` 的输入：**这里抽不出 facts 段，记忆里就直接少一批事实**，
 * 而且不会报错 —— 用户只会发现"上周说过的话它不记得了"。
 * 所以逐条钉：标题怎么认、正文到哪收、空标记怎么判、写前校验拦哪四类。
 */
class RollingSummaryFormatTest {

    private val zhSummary = """
### 重要事实
- 对青霉素过敏
- 喜欢美式咖啡

### 事情经过
- 讨论了体检安排
    """.trimIndent()

    private val enSummary = """
### Key Facts
- allergic to penicillin

### Timeline
- discussed the checkup
    """.trimIndent()

    @Test
    fun `中英两支的段落都抽得出来`() {
        assertEquals("- 对青霉素过敏\n- 喜欢美式咖啡", RollingSummaryFormat.extractFactSection(zhSummary))
        assertEquals("- allergic to penicillin", RollingSummaryFormat.extractFactSection(enSummary))
        assertEquals(
            "- discussed the checkup",
            RollingSummaryFormat.extractMarkdownSection(enSummary, RollingSummaryFormat.TIMELINE_SECTION_TITLES),
        )
    }

    @Test
    fun `标题层级与大小写都不影响识别`() {
        val variants = listOf(
            "## 重要事实\n- a",
            "###### 重要事实\n- a",
            "### key facts\n- a",
            "###  Key Facts  \n- a",
            "# 重要事实 #\n- a",
        )
        variants.forEach { markdown ->
            assertTrue("该认出标题: ${markdown.lineSequence().first()}", RollingSummaryFormat.hasFactSectionHeading(markdown))
            assertEquals("正文要抽到", "- a", RollingSummaryFormat.extractFactSection(markdown))
        }
    }

    @Test
    fun `正文收到下一个同级标题为止 更深层级的子标题算正文`() {
        val markdown = """
### 重要事实
- 一条

#### 分类：医疗
- 细分的一条

### 事情经过
- 经过
        """.trimIndent()

        val facts = RollingSummaryFormat.extractFactSection(markdown)
        assertTrue(facts.contains("一条"))
        assertTrue("子标题下的内容不该被切掉", facts.contains("细分的一条"))
        assertFalse("不该把下一段的内容也算进来", facts.contains("经过"))
    }

    @Test
    fun `空标记认得出 有内容时不认成空`() {
        assertTrue(RollingSummaryFormat.isEmptyFactSection(""))
        assertTrue(RollingSummaryFormat.isEmptyFactSection("   \n  \n"))
        assertTrue(RollingSummaryFormat.isEmptyFactSection("- 无"))
        assertTrue(RollingSummaryFormat.isEmptyFactSection("* None"))
        assertTrue(RollingSummaryFormat.isEmptyFactSection("- 无\n- None"))
        assertFalse(RollingSummaryFormat.isEmptyFactSection("- 对青霉素过敏"))
        assertFalse("混了真实条目就不算空", RollingSummaryFormat.isEmptyFactSection("- 无\n- 喜欢咖啡"))
    }

    @Test
    fun `重复出现同名标题时 不该只取第一段`() {
        // 模型确实会重复模板标题（补一段"补充事实"）。只取第一段的话，
        // 第二段里的事实会被**静默丢掉**，而写前校验看不出问题（第一段非空）。
        val markdown = """
### 重要事实
- 第一条

### 事情经过
- 经过

### 重要事实
- 第二条
        """.trimIndent()

        val facts = RollingSummaryFormat.extractFactSection(markdown)

        assertTrue("第一段要在: $facts", facts.contains("第一条"))
        assertTrue("重复标题里的第二段也要在: $facts", facts.contains("第二条"))
    }

    @Test
    fun `写前校验拦住四类破坏提取的情况`() {
        val noFacts = RollingSummaryFormat.validate("### 事情经过\n- x")
        assertFalse("缺 facts 标题要报", noFacts.ok)
        assertTrue(noFacts.issues.any { it.contains("fact section") })

        val noTimeline = RollingSummaryFormat.validate("### 重要事实\n- x")
        assertFalse("缺 timeline 标题要报", noTimeline.ok)
        assertTrue(noTimeline.issues.any { it.contains("timeline section") })

        val emptyBody = RollingSummaryFormat.validate("### 重要事实\n\n### 事情经过\n- x")
        assertFalse("facts 段空正文要报（契约要求显式写 - 无）", emptyBody.ok)
        assertTrue(emptyBody.issues.any { it.contains("empty") })

        val nested = RollingSummaryFormat.validate("### 重要事实\n- x\n#### 事情经过\n- y")
        assertFalse("timeline 更深会让 facts 段收不了尾", nested.ok)
        assertTrue(nested.issues.any { it.contains("nested deeper") })

        assertTrue("正常摘要该通过", RollingSummaryFormat.validate(zhSummary).ok)
        assertTrue(RollingSummaryFormat.validate(enSummary).ok)
    }

    @Test
    fun `校验失败原因要能进修复输入 空白原因不产出空行`() {
        val input = RollingSummaryFormat.buildRepairInput("zh-CN", listOf("缺 facts 标题", "   "), "### 草稿")

        assertTrue(input.contains("## 校验失败原因"))
        assertTrue(input.contains("- 缺 facts 标题"))
        assertTrue("空白原因要被滤掉，不留 \"- \" 空行", !input.contains("\n- \n"))
        assertTrue("草稿要包起来，免得模型把它当指令", input.contains("<draft-summary>"))

        val unknown = RollingSummaryFormat.buildRepairInput("en-US", emptyList(), "")
        assertTrue("没给原因时要有占位", unknown.contains("- unknown"))
    }

    @Test
    fun `格式要求文案里的标题文本与实际解析用的是同一份`() {
        // 这句看着像废话，但它是这类 bug 的源头：文案里写了个别的标题，
        // 模型照着输出，解析端按另一份词表找 —— 摘要永远抽不出事实。
        val requirementsZh = RollingSummaryFormat.buildFormatRequirements("zh-CN")
        val requirementsEn = RollingSummaryFormat.buildFormatRequirements("en-US")

        assertTrue(requirementsZh.contains(RollingSummaryFormat.getFactSectionTitle("zh-CN")))
        assertTrue(requirementsZh.contains(RollingSummaryFormat.getTimelineSectionTitle("zh-CN")))
        assertTrue(requirementsEn.contains(RollingSummaryFormat.getFactSectionTitle("en-US")))
        assertTrue(requirementsEn.contains(RollingSummaryFormat.getTimelineSectionTitle("en-US")))
        assertTrue("修复指令要带上同一份要求", RollingSummaryFormat.buildRepairPrompt("zh-CN").contains("### 重要事实"))
    }

    @Test
    fun `空输入不抛异常`() {
        assertEquals("", RollingSummaryFormat.extractFactSection(""))
        assertEquals("", RollingSummaryFormat.extractMarkdownSection("", RollingSummaryFormat.FACT_SECTION_TITLES))
        assertFalse(RollingSummaryFormat.hasFactSectionHeading(""))
        assertFalse(RollingSummaryFormat.validate("").ok)
    }
}
