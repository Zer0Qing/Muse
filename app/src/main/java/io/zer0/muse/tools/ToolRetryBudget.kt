package io.zer0.muse.tools

/**
 * 工具重试预算的说明文案。
 *
 * ## 为什么需要它
 *
 * 助手自审第 15 条：「失败没有重试预算的可视化，我一次失败后就很保守，
 * 其实有些是可以安全重试的」。
 *
 * 现状：提示词只写了「失败只按错误信息修正一次」，模型不知道这是第几次失败、
 * 还剩几次机会。于是第一次失败就倾向于放弃，把可修复的问题直接报给用户。
 *
 * 这里把「第几次 / 还剩几次 / 该不该继续」算成一行明确的话，和失败分类一起
 * 附在回执里。预算上限与提示词里的「最多 1 次」保持一致，不另立一套口径。
 */
object ToolRetryBudget {

    /**
     * 单工具在本回合内的重试上限。
     *
     * 与提示词「失败只按错误信息修正一次」一致：首次失败后允许再试 1 次，
     * 即同一工具最多失败 [MAX_RETRIES] + 1 次。
     */
    const val MAX_RETRIES = 1

    /**
     * 生成预算说明。
     *
     * @param toolName 工具名（让模型知道这条预算针对哪个工具）
     * @param attempt 这是第几次失败（从 1 开始）
     * @param retryable 失败分类给出的可重试性
     */
    fun describe(toolName: String, attempt: Int, retryable: Boolean): String {
        val used = attempt.coerceAtLeast(1)
        val remaining = (MAX_RETRIES - (used - 1)).coerceAtLeast(0)
        return when {
            !retryable ->
                "[重试预算] $toolName 第 $used 次失败；本次失败不可重试（见上方 kind），不要再调用同一工具。"
            remaining > 0 ->
                "[重试预算] $toolName 第 $used 次失败；还剩 $remaining 次重试机会 —— " +
                    "确认能改对再试，不要把同一次失败重放。"
            else ->
                "[重试预算] $toolName 第 $used 次失败；重试机会已用尽 —— " +
                    "不要再调用同一工具，把真实原因和已尝试的做法告诉用户。"
        }
    }
}
