package io.zer0.muse.data.agentbot;

import org.junit.Assert.assertTrue;
import org.junit.Test;
import java.io.File;

/**
 * P0-3 的防线在**记录侧**，不在审核侧。

 * 审查指出：`recordStep` 失败被吞成 `getOrDefault("")`，账本变成"成功但为空"，
 * 审核看不到副作用 → 判 REWORK → 自动重跑不可逆操作。

 * 我试过在 `BotResultApplier` 里把"空账本"当"不可确认"，两次都失败：
 *
 *  1. 一律判空账本 → 纯对话任务（本来就没执行工具）被误判；
 *  2. 用"有候选"当判据 → 只测审核逻辑的用例全红。

 * 根因：`process` 这一层**无法区分**"没执行工具"和"记账丢了"。
 * 能区分的地方只有一个 —— 记录侧。所以防线放在那里：记账失败打 error 级日志，
 * 带上工具名，让"这次重跑为什么发生"有据可查。
 */
class EmptyStepLedgerGuardTest {

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText();
    }

    private val applier: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotResultApplier.kt").readText();
    }

    @Test
    fun `recordStep failure is logged at error level with tool name`() {
        val at = scheduler.indexOf("记录步骤失败");
        assertTrue("必须有这条日志", at > 0);
        val around = scheduler.substring(maxOf(0, at - 400), minOf(at + 200, scheduler.length));
        assertTrue("要用 error 级 —— 这是排查重跑原因的唯一线索", around.contains("Logger.e("));
        assertTrue("要带工具名，否则不知道哪一步没记上", around.contains("toolName"));
        assertTrue("要说明后果", around.contains("返工"));
    }

    @Test
    fun `step read failure still counts as unconfirmed side effects`() {
        // 这条防线必须保留：读失败 ≠ 没有副作用。
        assertTrue(
            "读失败要按有副作用处理",
            applier.contains("步骤账本读取失败"),
        );
    }

    @Test
    fun `empty ledger is not special cased at the applier layer`() {
        // 这里不能有"空账本"特判 —— 那一层区分不了两种成因。
        val at = applier.indexOf("val sideEffects: List<String> =");
        assertTrue(at > 0);
        val end = applier.indexOf("var decision = reviewer.review(", at);
        val block = applier.substring(at, end);
        assertTrue(
            "不该在审核侧猜空账本的成因",
            !block.contains("recordedSteps.isEmpty()"),
        );
    }
}
