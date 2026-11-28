package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 提示词回归锚点与运行时模板资产测试。
 *
 * 直接加载并校验真实下发给模型的 `assets/prompt_templates` 下的 `_zh` / `_en` 模板、
 * [SystemPromptAssembler] 回落常量与 `ShowCardTool.kt` 契约，避免「只改了回落或只改了资产」
 * 导致的静默漂移。
 */
class PromptInvariantTest {

    private val invariants = SystemPromptAssembler.PROMPT_INVARIANTS

    private fun locate(relativeUnderApp: String): File? = listOf(
        File(relativeUnderApp),
        File("app/$relativeUnderApp"),
    ).firstOrNull { it.exists() }

    private fun readAsset(name: String): String? = locate("src/main/assets/prompt_templates/$name")?.readText()

    @Test
    fun `invariant list is not empty`() {
        assertTrue("锚点表被清空了，提示词约束就失去了守护", invariants.isNotEmpty())
    }

    @Test
    fun `artifact vs show_card decision is based on what the user does with it`() {
        val joined = invariants.joinToString("\n")

        assertTrue("要按「拿走」判断 artifact", joined.contains("用户会把它复制走或保存吗"))
        assertTrue("要按「操作」判断 show_card", joined.contains("用户会点它、拖它、改它吗"))
    }

    @Test
    fun `duplicating the same content in both forms is forbidden`() {
        val joined = invariants.joinToString("\n")

        assertTrue(
            "同一内容出两份是用户实际抱怨的现象，必须显式禁止",
            joined.contains("不要**为同一份内容同时出卡片和 artifact"),
        )
    }

    @Test
    fun `search has both stop and continue criteria`() {
        val joined = invariants.joinToString("\n")

        assertTrue("要有「该停」的判据", joined.contains("已能直接回答用户的问题"))
        assertTrue("多来源一致就该停", joined.contains("多个来源对同一事实一致"))
        assertTrue("要有「该继续」的判据", joined.contains("关键数字/日期/名称仍缺失"))
    }

    @Test
    fun `summary alone is not treated as verified`() {
        val joined = invariants.joinToString("\n")

        assertTrue(
            "拿搜索摘要当已核实是第 12 条的另一半问题",
            joined.contains("不能拿摘要当已核实"),
        )
    }

    @Test
    fun `every invariant appears in both fallback source and runtime zh assets`() {
        val source = locate("src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt")?.readText()
            ?: return
        val runtimeAssets = listOfNotNull(
            readAsset("execution_rules_zh.prompt"),
            readAsset("generative_ui_zh.prompt"),
        ).joinToString("\n\n")
        assertTrue("必须加载到运行时模板资产", runtimeAssets.isNotBlank())

        invariants.forEach { phrase ->
            // 锚点表自身出现一次；fallback 正文里必须至少再出现一次。
            val occurrences = Regex(Regex.escape(phrase)).findAll(source).count()
            assertTrue(
                "锚点「$phrase」只出现在锚点表里，SystemPromptAssembler 回落正文未包含",
                occurrences >= 2,
            )
            assertTrue(
                "锚点「$phrase」未出现在真实运行时资产 execution_rules_zh/generative_ui_zh.prompt 中",
                runtimeAssets.contains(phrase),
            )
        }
    }

