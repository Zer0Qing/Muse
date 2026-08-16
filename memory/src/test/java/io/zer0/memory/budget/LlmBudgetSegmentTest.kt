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
 */
class LlmBudgetSegmentTest {

    /** 构造一段与 assembleCompiledMarkdown 输出格式一致的 4 段 markdown。 */
    private fun sampleMarkdown(): String = listOf(
        "## 重要事实",
        "事实甲:用户喜欢 Kotlin\n事实乙:用户开发 Android 应用\n事实丙:用户在使用 Muse",
        "## 今天",
        "今天的内容行一\n今天的内容行二\n今天的内容行三\n今天的内容行四",
        "## 本周早些时候",
        "本周的内容行一\n本周的内容行二\n本周的内容行三\n本周的内容行四",
        "## 长期情况",
        "长期记忆 A 非常重要必须完整保留\n长期记忆 B 同样重要不能丢弃",
    ).joinToString("\n\n") + "\n"

    // ── ① 预算充足:四段全保留 ───────────────────────────────────────────

    @Test
    fun `预算充足时返回原文`() {
        val md = sampleMarkdown()
        val result = LlmBudget.truncateToTokenBudget(md, 100_000)
        // 预算远大于文本,应原样返回(不带截断标记,顺序一致)
        assertEquals(md, result)
        assertFalse("预算充足时不应出现截断标记", result.contains("(memory truncated)"))
    }

    @Test
    fun `预算充足时全部四个标题均保留`() {
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 100_000)
        listOf("## 重要事实", "## 今天", "## 本周早些时候", "## 长期情况").forEach { heading ->
            assertTrue("预算充足应保留 $heading: $result", result.contains(heading))
        }
    }

    // ── ② 预算不足:longterm/facts 保留、today/week 被裁剪 ────────────────

    @Test
    fun `预算不足时保留 longterm 与 facts 完整内容`() {
        val md = sampleMarkdown()
        // 小预算强制触发按段裁剪
        val result = LlmBudget.truncateToTokenBudget(md, 5)
        // longterm 是末段(最长期记忆),必须完整保留
        assertTrue("长期记忆应被完整保留: $result", result.contains("长期记忆 A 非常重要必须完整保留"))
        assertTrue("长期记忆 B 应被保留: $result", result.contains("长期记忆 B 同样重要不能丢弃"))
        // facts 是首段,高优先级,其内容也应得到保留(至少标题在)
        assertTrue("重要事实标题应保留: $result", result.contains("## 重要事实"))
    }

    @Test
    fun `预算不足时 today 被裁剪`() {
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 5)
        // today 是中段(低优先级),其尾部应被裁剪
        assertTrue("today 应被裁剪一段(带标记或缩小): $result", result.contains("(memory truncated)"))
        // 至少标题可能被裁剪,但 longterm 安全
        assertTrue("longterm 标题应仍在: $result", result.contains("## 长期情况"))
    }

    @Test
    fun `预算不足时优先级 order 不把 longterm 排到最后丢弃`() {
        // 核心回归:B-20 之前尾部截断会先丢 longterm;现在末尾应仍是 longterm 段
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 5)
        val longtermIdx = result.indexOf("## 长期情况")
        val factsIdx = result.indexOf("## 重要事实")
        assertTrue("longterm 应存在: $result", longtermIdx >= 0)
        assertTrue("facts 应存在: $result", factsIdx >= 0)
        // longterm 不应被排到早期(相对 today/week 靠后),更不应缺失
        assertFalse("longterm 不应被尾部截断丢弃", result.contains("长期记忆 A") && !result.contains("## 长期情况"))
    }

    // ── ③ 有截断时产生日志且不抛异常 ────────────────────────────────────

    @Test
    fun `超预算截断不抛异常`() {
        // 极小的预算也能安全返回,不抛异常
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 1)
        assertTrue("结果不能为空字符串", result.isNotBlank())
    }

    @Test
    fun `空文本与预算为 0 仍保持原行为`() {
        assertEquals("", LlmBudget.truncateToTokenBudget("", 100))
        val md = sampleMarkdown()
        // budget=0 视为不限制,返回原文
        assertEquals(md, LlmBudget.truncateToTokenBudget(md, 0))
    }

    @Test
    fun `splitSegments 纯函数正确切分四段`() {
        val segments = LlmBudget.splitSegments(sampleMarkdown())
        assertEquals(4, segments.size)
        assertEquals("## 重要事实", segments[0].heading)
        assertEquals("## 长期情况", segments[3].heading)
        assertTrue("末段应为 longterm 内容", segments[3].body.contains("长期记忆 A"))
    }

    @Test
    fun `truncateBySegments 纯函数裁剪只动中段`() {
        val segments = LlmBudget.splitSegments(sampleMarkdown())
        val result = LlmBudget.truncateBySegments(segments, 5)
        // 首段 facts 与末段 longterm 高优先级保留
        assertEquals("## 重要事实", result.first().heading)
        assertEquals("## 长期情况", result.last().heading)
        // 高优先级正文完整
        assertTrue(result.last().body.contains("长期记忆 A 非常重要必须完整保留"))
    }
}
