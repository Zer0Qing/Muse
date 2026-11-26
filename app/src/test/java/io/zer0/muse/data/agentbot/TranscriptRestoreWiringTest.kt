package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 对话流重建的接线。
 *
 * 规则本身（合并、排序、去重）在 [TranscriptRestorerMergeTest] 里用纯函数测。
 * 这里只验 ViewModel 有没有真的用那个组件、有没有做会话切换检查。
 *
 * 之所以改成"验调用"而不是"验内部实现"：之前那些断言直接扫 ViewModel 的
 * 协程体，抽一次函数就要改一次，而且改完还容易看错范围。
 */
class TranscriptRestoreWiringTest {

    private val vm: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText()
    }

    private fun reloadBody(): String {
        val at = vm.indexOf("fun reloadTranscript(");
        require(at > 0) { "找不到 reloadTranscript" };
        return vm.substring(at, minOf(at + 1200, vm.length));
    }

    @Test
    fun `binding a conversation rebuilds the transcript`() {
        val bindAt = vm.indexOf("fun bind(");
        assertTrue("bind 必须存在", bindAt > 0);
        val bindEnd = Regex("\\R    \\}\\R").find(vm, bindAt)?.range?.first
            ?: error("找不到 bind 函数结尾")
        val body = vm.substring(bindAt, bindEnd);
        assertTrue(
            "绑定会话时必须重建对话流，否则重启后是空白",
            body.contains("reloadTranscript()"),
        );
    }

    @Test
    fun `reload delegates reading and merging to the restorer`() {
        val body = reloadBody();
        assertTrue("读库交给 restorer", body.contains("restorer.read("));
        assertTrue("合并交给 restorer", body.contains("restorer.merge("));
        assertTrue(
            "重建要替换而不是追加，否则会重复",
            body.contains("replacePublished("),
        );
    }

    @Test
    fun `reload is skipped when there is no session`() {
        val body = reloadBody();
        assertTrue(
            "没有会话时不该查库",
            body.contains("if (currentSessionId.isBlank()) return"),
        );
    }

    @Test
    fun `reload drops results when the session changed mid read`() {
        val body = reloadBody();
        val guards = Regex("currentSessionId != sessionAtStart").findAll(body).count();
        assertTrue("首屏与补齐两段都要检查，实际 " + guards + " 处", guards >= 2);
    }

    @Test
    fun `transcript pages can load earlier messages`() {
        val display = Regex("TRANSCRIPT_DISPLAY_LIMIT = (\\d+)").find(vm)?.groupValues?.get(1)?.toIntOrNull();
        val page = Regex("TRANSCRIPT_PAGE_SIZE = (\\d+)").find(vm)?.groupValues?.get(1)?.toIntOrNull();
        assertTrue("显示页与分页量都必须存在", display != null && page != null);
        assertTrue(
            "单次对话投影必须有限",
            display!! <= 100,
        );
        assertTrue("较早消息使用 SessionRepository 的序列分页", vm.contains("restorer.readOlder(sessionAtStart"));
        assertTrue("旧消息加载后合并而非覆盖最新尾部", vm.contains("restorer.merge(page.messages, ui.transcript.value)"));
        assertTrue("分页按钮由可继续加载状态驱动", vm.contains("hasOlderTranscript"));
    }
}