    @Test
    fun `prompt template assets have no redundant unsuffixed duplicates and maintain zh-en parity`() {
        val dir = locate("src/main/assets/prompt_templates") ?: return
        val unsuffixed = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".prompt") && !it.name.endsWith("_zh.prompt") && !it.name.endsWith("_en.prompt") }
            .orEmpty()
        assertTrue(
            "不应残留无 locale 后缀的冗余模板副本: ${unsuffixed.map { it.name }}",
            unsuffixed.isEmpty(),
        )

        val requiredTemplates = listOf(
            "execution_rules",
            "generative_ui",
            "artifact_format",
            "mood_format",
            "platform_decl",
            "default_persona",
            "memory_rules",
            "self_reflection",
            "cover_generation",
        )
        for (name in requiredTemplates) {
            val zh = readAsset("${name}_zh.prompt").orEmpty()
            val en = readAsset("${name}_en.prompt").orEmpty()
            assertTrue("${name}_zh.prompt 缺失或为空", zh.isNotBlank())
            assertTrue("${name}_en.prompt 缺失或为空", en.isNotBlank())
            assertTrue("${name}_zh 与 ${name}_en 不应相同", zh != en)
        }
    }

    @Test
    fun `execution_rules assets enforce unified routing, proactive kb lookup, and workspace mounts`() {
        val zh = readAsset("execution_rules_zh.prompt") ?: return
        val en = readAsset("execution_rules_en.prompt") ?: return

        for (token in listOf(
            "knowledge_search",
            "knowledge_list_docs",
            "knowledge_read_doc",
            "include_internal=true",
            "web_search",
            "web_fetch",
            "browser_*",
            "find_tools",
            "runtime_missing",
        )) {
            assertTrue("execution_rules_zh 缺少 $token", zh.contains(token))
            assertTrue("execution_rules_en 缺少 $token", en.contains(token))
        }
        assertTrue("execution_rules_zh 应说明自动 RAG 默认关闭", zh.contains("自动 RAG 默认关闭"))
        assertTrue("execution_rules_en 应说明 auto-RAG 默认关闭", en.contains("auto-RAG is OFF by default"))
        assertTrue("execution_rules_zh 应包含工作区与挂载目录说明", zh.contains("工作区沙盒") && zh.contains("挂载"))
        assertTrue("execution_rules_en 应包含 workspace 与 mounted 目录说明", en.contains("workspace sandbox") && en.contains("user-mounted"))
        assertTrue("execution_rules_en 禁止把摘要当已核实", en.contains("never treat a snippet alone as verified"))
    }

    @Test
    fun `generative_ui assets stay slim presentation routers and ShowCardTool holds card contract`() {
        val zh = readAsset("generative_ui_zh.prompt") ?: return
        val en = readAsset("generative_ui_en.prompt") ?: return

        assertTrue("generative_ui_zh 应保持精简呈现路由(<900字)，实际 ${zh.length}", zh.length < 900)
        assertTrue("generative_ui_en 应保持精简呈现路由(<1500字)，实际 ${en.length}", en.length < 1500)
        assertTrue("generative_ui_zh 应包含 <card_state> 与 window.muse.send", zh.contains("<card_state>") && zh.contains("window.muse.send"))
        assertTrue("generative_ui_en 应包含 <card_state> 与 window.muse.send", en.contains("<card_state>") && en.contains("window.muse.send"))
        assertTrue("generative_ui_en 禁止同一内容同时出卡片和 artifact", en.contains("never** emit both a card and an artifact"))

        val showCardSrc = locate("src/main/java/io/zer0/muse/tools/ShowCardTool.kt")?.readText()
        if (showCardSrc != null) {
            assertTrue("ShowCardTool 应声明无 DOCTYPE 片段规范", showCardSrc.contains("DOCTYPE"))
            assertTrue("ShowCardTool 应声明 window.muse.cardId", showCardSrc.contains("window.muse.cardId"))
            assertTrue("ShowCardTool 应声明 window.muse.getData()", showCardSrc.contains("window.muse.getData()"))
            assertTrue("ShowCardTool 应声明 height 参数", showCardSrc.contains("\"height\""))
        }
    }

    @Test
    fun `artifact_format, mood_format, cover_generation, default_persona, and platform_decl contracts hold`() {
        val artifactZh = readAsset("artifact_format_zh.prompt") ?: return
        val artifactEn = readAsset("artifact_format_en.prompt") ?: return
        assertTrue(artifactZh.contains("table:") && artifactZh.contains("GFM") && artifactZh.contains("[artifact:...]"))
        assertTrue(artifactEn.contains("table:") && artifactEn.contains("GFM") && artifactEn.contains("[artifact:...]"))

        val moodZh = readAsset("mood_format_zh.prompt") ?: return
        val moodEn = readAsset("mood_format_en.prompt") ?: return
        for (pool in listOf("Vibe:", "Sparks:", "Reflections:", "Will:", "reasoning_content")) {
            assertTrue("mood_format_zh 缺少 $pool", moodZh.contains(pool))
            assertTrue("mood_format_en 缺少 $pool", moodEn.contains(pool))
        }
        assertTrue("mood_format_zh 应允许纯工具调用轮跳过 <mood>", moodZh.contains("纯工具调用轮"))
        assertTrue("mood_format_en 应允许纯工具调用轮跳过 <mood>", moodEn.contains("Pure tool-call turns"))

        val coverZh = readAsset("cover_generation_zh.prompt") ?: return
        val coverEn = readAsset("cover_generation_en.prompt") ?: return
        for ((label, content) in listOf("zh" to coverZh, "en" to coverEn)) {
            assertTrue("cover_generation_$label 必须使用 Pebble 双花括号 {{title}}", content.contains("{{title}}"))
            assertTrue("cover_generation_$label 必须使用 Pebble 双花括号 {{description}}", content.contains("{{description}}"))
            assertFalse("cover_generation_$label 不应残留单花括号 {title}", Regex("(?<!\\{)\\{title\\}(?!\\})").containsMatchIn(content))
            assertFalse("cover_generation_$label 不应含字面十六进制色值", content.contains("#2A7A55"))
            assertFalse("cover_generation_$label 不应写死 16:9", content.contains("16:9"))
            assertTrue("cover_generation_$label 应标注约 2.4:1 横幅构图", content.contains("2.4:1"))
        }

        val personaZh = readAsset("default_persona_zh.prompt") ?: return
        val personaEn = readAsset("default_persona_en.prompt") ?: return
        assertFalse("default_persona_zh 不应强制普通概念解释必联网搜索", personaZh.contains("涉及概念解释时,必须联网搜索"))
        assertFalse("default_persona_en 不应强制普通概念解释必联网搜索", personaEn.contains("When explaining concepts, always search the web"))

        val platformZh = readAsset("platform_decl_zh.prompt") ?: return
        val platformEn = readAsset("platform_decl_en.prompt") ?: return
        assertTrue("platform_decl_zh 应包含 Shizuku/Root 说明", platformZh.contains("Shizuku/Root"))
        assertTrue("platform_decl_en 应包含 Shizuku/Root 说明", platformEn.contains("Shizuku/Root"))
    }

    @Test
    fun `assembler removes obsolete tool contradictions and splits group chat identity from channel tools`() {
        val source = locate("src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt")?.readText()
            ?: return
        assertFalse("不应残留 filesDir 矛盾限制", source.contains("文件只能访问应用沙盒 filesDir"))
        assertFalse("不应引用不存在的工具 mcp_server_configure", source.contains("mcp_server_configure"))
        assertFalse("不应引用不存在的工具 mcp_server_bind_assistant", source.contains("mcp_server_bind_assistant"))

        val identityOnly = SystemPromptAssembler.buildGroupChatIdentitySection(
            chatName = "测试群",
            members = listOf("小爱", "小智"),
            currentAgentName = "小爱",
        )
        assertTrue(identityOnly.contains("小爱"))
        assertFalse("群聊身份段不应包含频道工具名", identityOnly.contains("channel_reply"))
        assertFalse("群聊身份段不应包含频道工具名", identityOnly.contains("channel_pass"))
        assertFalse("群聊身份段不应包含频道工具名", identityOnly.contains("channel_read_context"))

        val withTools = SystemPromptAssembler.buildGroupChatHintSection(
            chatName = "测试群",
            members = listOf("小爱", "小智"),
            currentAgentName = "小爱",
            includeChannelTools = true,
        )
        assertTrue(withTools.contains("channel_reply(content)"))
        assertTrue(withTools.contains("channel_pass(reason?)"))
        assertTrue(withTools.contains("channel_read_context(limit?)"))
        assertFalse("不应暴露旧的错误参数名 body", withTools.contains("assistantId, body"))

        val withoutTools = SystemPromptAssembler.buildGroupChatHintSection(
            chatName = "测试群",
            members = listOf("小爱", "小智"),
            currentAgentName = "小爱",
            includeChannelTools = false,
        )
        assertEquals(identityOnly, withoutTools)
    }

    @Test
    fun `extractLongTermMemoryLines parses compiled facts for relevant memory deduplication`() {
        val snapshot = """
            前缀段
            <long_term_memory>
            ## 长期情况
            - 用户住在成都
            * 喜欢喝美式咖啡
            </long_term_memory>
            后缀段
        """.trimIndent()
        val extracted = SystemPromptAssembler.extractLongTermMemoryLines(snapshot)
        assertEquals(setOf("用户住在成都", "喜欢喝美式咖啡"), extracted)
    }
}
