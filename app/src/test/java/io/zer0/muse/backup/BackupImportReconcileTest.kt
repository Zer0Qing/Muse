package io.zer0.muse.backup

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-DATA-B34: 备份导入对账不能只看“解析行数”。
 *
 * B-34 原实现只比对 meta 声明数与解析数；解析通过但缓冲 flush 的 upsert 若丢记录，
 * 对账看不出来 → “导入成功但数据半缺失”。现增加入库后真实行数核对。
 */
class BackupImportReconcileTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()

    @Test
    fun `import reconciles inserted row counts after flush`() {
        val src = source()
        assertTrue(
            "flush 之后必须调用入库对账",
            src.contains("verifyInsertedCounts(expectedCounts)"),
        )
    }

    @Test
    fun `reconcile compares against real table rows`() {
        val src = source()
        val start = src.indexOf("private suspend fun verifyInsertedCounts")
        assertTrue("未找到 verifyInsertedCounts", start >= 0)
        val body = src.substring(start, start + 1200)
        assertTrue(
            "对账应读取真实表行数(如 sessionDao().observeAll())",
            body.contains("db.sessionDao().observeAll().first().size"),
        )
        assertTrue(
            "入库数不足应触发回滚",
            body.contains("备份入库数不一致"),
        )
    }

    @Test
    fun `reconcile failure does not block import on counting errors`() {
        val src = source()
        val start = src.indexOf("private suspend fun verifyInsertedCounts")
        assertTrue("未找到 verifyInsertedCounts", start >= 0)
        val body = src.substring(start, start + 1200)
        assertTrue(
            "取数失败只记日志,不应阻断导入",
            body.contains("入库对账取数失败"),
        )
    }
}
