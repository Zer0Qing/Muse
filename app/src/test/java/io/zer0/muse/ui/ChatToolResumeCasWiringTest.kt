package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F2: 断点恢复工具调用追加消息必须走 CAS accessor,不能对
 * `messages.value` 整体赋值 —— 否则与流式节流写(updateAssistant)交错时
 * 会用旧快照覆盖,表现为恢复后的 TOOL 消息偶发消失。
 *
 * 与项目现有 wiring 测试同构:断言源码中的接线方式(这里无法稳定复现竞态,
 * 但接线一旦退回直接赋值,本测试即失败)。
 */
class ChatToolResumeCasWiringTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/chat/ChatToolResumeCoordinator.kt").readText()

    @Test
    fun `resume appends tool result message through CAS accessor`() {
        val src = source()
        assertTrue(
            "TOOL 结果消息必须通过 stateAccessor.updateMessages 追加",
            src.contains("host.stateAccessor.updateMessages { messages -> messages + toolMsg }"),
        )
    }

    @Test
    fun `resume appends assistant placeholder through CAS accessor`() {
        val src = source()
        assertTrue(
            "ASSISTANT 占位消息必须通过 stateAccessor.updateMessages 追加",
            src.contains("host.stateAccessor.updateMessages { messages -> messages + assistantMsg }"),
        )
    }

    @Test
    fun `resume path no longer overwrites messages state directly`() {
        val src = source()
        assertTrue(
            "恢复路径不应再出现 messages.value = 整体覆盖",
            !src.contains("host.stateStore.messages.value = host.stateStore.messages.value + toolMsg") &&
                !src.contains("host.stateStore.messages.value = host.stateStore.messages.value + assistantMsg"),
        )
    }
}
