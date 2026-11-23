package io.zer0.muse.backup;

import org.junit.Assert.assertTrue;
import org.junit.Test;
import java.io.File;

/**
 * 单 JSON 恢复路径必须覆盖中枢八表。

 * 独立审查发现的 P1-6：三处**导出**（buildBackup / DbSnapshot / NDJSON 行）都齐全，
 * 但 `applyBackupInternal` 里完全没有这八张表 —— 备份文件里带着它们，
 * 导入后却全部丢失。
 *
 * 而 `applyBackupInternal` 同时是失败回滚的 recovery 路径，
 * 只补一半会让回滚与提交不对称。
 */
class BotTablesJsonRestoreTest {

    private val source: String by lazy {
        File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText();
    }

    private fun applyInternalBody(): String {
        val at = source.indexOf("private suspend fun applyBackupInternal(");
        require(at > 0) { "找不到 applyBackupInternal" };
        val end = source.indexOf("        // 2. 导入 memory 数据", at);
        return source.substring(at, if (end > at) end else source.length);
    }

    @Test
    fun `all eight bot tables are cleared before restore`() {
        val body = applyInternalBody();
        listOf(
            "botAnnotationDao().deleteAll()",
            "publicationOutboxDao().deleteAll()",
            "approvalRequestDao().deleteAll()",
            "botEventDao().deleteAll()",
            "taskStepDao().deleteAll()",
            "taskRunDao().deleteAll()",
            "botTaskDao().deleteAll()",
            "botBindingDao().deleteAll()",
        ).forEach { call ->
            assertTrue("缺清空：" + call + " —— 恢复会把新数据叠在旧数据上", body.contains(call));
        };
    }

    @Test
    fun `all eight bot tables are written back`() {
        val body = applyInternalBody();
        listOf(
            "backup.botBindings.forEach { db.botBindingDao().upsert(it) }",
            "backup.botTasks.forEach { db.botTaskDao().upsert(normalizeBotTaskForRestore(it, restoreAt)) }",
            "db.taskRunDao().upsertAll(backup.botTaskRuns.map",
            "db.taskStepDao().upsertAll(backup.botTaskSteps)",
            "db.botEventDao().upsertAll(backup.botEvents)",
            "db.approvalRequestDao().upsertAll(backup.botApprovals)",
            "db.publicationOutboxDao().upsertAll(backup.botPublications)",
            "db.botAnnotationDao().upsertAll(backup.botAnnotations)",
        ).forEach { call ->
            assertTrue("缺写回：" + call + " —— 导出了但导不回", body.contains(call));
        };
    }

    @Test
    fun `backlog restore clears dispatch state and writes claim safe snapshot`() {
        val body = applyInternalBody()
        assertTrue(body.contains("db.proactiveBacklogDao().deleteAll()"))
        assertTrue(body.contains("db.proactiveBacklogScheduleDao().deleteAll()"))
        assertTrue(body.contains("prepareBacklogForRestore(backup, restoreAt)"))
        assertTrue(body.contains("db.proactiveBacklogDao().upsertAll(restoredBacklog.dispatches)"))
        assertTrue(body.contains("restoredBacklog.schedule.forEach"))
    }
}
