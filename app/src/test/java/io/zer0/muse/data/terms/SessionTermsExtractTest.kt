package io.zer0.muse.data.terms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.7.x(E2): 术语抽取与注入的确定性口径。
 *
 * 抽取最容易犯的错不是漏,而是**把普通词当术语**:一旦"一下/然后/这个"进了上下文,
 * 模型会开始围绕它们解释,而用户看到的是一份谁也看不懂的术语页。
 * 所以下面除了"该有的有",更重在"不该有的没有"。
 */
class SessionTermsExtractTest {

    private fun termsOf(text: String) = SessionTerms.extract(text).map { it.term }

    @Test
    fun `用户自己给出的定义被抽出词与解释`() {
        val hits = SessionTerms.extract("我们这边的「开黑」就是指一起打排位赛。")

        assertEquals(1, hits.size)
        assertEquals("开黑", hits.single().term)
        // 解释只留内容本身:"就是指"是句式的一部分,留下来会跟着进提示词
        assertEquals("一起打排位赛", hits.single().meaning)
    }

    @Test
    fun `管这个叫某词句式抽的是被叫的那个词`() {
        val hits = SessionTerms.extract("我们内部管这个叫拉条子，就是每周列一遍进度")

        // 曾经按"管后面的第一个名词"取,抽出的是指代词"这个" —— 那会往术语页塞一条废话
        assertTrue("不该把指代词当术语: ${SessionTerms.describe(hits)}", termsOf("我们内部管这个叫拉条子").none { it == "这个" })
        assertTrue("该抽的是被叫的那个词: ${SessionTerms.describe(hits)}", hits.any { it.term.contains("拉条子") })
    }

    @Test
    fun `所谓句式与中文冒号句式都认`() {
        assertTrue(termsOf("所谓摸鱼，就是上班划水").contains("摸鱼"))
        assertTrue(termsOf("「双推」：两次灰度发布").contains("双推"))
    }

    @Test
    fun `英文 means 句式认，且不区分大小写`() {
        val hits = SessionTerms.extract("In our team, \"hotfix\" means a Sunday deploy.")

        assertEquals("hotfix", hits.single().term)
        assertTrue(hits.single().meaning!!.contains("Sunday deploy"))
    }

    @Test
    fun `我们管这叫 句式认`() {
        assertTrue(termsOf("我们内部管这个叫 拉条子，就是列周报").contains("拉条子"))
    }

    @Test
    fun `引号里的短语作为候选被收集_没有解释也收`() {
        val hits = SessionTerms.extract("老板提到「灰度池」这个词")

        assertEquals(listOf("灰度池"), hits.map { it.term })
        assertNull(hits.single().meaning)
    }

    @Test
    fun `标识符形状的词被收集`() {
        val terms = termsOf("你看 userDisplayName 和 maxRetryCount 这两个字段")

        assertTrue("camelCase 该收: $terms", terms.contains("userDisplayName"))
        assertTrue(terms.contains("maxRetryCount"))
    }

    @Test
    fun `通用大写缩写不被当成黑话`() {
        val terms = termsOf("API 的 JSON 返回要过 HTTP 缓存,顺便看下 TODO 和 PR")

        assertTrue("通用缩写不该进术语: $terms", terms.none { it in setOf("API", "JSON", "HTTP", "TODO", "PR") })
    }

    @Test
    fun `常见虚词与短词不会被当成术语`() {
        // 这是"宁可漏不可错"的底线:没有词表时,任何 n-gram 统计都会把这些吸进来。
        val terms = termsOf("然后我一下就把这个东西处理完了,其实没什么")

        assertTrue("不该有垃圾候选: $terms", terms.isEmpty())
    }

    @Test
    fun `过短或过长的候选被丢掉`() {
        assertTrue(termsOf("「喵」").isEmpty())
        assertTrue(termsOf("「这是一个长得已经像一句话的所谓术语候选项需要被丢弃」").isEmpty())
    }

    @Test
    fun `同一条消息里重复出现的同一个词只算一次命中`() {
        val hits = SessionTerms.extract("「开黑」和「开黑」还有「开黑」")

        assertEquals(1, hits.size)
    }

    @Test
    fun `裸候选不发明解释`() {
        // "同一条说法后来又被裸提到"时不该把解释带进结果 —— 解释只在句子里真的有定义时才有。
        // 而"已有解释被后来的裸候选抹掉"是存储层的事,在 SessionTermStoreTest 里钉。
        val withMeaning = SessionTerms.extract("我们这边的「开黑」就是指一起打排位赛")
        val bare = SessionTerms.extract("今晚要不要开黑,还有「开黑」")

        assertTrue(withMeaning.single().meaning != null)
        assertNull(bare.firstOrNull { it.term == "开黑" }?.meaning)
    }

    // ── 注入 ──────────────────────────────────────────────────

    private fun entity(term: String, confirmed: Boolean, meaning: String? = null, occurrences: Int = 1) = SessionTermEntity(
        id = term.hashCode().toLong(),
        term = term,
        meaning = meaning,
        sessionId = "s-1",
        assistantId = "default",
        occurrences = occurrences,
        confirmed = confirmed,
        firstSeenAt = 0L,
        lastSeenAt = 0L,
    )

    @Test
    fun `空术语集不产生注入段`() {
        assertEquals("", SessionTerms.renderInjectionBlock(emptyList()))
    }

    @Test
    fun `未确认的条目必须标不确定`() {
        val block = SessionTerms.renderInjectionBlock(listOf(entity("开黑", confirmed = false, meaning = "一起打排位")))

        assertTrue(block, block.contains("不确定"))
        assertTrue(block, block.contains("开黑"))
        assertTrue(block, block.contains("一起打排位"))
        assertTrue(block, block.startsWith("<session_terms>"))
    }

    @Test
    fun `已确认的条目不标不确定`() {
        val block = SessionTerms.renderInjectionBlock(listOf(entity("开黑", confirmed = true, meaning = "一起打排位")))

        assertFalse(block, block.contains("不确定"))
    }

    @Test
    fun `注入条数有上限`() {
        val many = (1..12).map { entity("t$it", confirmed = true) }

        val block = SessionTerms.renderInjectionBlock(many)

        assertEquals(SessionTerms.MAX_INJECT, 5)
        val lines = block.lines().filter { it.startsWith("- ") }
        assertEquals("最多 5 条,超出的不进提示词", 5, lines.size)
    }

    @Test
    fun `确认阈值只有一个来源`() {
        assertEquals(3, SessionTerms.CONFIRM_THRESHOLD)
        assertTrue(SessionTerms.awaitingConfirmation(occurrences = 3, confirmed = false))
        assertFalse(SessionTerms.awaitingConfirmation(occurrences = 2, confirmed = false))
        assertFalse("确认过就不该再打扰用户", SessionTerms.awaitingConfirmation(occurrences = 9, confirmed = true))
    }
}
