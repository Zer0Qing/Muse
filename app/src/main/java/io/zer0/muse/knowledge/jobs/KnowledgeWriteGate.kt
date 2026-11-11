package io.zer0.muse.knowledge.jobs

/**
 * R9 恢复写入门禁 — 设计 §14.2「恢复前暂停领取并建立数据集写入隔离」。
 *
 * 恢复协调器在开始恢复前 [beginRestore],结束后 [endRestore];
 * [JobRunner] 通过 `writeAllowed` 注入读取本门禁状态,恢复期间不领取新作业。
 *
 * 说明:这是进程内门禁,不是跨进程锁;跨进程写保护依赖 Room 事务与恢复日志,
 * 不能把本对象当成跨进程互斥证明。
 */
object KnowledgeWriteGate {

    @Volatile
    private var restoreInProgress: Boolean = false

    fun beginRestore() {
        restoreInProgress = true
    }

    fun endRestore() {
        restoreInProgress = false
    }

    fun isWriteAllowed(): Boolean = !restoreInProgress
}
