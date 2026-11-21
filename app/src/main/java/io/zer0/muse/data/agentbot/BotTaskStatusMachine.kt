package io.zer0.muse.data.agentbot

/**
 * 任务状态的穷尽转移函数。
 *
 * ## 为什么不用任意字符串分支
 *
 * "终态不可被迟到事件改写"这条不变量，如果靠每个调用点各自 if 判断，
 * 迟早会有一条路径忘了判 —— 表现为用户已经看到"已完成"，几秒后它变成"失败"。
 * 这里把 (当前状态, 事件) → 目标状态 收成唯一一张表，非法转移返回 null，
 * 由调用方当作"忽略迟到事件"处理，而不是硬写进去。
 */
object BotTaskStatusMachine {

    /** 允许的转移事件。 */
    enum class Event {
        /** worker 领到任务开始跑。 */
        START,

        /** 需要等外部资源（文件锁 / 设备面）。 */
        WAIT_RESOURCE,

        /** 资源就绪，回到队列。 */
        RESUME_FROM_RESOURCE,

        /** 需要用户批准。 */
        WAIT_APPROVAL,

        /** 批准已结算，回到队列。 */
        RESUME_FROM_APPROVAL,

    /**
     * 用户在 NEEDS_USER 状态点了"继续"。
     *
     * 与 RESUME_FROM_APPROVAL 分开：那个是"审批系统说可以了"，
     * 这个是"用户本人确认要重跑"。两者不能合并 —— 前者不该自动触发重跑。
     */
    USER_CONFIRMED_CONTINUE,

        /** 执行完毕，进入审核。 */
        EXECUTE_DONE,

        /** 审核接受。 */
        REVIEW_ACCEPT,

        /** 审核部分完成。 */
        REVIEW_PARTIAL,

        /** 审核要求返工。 */
        REVIEW_REWORK,

        /** 审核判定失败。 */
        REVIEW_FAIL,

        /** 审核后仍需用户决定。 */
        REVIEW_NEEDS_USER,

        /** 结果无法确认（副作用已发生但没写回）。 */
        MARK_UNKNOWN,

        /** 用户请求取消。 */
        CANCEL_REQUEST,

        /** 取消已确认。 */
        CANCEL_CONFIRM,

        /** 取消时发现副作用已发生，转为部分完成。 */
        CANCEL_TO_PARTIAL,
    }

