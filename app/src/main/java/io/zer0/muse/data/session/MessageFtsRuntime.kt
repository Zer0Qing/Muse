package io.zer0.muse.data.session

/**
 * R-DB-05: 进程内消息 FTS 模式标记。
 *
 * 由 [io.zer0.muse.data.session.MuseDb] 在迁移/建表/onOpen 后根据
 * `sqlite_master.sql` 实际建表结果写入;SessionRepository 据此选择
 * FTS5 原文索引或 FTS4 ngram 回退路径。
 */
object MessageFtsRuntime {
    @Volatile
    var useFts5: Boolean = false
}
