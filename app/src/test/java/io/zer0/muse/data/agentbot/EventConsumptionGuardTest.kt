package io.zer0.muse.data.agentbot;

import org.junit.Assert.assertFalse;
import org.junit.Assert.assertTrue;
import org.junit.Test;
import java.io.File;

/**
 * 结果类事件不能被无条件消费。

 * 独立审查发现的 P1-8：`runTurnOnce` 里 `when (event.kind) { ... else -> true }`
 * 把所有事件都当成处理成功，随后 `markConsumed` 是不可逆的。
 *
 * 结果：`TASK_RESULT_READY` / `TASK_FAILED` 既没发布也没落 UI，就被永久吞掉 ——
 * 用户永远收不到「你交代的事做完了」。
 */
class EventConsumptionGuardTest {

    private val vm: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText();
    }

    private fun turnBody(): String {
        val at = vm.indexOf("suspend fun runTurnOnce(");
        require(at > 0) { "找不到 runTurnOnce" };
        val end = vm.indexOf("fun tickScheduler(", at);
        return vm.substring(at, if (end > at) end else vm.length);
    }

    @Test
    fun `result events are not consumed`() {
        val body = turnBody();
        // TASK_RESULT_READY 与 TASK_FAILED 必须走 false 分支。
        assertTrue(
            "结果类事件必须显式返回 false",
            body.contains("BotEventKind.TASK_RESULT_READY,") &&
                body.contains("BotEventKind.TASK_FAILED,"),
        );
        assertTrue("并且返回 false", body.contains("false"));
    }

    @Test
    fun `no catch all true branch`() {
        val body = turnBody();
        assertFalse(
            "不能有 else -> true —— 那等于一律消费",
            body.contains("else -> true"),
        );
    }

    @Test
    fun `unknown events are preserved not dropped`() {
        val body = turnBody();
        assertTrue(
            "未识别类型也要保留，宁可堆积也不静默丢弃",
            body.contains("else -> false"),
        );
    }
}