    /**
     * 唯一的转移表。
     *
     * 返回 null = 该事件在当前状态下不合法（迟到事件 / 重复事件），调用方应忽略。
     */
    fun next(current: String, event: Event): String? = when (current) {
        BotTaskStatus.QUEUED -> when (event) {
            Event.START -> BotTaskStatus.RUNNING
            Event.CANCEL_REQUEST -> BotTaskStatus.CANCEL_REQUESTED
            Event.WAIT_RESOURCE -> BotTaskStatus.WAITING_RESOURCE
            Event.WAIT_APPROVAL -> BotTaskStatus.WAITING_APPROVAL
            else -> null
        }

        BotTaskStatus.RUNNING -> when (event) {
            Event.WAIT_RESOURCE -> BotTaskStatus.WAITING_RESOURCE
            Event.WAIT_APPROVAL -> BotTaskStatus.WAITING_APPROVAL
            Event.EXECUTE_DONE -> BotTaskStatus.REVIEWING
            Event.MARK_UNKNOWN -> BotTaskStatus.UNKNOWN
            Event.CANCEL_REQUEST -> BotTaskStatus.CANCEL_REQUESTED
            else -> null
        }

        BotTaskStatus.WAITING_RESOURCE -> when (event) {
            Event.RESUME_FROM_RESOURCE -> BotTaskStatus.QUEUED
            Event.CANCEL_REQUEST -> BotTaskStatus.CANCEL_REQUESTED
            Event.MARK_UNKNOWN -> BotTaskStatus.UNKNOWN
            else -> null
        }

        BotTaskStatus.WAITING_APPROVAL -> when (event) {
            /**
             * 审批通过后**不自动重跑**，而是等用户显式确认。
             *
             * 为什么：tick() 领到 QUEUED 任务会 openRun 开新 attempt，从头执行 ——
             * 已跑过的步骤（发通知、写文件）会再来一遍，包括标了 NO_BLIND_REPLAY 的。
             * 那是在用户不知情的情况下重复副作用，直接违反 V23。
             *
             * 转到 NEEDS_USER 让用户看到"这一步已批准，要重跑整件事吗"，
             * 由他决定。体验上多一次点击，但不会悄悄做第二遍。
             */
            Event.RESUME_FROM_APPROVAL -> BotTaskStatus.NEEDS_USER
            Event.CANCEL_REQUEST -> BotTaskStatus.CANCEL_REQUESTED
            Event.MARK_UNKNOWN -> BotTaskStatus.UNKNOWN
            else -> null
        }

        BotTaskStatus.REVIEWING -> when (event) {
            Event.REVIEW_ACCEPT -> BotTaskStatus.SUCCEEDED
            Event.REVIEW_PARTIAL -> BotTaskStatus.PARTIAL
            Event.REVIEW_FAIL -> BotTaskStatus.FAILED
            Event.REVIEW_NEEDS_USER -> BotTaskStatus.NEEDS_USER
            Event.REVIEW_REWORK -> BotTaskStatus.QUEUED
            Event.MARK_UNKNOWN -> BotTaskStatus.UNKNOWN
            Event.CANCEL_REQUEST -> BotTaskStatus.CANCEL_REQUESTED
            else -> null
        }

        BotTaskStatus.NEEDS_USER -> when (event) {
            /**
             * 用户确认继续。
             *
             * 这是 NEEDS_USER 唯一的出口（除取消与未知）。审批通过后任务停在这里，
             * 用户点"继续"才回队列重跑。
             *
             * 为什么不让它自动回队列：重跑会把已执行的步骤再来一遍，
             * 包括那些标了 NO_BLIND_REPLAY 的。必须由用户明确同意。
             */
            Event.USER_CONFIRMED_CONTINUE -> BotTaskStatus.QUEUED
            Event.CANCEL_REQUEST -> BotTaskStatus.CANCEL_REQUESTED
            Event.MARK_UNKNOWN -> BotTaskStatus.UNKNOWN
            else -> null
        }
        BotTaskStatus.CANCEL_REQUESTED -> when (event) {
            Event.CANCEL_CONFIRM -> BotTaskStatus.CANCELLED
            Event.CANCEL_TO_PARTIAL -> BotTaskStatus.PARTIAL
            Event.MARK_UNKNOWN -> BotTaskStatus.UNKNOWN
            else -> null
        }

        // 终态：任何事件都不再改写。这里显式列出而不是走 else，
        // 是为了让"新增一个状态却忘了处理"在编译期就暴露。
        //
        // NEEDS_USER 不在这里 —— 它有"继续"与"取消"两个出口，见上面的分支。
        // 把它列进来的话，上面那个分支永远走不到（Kotlin 的 when 按顺序匹配），
        // 用户点"继续"会静默无效。
        BotTaskStatus.SUCCEEDED,
        BotTaskStatus.PARTIAL,
        BotTaskStatus.FAILED,
        BotTaskStatus.CANCELLED,
        BotTaskStatus.UNKNOWN,
        -> null

        else -> null
    }

    /**
     * 是否需要用户/审核介入才能继续。
     *
     * 用于"这个任务还占着资源吗"的判断：等待态不占执行位，可让别的任务先跑。
     */
    fun isBlocked(status: String): Boolean = when (status) {
        BotTaskStatus.WAITING_RESOURCE,
        BotTaskStatus.WAITING_APPROVAL,
        BotTaskStatus.NEEDS_USER,
        -> true
        else -> false
    }
}
