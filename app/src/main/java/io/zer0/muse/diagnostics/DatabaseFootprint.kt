package io.zer0.muse.diagnostics

import android.content.Context
import java.io.File

/**
 * v2.6.23(S4): 数据库磁盘足迹 —— 只读汇总各 Room 库及其 -wal/-shm 的体积。
 *
 * 诊断卡用它回答「记忆页变慢是不是库太大了」。刻意只列**文件名与字节数**:
 * 文件名(muse.db / facts.db / facts_<assistant>.db)不含用户内容,可以随导出公开;
 * 绝对路径、表内数据、消息内容一律不进诊断导出。
 *
 * 已知库名集合来自真源(各 Db 类的 DB_NAME 常量);额外发现的 `*.db` 也列出,
 * 但按「未知库」标记 —— 新增库没登记进诊断,不该表现为「什么都没占用」。
 */
data class DatabaseFootprintEntry(
    /** 数据库文件名(不含目录)。 */
    val name: String,
    val mainBytes: Long,
    val walBytes: Long,
    val shmBytes: Long,
    /** 是否属于已知库名集合(未知 → 诊断卡上标「未登记」)。 */
    val known: Boolean,
) {
    val totalBytes: Long get() = mainBytes + walBytes + shmBytes
}

object DatabaseFootprint {

    /** 已登记进诊断的库(与各自 Db 类的常量保持一致)。 */
    val KNOWN_DB_NAMES: Set<String> = setOf(
        "muse.db",
        "facts.db",
        "memory.db",
        "memory_spaces.db",
    )

    /**
     * 扫描 databases 目录。子助手分库(facts_*.db / *_<id>.db)按前缀归为已知族。
     * IO 在调用方线程执行,由采集器放 IO 调度器。
     */
    fun collect(context: Context): List<DatabaseFootprintEntry> {
        val dir = context.getDatabasePath("probe.db").parentFile ?: return emptyList()
        val files = dir.listFiles().orEmpty()
        val mains = files.filter { it.isFile && it.name.endsWith(".db") }
        return mains.map { main ->
            val wal = File(main.parentFile, main.name + "-wal")
            val shm = File(main.parentFile, main.name + "-shm")
            DatabaseFootprintEntry(
                name = main.name,
                mainBytes = main.length(),
                walBytes = if (wal.exists()) wal.length() else 0L,
                shmBytes = if (shm.exists()) shm.length() else 0L,
                known = isKnownFamily(main.name),
            )
        }.sortedBy { it.name }
    }

    private fun isKnownFamily(name: String): Boolean = name in KNOWN_DB_NAMES ||
        name.startsWith("facts_") ||
        name == "muse.db" ||
        // 备份/恢复流程的临时库不应被误标成「未登记」
        name.startsWith("backup_") || name.endsWith(".pre-destructive.bak") || name.endsWith(".bak")
}
