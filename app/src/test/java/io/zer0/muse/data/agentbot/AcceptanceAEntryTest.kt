package io.zer0.muse.data.agentbot;

import org.junit.Assert.assertTrue;
import org.junit.Test;
import java.io.File;
import io.zer0.muse.ui.screen.HomeScreen

/**
 * 验收清单 A 组（入口与可达性）的可静态验证部分。

 * 清单里 A 组有 6 项，全部标"必须上手"。但这轮查证发现：
 * **"入口有没有接到实现"是可以静态验证的** —— 只有"点了有没有反应"必须看。
 *
 * 审查报告里"死路由"是高频问题：代码在、路由在、但没人 navigate 到它。
 * 这个类逐个钉住那类接线。
 */
class AcceptanceAEntryTest {

    private val home: String by lazy {
        File("src/main/java/io/zer0/muse/ui/screen/HomeScreen.kt").readText();
    }

    private val screen: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotScreen.kt").readText();
    }

    @Test
    fun `A1 agent tab renders the bot screen`() {
        assertTrue(
            "Agent Tab 必须真的渲染 AgentBotScreen，不能只有 ViewModel",
            home.contains("AgentBotScreen(viewModel") || home.contains("AgentBotScreen("),
        );
    }

    @Test
    fun `A2 provider menu entry actually opens the sheet`() {
        // 审查报告点名过：面板存在，但没有一处把 showModelSheet 置 true。
        assertTrue("必须有一处把 showModelSheet 置 true", home.contains("showModelSheet = true"));
        assertTrue("面板必须被渲染", home.contains("if (showModelSheet)"));
    }

    @Test
    fun `A3 provider settings callback is wired through`() {
        // 空 lambda 默认值会让"点了没反应"看起来像正常。
        val count = Regex("onOpenProviderSettings").findAll(home).count();
        assertTrue(
            "声明 + 至少两处传递，实际 " + count + " 处",
            count >= 3,
        );
    }

    @Test
    fun `A4 task board is rendered from state`() {
        assertTrue("看板必须订阅状态", screen.contains("viewModel.taskBoard.collectAsStateWithLifecycle()"));
        assertTrue("并且渲染出来", screen.contains("TaskBoardSection("));
    }

    @Test
    fun `A5 allowance section is reachable and can revoke`() {
        assertTrue(
            "放行列表必须订阅状态",
            screen.contains("viewModel.allowedTools.collectAsStateWithLifecycle()"),
        );
        assertTrue("并且有收回入口", screen.contains("onRevoke = viewModel::revokeAllowance"));
    }

    @Test
    fun `A6 candidate section is reachable`() {
        assertTrue(
            "候选必须订阅状态",
            screen.contains("viewModel.pendingCandidates.collectAsStateWithLifecycle()"),
        );
        assertTrue("并且渲染出来", screen.contains("CandidateSection("));
    }

    @Test
    fun `every rendered section is backed by a state flow`() {
        // 防止"渲染了一个空壳区块"：每个 Section 都要有对应的 collect。
        // 调用点有缩进，定义是顶格 —— 用词边界而不是行首锚点。
        val sections = Regex("\\b(TaskBoardSection|ApprovalSection|AllowanceSection|CandidateSection)\\(")
            .findAll(screen).map { it.groupValues[1] }.toSet();
        assertTrue("四类区块都要出现", sections.size >= 4);
        listOf(
            "taskBoard", "allowedTools", "pendingCandidates",
        ).forEach { flow ->
            assertTrue(
                flow + " 必须有对应的状态订阅",
                screen.contains("viewModel." + flow + ".collectAsStateWithLifecycle()"),
            );
        };
    }
}