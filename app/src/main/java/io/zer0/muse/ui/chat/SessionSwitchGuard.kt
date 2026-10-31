package io.zer0.muse.ui.chat

import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.x: 会话/模式切换的序号守卫。
 *
 * 背景(2026-09-28 用户反馈:Agent 页偶发串对话 — 另一个会话的内容闪现、关掉再打开才恢复):
 * [io.zer0.muse.ui.chat.ChatSessionController.setAgentMode] /
 * [io.zer0.muse.ui.chat.ChatSessionController.switchSession] /
 * ChatViewModel.switchAgentAssistant 的异步加载(查库 → 提交 messages + UI 状态)此前没有
 * 过期校验:快速切换时先发起的加载可能后完成,把「旧会话」的消息与状态硬盖到当前界面。
 *
 * 用法:切换入口同步调用 [begin] 取得本次序号;异步任务在提交(写 messages/state)前调用
 * [isStale],为 true 则丢弃本次结果(必要时先释放本次 acquire 的会话引用)。
 * 不变量:最后一次发起的切换永远能提交,过期任务永不覆盖它。
 *
 * 序号是实例状态,每个界面实例各持一份:需要排序的是「同一个界面上连续发起的切换」,
 * 跨实例排序没有意义。早先写成进程级单例,导致一个实例的迟到任务会把另一个实例的
 * 切换判成过期(测试里表现为焦点恢复被丢弃)。
 */
internal class SessionSwitchGuard {
    private val seq = AtomicInteger(0)

    /** 发起一次切换,返回本次切换的序号。 */
    fun begin(): Int = seq.incrementAndGet()

    /** 该序号是否已被更新的切换取代。 */
    fun isStale(token: Int): Boolean = token != seq.get()

    /**
     * 仅供测试：把序号归零。
     *
     * 实例状态本就随实例销毁，这个方法留给需要复用同一实例的用例显式复位。
     */
    @androidx.annotation.VisibleForTesting
    fun resetForTest() {
        seq.set(0)
    }
}
