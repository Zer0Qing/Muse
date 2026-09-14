package io.zer0.common

/**
 * B-7: 进程级写入门控 — 备份恢复(文件级替换 DB)期间暂停高频后台写入。
 *
 * 背景: restoreFromAutoBackup 会 close 三个 Room 单例并用快照替换库文件,
 * 期间 MemoryTicker / ScheduledTaskRunner / 自动备份等协程若继续写库,
 * 会产生在途写丢失或新库 WAL 与已删附属文件不一致。
 *
 * 用法: 恢复流程开始时 [begin] 并将 [restoring] 置位,结束后 [end] 复位;
 * 各高频写入入口(记忆 tick、任务轮询、自动备份)在写库前检查 [restoring],
 * 为 true 时跳过本次写并记日志。仅影响"跳过一次写入",不阻塞 UI。
 */
object ProcessWriteGate {
    /** 是否正处于数据库文件级替换窗口(备份恢复)。进程内全局可见。 */
    @Volatile
    var restoring: Boolean = false
        private set

    /** 进入恢复窗口。返回 true 表示成功进入(即此前未处于恢复中)。 */
    fun begin(): Boolean {
        return synchronized(this) {
            if (restoring) {
                false
            } else {
                restoring = true
                true
            }
        }
    }

    /** 退出恢复窗口。 */
    fun end() {
        synchronized(this) {
            restoring = false
        }
    }
}