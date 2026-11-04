package io.zer0.memory.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编译提示词的契约（`:memory` 体检第九处）。
 *
 * 五支 prompt（today / daily / week / longterm / facts）各自有中英两支，
 * 而编译产物是要**注入回每一次对话**的东西：某一侧漏了一条约束，
 * 那个语言用户的记忆形状就长期和另一侧不一样，而且没人会报错。
 *
 * 尤其现在不能靠"读的时候把标题剥掉"兜着 —— `CompiledMemoryState.stripLeadingHeadings`
 * 已经收窄成"只剥段标题与 1~2 级前言"，好保住 facts 段里的 `### Work` 子分类，
 * 于是"提示词里必须写明不要用 markdown 标题"这件事从保险变成了**唯一防线**。
 */
class CompilePromptsContractTest {

    private val locales = listOf("zh-CN" to true, "en-US" to false, "ja-JP" to false)

    private val builders: List<Pair<String, (String) -> String>> = listOf(
        "today" to CompilePrompts::buildTodayPrompt,
        "daily" to CompilePrompts::buildDailyPrompt,
        "week" to CompilePrompts::buildWeekPrompt,
        "longterm" to CompilePrompts::buildLongtermPrompt,
        "facts" to CompilePrompts::buildFactsPrompt,
    )

    @Test
    fun `每一支的每种语言都禁止 markdown 标题`() {
        builders.forEach { (name, build) ->
            locales.forEach { (locale, isZh) ->
                val prompt = build(locale)
                val marker = if (isZh) "Markdown 标题" else "Markdown headings"
                assertTrue("$name($locale) 少了禁止标题的那句", prompt.contains(marker))
            }
        }
    }

    private fun promptOf(name: String, locale: String): String = builders
        .first { it.first == name }.second(locale)

    @Test
    fun `条数上限中英成对一致`() {
        // 数字各写一遍就会各漂各的：用户看到的记忆长短取决于他用哪种语言，而不是内容多少
        listOf(
            Triple("today", "3 到 5", "3 to 5"),
            Triple("week", "3 到 5", "3 to 5"),
            Triple("longterm", "3 到 6", "3 to 6"),
        ).forEach { (name, zhTerm, enTerm) ->
            val zh = promptOf(name, "zh-CN")
            val en = promptOf(name, "en-US")
            assertTrue("$name 中文支缺条数上限「$zhTerm」", zh.contains(zhTerm))
            assertTrue("$name 英文支缺条数上限「$enTerm」", en.contains(enTerm))
        }

        assertTrue("daily 中文支要写两三句", promptOf("daily", "zh-CN").contains("两三句"))
        assertTrue("daily 英文支要写 two to three", promptOf("daily", "en-US").contains("two to three"))
    }

    @Test
    fun `未知语言落到英文支而不是空白或中文`() {
        builders.forEach { (name, build) ->
            val en = build("en-US")
            listOf("ja-JP", "ko-KR", "", "   ", "pt-BR").forEach { locale ->
                assertNotEquals("$name($locale) 不该是空的", "", build(locale).trim())
                assertEquals("$name 未知语言应与 en-US 同一支", en, build(locale))
            }
        }
    }

    @Test
    fun `中文支确实带中文 英文支不带`() {
        builders.forEach { (name, build) ->
            val zh = build("zh-CN")
            val en = build("en-US")
            assertTrue("$name 中文支要含汉字", zh.any { ch -> ch in '一'..'鿿' })
            assertNotEquals("$name 两支不该完全相同", zh, en)
        }
    }

    @Test
    fun `facts 支要求一行一条且不补主语`() {
        val zh = CompilePrompts.buildFactsPrompt("zh-CN")
        val en = CompilePrompts.buildFactsPrompt("en-US")

        assertTrue(zh.contains("一行一条"))
        assertTrue(en.contains("one fact per line"))
        // 不补"用户"主语：加了主语会让同一句话在 facts 与摘要里以两种写法重复出现，去重也认不出
        assertTrue(zh.contains("不要补"))
        assertTrue(en.contains("do not add"))
    }

    @Test
    fun `可编辑 facts 支与 facts 支是同一份`() {
        listOf("zh-CN", "en-US", "ja-JP").forEach { locale ->
            assertEquals(
                "buildEditableFactsPrompt 是 buildFactsPrompt 的别名，不该各写一份",
                CompilePrompts.buildFactsPrompt(locale),
                CompilePrompts.buildEditableFactsPrompt(locale),
            )
        }
    }
}
