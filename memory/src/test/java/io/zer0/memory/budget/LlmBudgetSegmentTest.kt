package io.zer0.memory.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.79 (B-20): 按段优先级裁剪的测试。
 *
 * 覆盖审计 B-20 的三个判定:
 *  ① 总预算充足时四段全保留(返回原文)
 *  ② 预算不足时 longterm/facts 保留、today/week 被裁剪
 *  ③ 有截断时产生日志(Logger.w)且不抛异常
 *
 * 样本 markdown 格式与 MemoryCompiler.assembleCompiledMarkdown 一致:
 * 四段顺序 facts → today → week → longterm,段间空行,整体以换行收尾。
 *
 * 预算选择的稳健性:high 段(facts/longterm)正文极短,成本很小;
 * today/week 用大量行撑大成本,使得"high 成本 < 预算 < 全文成本"区间很宽,
 * 即便 BPE 与字符启发式估算略有出入,按段裁剪路径也能稳定触发。
 */
class LlmBudgetSegmentTest {

    private fun factsBody() = "事实甲:用户喜欢 Kotlin\n事实乙:用户开发 Android"

    private fun longtermBody() = "长期记忆 A 非常重要完全保留\n长期记忆 B 同样重要"

    private fun hugeBody(prefix: String, lines: Int = 80): String =
        (1..lines).joinToString("\n") { "$prefix 填充行 $it 用于把本段撑大远超预算" }

    /** 构造一段与 assembleCompiledMarkdown 输出格式一致的 4 段 markdown。 */
    private fun sampleMarkdown(): String = listOf(
        "## 重要事实",
        factsBody(),
        "## 今天",
        hugeBody("今天"),
        "## 本周早些时候",
        hugeBody("本周"),
        "## 长期情况",
        longtermBody(),
    ).joinToString("\n\n") + "\n"

    // ── ① 预算充足:四段全保留 ───────────────────────────────────────────

    @Test
    fun `预算充足时返回原文`() {
        val md = sampleMarkdown()
        val result = LlmBudget.truncateToTokenBudget(md, 2_000_000)
        // 预算远大于文本,应原样返回(不带截断标记,顺序一致)
        assertEquals(md, result)
        assertFalse("预算充足时不应出现截断标记", result.contains("(memory truncated)"))
    }

    @Test
    fun `预算充足时全部四个标题均保留`() {
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 2_000_000)
        listOf("## 重要事实", "## 今天", "## 本周早些时候", "## 长期情况").forEach { heading ->
            assertTrue("预算充足应保留 $heading: $result", result.contains(heading))
        }
    }

    // ── ② 预算不足:longterm/facts 保留、today/week 被裁剪 ────────────────

    @Test
    fun `预算不足时保留 longterm 与 facts 完整内容`() {
        // 预算需满足: high(facts+longterm) < budget < 全文成本
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 300)
        // longterm 是末段(最长期记忆),必须完整保留
        assertTrue("长期记忆应被完整保留: $result", result.contains(longtermBody()))
        // facts 是首段,高优先级,也应完整保留
        assertTrue("facts 应被完整保留: $result", result.contains(factsBody()))
    }

    @Test
    fun `预算不足时 today 至少被裁剪且保留 longterm`() {
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 300)
        // today 是被撑大的中段(低优先级),其尾部应被裁剪
        assertTrue("中间段被撑大后应触发截断标记: $result", result.contains("(memory truncated)"))
        // longterm 标题稳定在
        assertTrue("longterm 标题应仍在: $result", result.contains("## 长期情况"))
        // longterm 正文完整,不被尾部截断丢弃
        assertTrue("longterm 正文应完整: $result", result.contains(longtermBody()))
    }

    @Test
    fun `预算不足时不把 longterm 排到最早丢弃`() {
        // 核心回归:B-20 之前尾部截断会先丢 longterm;现在末段 longterm 应仍在文本末尾
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 300)
        val longtermIdx = result.indexOf("## 长期情况")
        assertTrue("longterm 标题应存在: $result", longtermIdx >= 0)
        // longterm 标题不应缺失,且内容完整(而非仅剩空标题)
        assertTrue(result.contains(longtermBody()))
    }

    // ── ③ 有截断时产生日志且不抛异常 ────────────────────────────────────

    @Test
    fun `超预算截断出结果且不抛异常`() {
        // 无论预算多小,都应安全返回非空白结果,不抛异常
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 3)
        assertTrue("结果不能为空字符串", result.isNotBlank())
    }

    @Test
    fun `空文本与预算为 0 保持原行为`() {
        assertEquals("", LlmBudget.truncateToTokenBudget("", 100))
        val md = sampleMarkdown()
        // budget=0 视为不限制,返回原文
        assertEquals(md, LlmBudget.truncateToTokenBudget(md, 0))
    }

    // ── 纯函数拆段 / 裁剪逻辑 ────────────────────────────────────────────

    @Test
    fun `splitSegments 纯函数正确切分四段`() {
        val segments = LlmBudget.splitSegments(sampleMarkdown())
        assertEquals(4, segments.size)
        assertEquals("## 重要事实", segments[0].heading)
        assertEquals("## 长期情况", segments[3].heading)
        assertTrue("末段应为 longterm 正文", segments[3].body.contains("长期记忆 A"))
    }

    @Test
    fun `truncateBySegments 纯函数只裁剪中段不动高优先级段`() {
        val segments = LlmBudget.splitSegments(sampleMarkdown())
        val result = LlmBudget.truncateBySegments(segments, budget = 300)
        // 首段 facts 与末段 longterm 高优先级完整保留
        assertEquals("## 重要事实", result.first().heading)
        assertEquals("## 长期情况", result.last().heading)
        assertEquals(longtermBody(), result.last().body)
    }
}
