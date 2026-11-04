package io.zer0.memory.ai

import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆提取提示词的契约（`:memory` 体检第三处）。
 *
 * 这个 prompt 是**实时自动保存记忆**那条路在用的
 * （`MemoryAutoSaveScheduler:331 / :573`），它有两个中文用户不会遇到、
 * 但英文用户会直接吃亏的形状：
 *
 *  - 中英两支各写一遍规则。今天它们是对称的，**但没有任何东西保证明年还对称**：
 *    少提一个字段，解析端就拿默认值填上 —— 用户看到的是"我说记住了，结果没记住"，
 *    而且只有某个语言的用户会这样。
 *  - 解析用 `@Serializable`，JSON 键名就是属性名。所以"该提哪些键"有唯一真源：解析器本身。
 *    测试直接从解析器的 descriptor 取字段名，将来加字段忘了写进 prompt，测试立刻红。
 *
 * 断言都落在**能不能被解析**上，不校文案措辞 —— 文案可以改，键名不能漏。
 */
class MemoryExtractPromptContractTest {

    private val zh = MemoryExtractPrompt.buildSystemPrompt("zh-CN")
    private val en = MemoryExtractPrompt.buildSystemPrompt("en-US")

    /** 解析器认识的键（自动跟随 `@Serializable`，不需要手抄一份清单）。 */
    private fun keysOf(vararg descriptors: SerialDescriptor): Set<String> =
        descriptors.flatMap { descriptor -> (0 until descriptor.elementsCount).map { descriptor.getElementName(it) } }.toSet()

    private val parserKeys = keysOf(
        ParsedAnalysis.serializer().descriptor,
        ParsedEntity.serializer().descriptor,
        ParsedLink.serializer().descriptor,
        ParsedUpdate.serializer().descriptor,
        ParsedMerge.serializer().descriptor,
    )

    @Test
    fun `解析器的每个字段都要被两支提示词同时提到`() {
        parserKeys.forEach { key ->
            assertTrue("中文支漏了字段 $key（模型就不会输出它，解析端只会拿默认值）", zh.contains(key))
            assertTrue("英文支漏了字段 $key（英文用户会静默丢这一类记忆）", en.contains(key))
        }
    }

    @Test
    fun `中英两支列出的分类词表一致`() {
        val categories = listOf("preference", "identity", "event", "relationship", "goal", "medical", "other")

        categories.forEach { folder ->
            assertTrue("中文支缺分类 $folder", zh.contains(folder))
            assertTrue("英文支缺分类 $folder", en.contains(folder))
        }
    }

    @Test
    fun `自动分类提示词与提取提示词用同一套分类`() {
        val zhCat = MemoryExtractPrompt.buildCategorizeSystemPrompt("zh-CN")
        val enCat = MemoryExtractPrompt.buildCategorizeSystemPrompt("en-US")
        val categories = listOf("preference", "identity", "event", "relationship", "goal", "medical", "other")

        categories.forEach { folder ->
            assertTrue("分类支缺 $folder: $folder", zhCat.contains(folder) && enCat.contains(folder))
        }
        // 分类支只该管这两件事，多余字段会让模型自由发挥
        assertTrue(zhCat.contains("folderPath"))
        assertTrue(enCat.contains("folderPath"))
    }

    @Test
    fun `speaker 三个取值都在 免得助手的话被记成用户事实`() {
        listOf("USER", "ASSISTANT", "OTHER").forEach { speaker ->
            assertTrue("中文支缺 speaker 取值 $speaker", zh.contains(speaker))
            assertTrue("英文支缺 speaker 取值 $speaker", en.contains(speaker))
        }
    }

    @Test
    fun `两支都明确禁止 markdown 围栏`() {
        // 带围栏的输出解析会直接失败；这一条只对某一支写死，另一支的用户就会"没记住"
        assertTrue("中文支要禁止围栏", zh.contains("不要 markdown") || zh.contains("markdown 代码块"))
        assertTrue("英文支要禁止围栏", en.contains("no markdown") || en.contains("No markdown"))
        assertFalse("正文里不该真的出现围栏包裹", zh.contains("```json"))
        assertFalse(en.contains("```json"))
    }

    @Test
    fun `未知语言走英文支而不是抛异常或给空串`() {
        // locale 来自宿主；换成日语或空串时必须有可用提示词，不能一条记忆都不再提取
        listOf("ja-JP", "ko-KR", "", "  ", "pt-BR").forEach { locale ->
            val prompt = MemoryExtractPrompt.buildSystemPrompt(locale)
            assertTrue("locale=$locale 不该给空提示词", prompt.isNotBlank())
            assertTrue("locale=$locale 应落到英文支", prompt.startsWith("You are a memory analyst"))
        }
    }

    @Test
    fun `已有记忆预览为空时不留空章节`() {
        listOf(null, "", "   ").forEach { preview ->
            val prompt = MemoryExtractPrompt.buildSystemPrompt("zh-CN", preview)
            assertFalse("不该出现空的「已有记忆」标题", prompt.contains("## 已有记忆"))
        }
        val withPreview = MemoryExtractPrompt.buildSystemPrompt("zh-CN", "- 对青霉素过敏: 用户明确陈述")
        assertTrue("有预览时要带上章节", withPreview.contains("## 已有记忆"))
        assertTrue("预览内容要真进提示词", withPreview.contains("对青霉素过敏"))
        val enPreview = MemoryExtractPrompt.buildSystemPrompt("en-US", "- allergic: stated")
        assertTrue(enPreview.contains("Existing Memories"))
    }

    @Test
    fun `模板版本号在提示词外面单独可取`() {
        // 缓存分组与版本用来判定"提示词换版后要不要重算"，取不到就等于每次都要重算
        assertTrue(MemoryExtractPrompt.TEMPLATE_VERSION.startsWith("memory-extract."))
        assertTrue(MemoryExtractPrompt.CACHE_GROUP.startsWith("memory."))
        assertFalse(zh.contains(MemoryExtractPrompt.TEMPLATE_VERSION))
    }
}
