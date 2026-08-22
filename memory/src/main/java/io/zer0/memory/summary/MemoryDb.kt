package io.zer0.memory.summary

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Memory 模块的 Room 数据库。
 *
 * 汇总了 memory 系统的所有持久化表:
 *  - [SessionSummaryEntity]: session 摘要(rolling summary)
 *  - [DailyStateEntity]: 每日编译进度(daily-state.json 的 Room 版本)
 *  - [CompiledSectionEntity]: 四块编译产物(facts/today/week/longterm.md 的 Room 版本)
 *  - [FactEntity] 由独立的 [io.zer0.memory.fact.FactDb] 管理(因为 FTS5 需要 raw SQL)
 *
 * 注意: Phase 2 简化,把"文件系统 + facts.db"两套存储
 * 全部迁到 Room,Android 习惯更一致。
 */
@Database(
    entities = [
        SessionSummaryEntity::class,
        DailyStateEntity::class,
        CompiledSectionEntity::class,
        ScopedCompiledSectionEntity::class,
    ],
    version = 3,
    // v1.78 (H4): 开启 schema 导出,为未来 version 升级编写 Migration 提供基线
    exportSchema = true,
)
abstract class MemoryDb : RoomDatabase() {
    abstract fun sessionSummaryDao(): SessionSummaryDao
    abstract fun dailyStateDao(): DailyStateDao
    abstract fun compiledSectionDao(): CompiledSectionDao
    abstract fun scopedCompiledSectionDao(): ScopedCompiledSectionDao

    companion object {
        /** 仅增加列，旧摘要全部归入 default，不删除任何历史数据。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE session_summaries ADD COLUMN space_id TEXT NOT NULL DEFAULT 'default'",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS compiled_sections_scoped (
                        section_key TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        space_id TEXT NOT NULL,
                        content TEXT NOT NULL,
                        fingerprint TEXT,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(section_key, scope, space_id)
                    )
                    """.trimIndent(),
                )
                // 旧单空间产物只迁入兼容默认槽位，保留旧表作为回退与回滚源。
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO compiled_sections_scoped
                        (section_key, scope, space_id, content, fingerprint, updated_at)
                    SELECT section_key, 'main', 'default', content, fingerprint, updated_at
                    FROM compiled_sections
                    """.trimIndent(),
                )
            }
        }
    }
}
