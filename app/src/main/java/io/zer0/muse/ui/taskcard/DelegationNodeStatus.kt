package io.zer0.muse.ui.taskcard

/** 委派节点状态。 */
enum class DelegationNodeStatus {
    RUNNING,        // 执行中
    COMPLETED,      // 已完成
    FAILED,         // 失败
    TIMEOUT,        // 超时
    CANCELLED,      // 已取消
    PAUSED,         // 已暂停(等待用户确认)
}
