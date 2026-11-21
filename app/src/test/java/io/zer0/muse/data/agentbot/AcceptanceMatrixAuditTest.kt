package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V 矩阵终审：把"这些条目靠什么保证"落成可回归的断言。
 *
 * 有些条目是靠既有代码成立的（不需要新增实现），但**没有断言就会漂移**：
 * 下一个人改动相关文件时不会知道自己破坏了一条验收标准。
 */
class AcceptanceMatrixAuditTest {

    private fun source(rel: String) = File("src/main/java/" + rel).readText();

    // ── V18：FULL 普通聊天行为不变 ────────────────────────────────

    @Test
    fun `plain chat has no central references`() {
        // 中枢的所有代码都在 data/agentbot 与 ui/agentbot 下。
        // 普通聊天目录里出现 agentbot 引用，就意味着两条链路开始耦合。
        val chatDir = File("src/main/java/io/zer0/muse/ui/chat");
        val offenders = chatDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("data.agentbot") }
            .map { it.name }
            .toList();
        assertTrue(
            "普通聊天不该依赖中枢（发现: " + offenders + "）",
            offenders.isEmpty(),
        );
    }

    // ── V19：资源失效/目标编辑后标注失效 ─────────────────────────

    @Test
    fun `annotation invalidates on content change`() {
        val svc = source("io/zer0/muse/data/agentbot/BotAnnotationService.kt");
        assertTrue(
            "正文变了旧标注必须失效，不能套到新内容上",
            svc.contains("targetContentHash == currentContentHash"),
        );
        assertTrue(svc.contains("AnnotationStatus.STALE"));
    }

    // ── V27：启动失败落 SafeMode ────────────────────────────────

    @Test
    fun `startup failure marks safe mode instead of hanging`() {
        val app = source("io/zer0/muse/MuseApp.kt");
        assertTrue(
            "启动装配抛错必须落 SafeMode，不能出现无可点元素的全屏遮罩",
            app.contains("markSafeMode"),
        );
        assertTrue(
            "无论成败都要放开启动闸，否则用户永远停在加载页",
            app.contains("_startupReady.value = true"),
        );
    }

    // ── V29：已放行可收回，且立即生效 ───────────────────────────

    @Test
    fun `allowance can be revoked and the list refreshes`() {
        val screen = source("io/zer0/muse/ui/agentbot/AgentBotScreen.kt");
        val vm = source("io/zer0/muse/ui/agentbot/AgentBotViewModel.kt");
        assertTrue("界面要有收回入口", screen.contains("onRevoke"));
        assertTrue("收回要真的调仓储", vm.contains("allowances.revoke("));
        assertTrue(
            "收回后必须立刻刷新，不能等下次进页面",
            vm.contains("refreshAllowances()"),
        );
    }

    // ── V30：发送失败时输入回到输入框 ───────────────────────────

    @Test
    fun `send failure restores the draft`() {
        val ctrl = source("io/zer0/muse/ui/chat/ChatGenerationController.kt");
        assertTrue(
            "发送失败要恢复输入内容，不能静默丢掉用户打的字",
            ctrl.contains("rollbackOptimisticSend"),
        );
    }

    // ── V31：7 语言键数一致 ─────────────────────────────────────

    @Test
    fun `central strings exist in all seven locales`() {
        val locales = listOf("values", "values-en", "values-es", "values-ja", "values-ko", "values-pt-rBR", "values-ru");
        val counts = locales.associateWith { loc ->
            val file = File("src/main/res/$loc/strings_ui_misc.xml");
            if (!file.isFile) -1
            else Regex("<string name=\"agent_bot_").findAll(file.readText()).count();
        };
        val expected = counts["values"] ?: -1;
        assertTrue("中文基准必须有中枢文案", expected > 0);
        counts.forEach { (loc, count) ->
            assertEquals("$loc 的中枢文案数与中文不一致", expected, count);
        };
    }

    // ── V25：旧链路未变 ─────────────────────────────────────────

    @Test
    fun `old subagent entry points still default to foreground`() {
        val runner = source("io/zer0/muse/tools/SubagentRunner.kt");
        assertTrue(
            "background 默认 false，旧调用方行为不变",
            runner.contains("val background: Boolean = false"),
        );
        assertTrue(
            "工具回调默认 null，旧调用方不受影响",
            runner.contains("val onToolStarted: (suspend (toolName: String, argsJson: String) -> String)? = null"),
        );
    }
}
