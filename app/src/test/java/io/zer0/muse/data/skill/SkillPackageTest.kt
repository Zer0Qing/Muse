package io.zer0.muse.data.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SKILL.md` 解析测试。
 *
 * 用户是手写这份文件的，所以两条同等重要：**接受该接受的**，
 * 以及**拒绝时说清缺什么、在第几行** —— 只会返回"格式错误"等于把人逼回猜。
 */
class SkillPackageTest {

    private val sample = """
        ---
        name: 周报整理
        description: 把本周会话要点整理成三段式周报
        ---

        第一步：读本周记录。
        第二步：按「进展 / 风险 / 下周」三段输出。
    """.trimIndent()

    @Test
    fun `parses name description and body separately`() {
        val parsed = SkillPackage.parse(sample).getOrThrow()

        assertEquals("周报整理", parsed.name)
        assertEquals("把本周会话要点整理成三段式周报", parsed.description)
        assertTrue("正文应保留指令主体: ${parsed.body}", parsed.body.startsWith("第一步：读本周记录。"))
        assertTrue("正文不该再带 frontmatter", !parsed.body.contains("---"))
    }

    /** 失败原因文本。项目用的是自定义 Result（Error.message），不是 kotlin.Result。 */
    private fun failureOf(markdown: String): String = (SkillPackage.parse(markdown) as? io.zer0.common.Result.Error)?.message ?: ""

    @Test
    fun `missing opening fence is refused with a positional hint`() {
        val error = failureOf("name: x\ndescription: y\n正文")

        assertTrue("要说明缺 YAML 头部，实际: $error", error.contains("YAML 头部"))
    }

    @Test
    fun `unclosed frontmatter says so instead of swallowing the body`() {
        val error = failureOf("---\nname: x\ndescription: y\n正文没有闭合")

        assertTrue("要说明头部没闭合，实际: $error", error.contains("闭合"))
    }

    @Test
    fun `missing required fields name the exact key`() {
        val noName = failureOf("---\ndescription: d\n---\n正文")
        val noDesc = failureOf("---\nname: n\n---\n正文")
        val noBody = failureOf("---\nname: n\ndescription: d\n---\n")

        assertTrue("缺 name: $noName", noName.contains("缺少 name"))
        assertTrue("缺 description: $noDesc", noDesc.contains("缺少 description"))
        assertTrue("空正文也要被拒: $noBody", noBody.contains("没有正文"))
    }

    @Test
    fun `oversized description is refused for context-budget reasons`() {
        val long = "长".repeat(SkillPackage.MAX_DESCRIPTION_CHARS + 1)
        val error = failureOf("---\nname: n\ndescription: $long\n---\n正文")

        assertTrue("要说清是列表注入的预算: $error", error.contains("技能列表"))
        assertTrue("要指出正文不受此限制: $error", error.contains("正文放在"))
    }

    @Test
    fun `oversized body suggests splitting into references`() {
        val long = "字".repeat(SkillPackage.MAX_BODY_CHARS + 10)
        val error = failureOf("---\nname: n\ndescription: d\n---\n$long")

        assertTrue("超限正文要给出分文件的做法: $error", error.contains("references/"))
    }

    @Test
    fun `block scalars and folded scalars both work`() {
        val block = SkillPackage.parse(
            """
            ---
            name: n
            description: |
              第一行
              第二行
            ---
            正文
            """.trimIndent(),
        ).getOrThrow()
        val folded = SkillPackage.parse(
            """
            ---
            name: n
            description: >
              一句
              接一句
            ---
            正文
            """.trimIndent(),
        ).getOrThrow()

        assertEquals("第一行\n第二行", block.description)
        assertEquals("一句 接一句", folded.description)
    }

    @Test
    fun `bom and crlf files parse the same`() {
        val withNoise = "" + sample.replace("\n", "\r\n")

        val parsed = SkillPackage.parse(withNoise).getOrThrow()

        assertEquals("周报整理", parsed.name)
    }

    @Test
    fun `comments blank lines and unknown keys are tolerated`() {
        val parsed = SkillPackage.parse(
            """
            ---
            # 这是注释

            name: n
            description: d
            license: MIT
            allowed-tools: read_file
            ---
            正文
            """.trimIndent(),
        ).getOrThrow()

        assertEquals("d", parsed.description)
        assertEquals("MIT", parsed.metadata["license"])
        assertEquals("read_file", parsed.metadata["allowed-tools"])
    }

    @Test
    fun `quoted values keep inner colons`() {
        val parsed = SkillPackage.parse(
            """---
            name: "导入: 备份"
            description: '含冒号: 的说明'
            ---
            正文""",
        ).getOrThrow()

        assertEquals("导入: 备份", parsed.name)
        assertEquals("含冒号: 的说明", parsed.description)
    }

    @Test
    fun `filename constant matches what importers look for`() {
        assertEquals("SKILL.md", SkillPackage.FILE_NAME)
    }
}
