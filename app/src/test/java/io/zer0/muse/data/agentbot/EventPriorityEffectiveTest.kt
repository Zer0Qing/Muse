package io.zer0.muse.data.agentbot;

import org.junit.Assert.assertTrue;
import org.junit.Test;
import java.io.File;

/**
 * 事件优先级必须真的生效。

 * 上一轮把结果事件改成"保留待处理"后，我担心它们会挤占收件箱。
 * 查证发现更严重的问题：`priorityOf` **从来没被调用过** ——
 * `pendingEvents` 只按 `sequence ASC` 取前 N 条，优先级是死代码。
 *
 * 后果：用户说"停下"（优先级 1）可能排在闲聊（优先级 8）后面。
 */
class EventPriorityEffectiveTest {

    private val processor: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotInboxProcessor.kt").readText();
    }

    @Test
    fun `runTurn sorts by priority before truncating`() {
        val at = processor.indexOf("suspend fun runTurn(");
        assertTrue(at > 0);
        val end = processor.indexOf("fun priorityOf(", at);
        val body = processor.substring(at, if (end > at) end else processor.length);

        assertTrue(
            "必须真的调用 priorityOf —— 否则它是死代码",
            body.contains("priorityOf("),
        );
        assertTrue("必须排序", body.contains("sortedWith("));
        assertTrue("排序后要截断", body.contains(".take(maxEvents)"));
    }

    @Test
    fun `window is larger than the per turn cap`() {
        // 只取 8 条再排序没有意义 —— 高优先级事件可能根本不在那 8 条里。
        assertTrue(
            "排序窗口必须大于单轮上限",
            processor.contains("maxEvents * PRIORITY_WINDOW_FACTOR"),
        );
        val factor = Regex("PRIORITY_WINDOW_FACTOR = (\\d+)").find(processor)?.groupValues?.get(1)?.toIntOrNull();
        assertTrue("倍数必须大于 1，实际 " + factor, factor != null && factor > 1);
    }

    @Test
    fun `user input outranks task result`() {
        // 这条是上一轮担心的问题：结果事件堆积不能挡住用户说话。
        val at = processor.indexOf("fun priorityOf(");
        val body = processor.substring(at, minOf(at + 700, processor.length));
        val userAt = body.indexOf("USER_INPUT_RECEIVED ->");
        val resultAt = body.indexOf("TASK_RESULT_READY ->");
        assertTrue("两者都要有优先级", userAt > 0 && resultAt > 0);
        val userRank = Regex("USER_INPUT_RECEIVED -> (\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull();
        val resultRank = Regex("TASK_RESULT_READY -> (\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull();
        assertTrue(
            "用户输入必须比结果事件先处理，实际 user=" + userRank + " result=" + resultRank,
            userRank != null && resultRank != null && userRank < resultRank,
        );
    }
}
