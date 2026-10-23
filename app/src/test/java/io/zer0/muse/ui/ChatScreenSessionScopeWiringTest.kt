package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guard the UI-only pending-tool recovery callbacks from regressing to task-session scope.
 */
class ChatScreenSessionScopeWiringTest {

    @Test
    fun `pending tool recovery uses the displayed mode-aware session`() {
        val source = locateChatScreenSource()

        assertFalse(
            source.contains("state.currentSessionId?.let { viewModel.resumePendingToolCalls(it) }"),
        )
        assertFalse(
            source.contains("state.currentSessionId?.let { viewModel.discardPendingToolCalls(it) }"),
        )
        assertTrue(
            source.contains("effectiveChatSessionId(state)?.let { viewModel.resumePendingToolCalls(it) }"),
        )
        assertTrue(
            source.contains("effectiveChatSessionId(state)?.let { viewModel.discardPendingToolCalls(it) }"),
        )
        assertFalse(
            source.contains("SessionTodoBar(sessionId = state.currentSessionId ?: \"\")"),
        )
        assertTrue(
            source.contains("SessionTodoBar(sessionId = effectiveChatSessionId(state).orEmpty())"),
        )
        assertFalse(source.contains("LaunchedEffect(state.currentSessionId)"))
        assertFalse(source.contains("LaunchedEffect(state.hasDraft, state.currentSessionId)"))
        assertFalse(source.contains("LaunchedEffect(state.targetMessageId, state.currentSessionId)"))
        assertTrue(source.contains("LaunchedEffect(effectiveChatSessionId(state))"))
        // v2.6.7: 草稿恢复到不弹 toast、不显示标签 —— 用户要求“不要任何提示，把输入框持久化就行”。
        // 原来这里断言存在 LaunchedEffect(state.hasDraft, effectiveChatSessionId(state))，
        // 那条 toast 已按要求删除，断言随之反转为“不该出现”。
        assertFalse(
            source.contains("LaunchedEffect(state.hasDraft, effectiveChatSessionId(state))"),
        )
        assertTrue(source.contains("LaunchedEffect(state.targetMessageId, effectiveChatSessionId(state))"))
        assertFalse(source.contains("trackedSessionId = state.currentSessionId"))
        assertFalse(source.contains("val sessionId = state.currentSessionId"))
    }

    private fun locateChatScreenSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/ChatScreen.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/ChatScreen.kt"),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate ChatScreen.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
