package io.zer0.muse.ui

/**
 * 「非渲染消息」诊断日志的去重门。
 *
 * 聊天可见列表由 produceState 推出,messages 一变就重算 —— 流式输出时一秒能变七八次,
 * 同一对 (hidden, total) 会被反复写进调试日志,把真正要看的诊断信息冲掉。
 *
 * 这里只做一件事:记住上一次报过的值。同值连续到来不再记,值发生变化才记;
 * hidden 回到 0 时清空记忆,让「同一对数字再次出现」仍算新事件。
 */
internal class ChatRenderLogGate {

    private var lastReported: Pair<Int, Int>? = null

    /** 本次是否该写日志。 */
    fun shouldLog(hidden: Int, total: Int): Boolean {
        if (hidden <= 0) {
            lastReported = null
            return false
        }
        val current = hidden to total
        if (current == lastReported) return false
        lastReported = current
        return true
    }
}
