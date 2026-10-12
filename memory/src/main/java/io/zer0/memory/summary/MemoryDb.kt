package io.zer0.memory.summary

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val MEMORY_DB_VERSION = 5

/**
 * Memory 模块的 Room 数据库。
 *
 * 汇总了 memory 系统的所有持久化表:
 *  - [SessionSummaryEntity]: session 摘要(rolling summary)
 *  - [ContextCheckpointEntity]: 上下文检查点(会话压缩产物,含边界指针)
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
        ContextCheckpointEntity::class,
        DailyStateEntity::class,
        CompiledSectionEntity::class,
        ScopedCompiledSectionEntity::class,
    ],
    version = MEMORY_DB_VERSION,
    // v1.78 (H4): 开启 schema 导出,为未来 version 升级编写 Migration 提供基线
    exportSchema = true,
)
abstract class MemoryDb : RoomDatabase() {
    abstract fun sessionSummaryDao(): SessionSummaryDao
    abstract fun contextCheckpointDao(): ContextCheckpointDao
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

        /**
         * v2.x: 新增上下文检查点表（会话压缩产物）。
         *
         * 纯增表：不触碰任何既有表，升级后旧会话只是"还没有检查点"，
         * 首次压缩时自然写入。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS context_checkpoints (
                        session_id TEXT NOT NULL,
                        covered_seq INTEGER NOT NULL,
                        last_covered_message_id TEXT NOT NULL DEFAULT '',
                        covered_count INTEGER NOT NULL DEFAULT 0,
                        summary TEXT NOT NULL,
                        tokens_before INTEGER NOT NULL DEFAULT 0,
                        tokens_after INTEGER NOT NULL DEFAULT 0,
                        strategy TEXT NOT NULL DEFAULT 'summary',
                        reason TEXT NOT NULL DEFAULT 'auto',
                        updated_at INTEGER NOT NULL,
                        space_id TEXT NOT NULL DEFAULT 'default',
                        PRIMARY KEY(session_id)
                    )
                    """.trimIndent(),
                )
                // 注意：不要在这里额外建索引 —— Room 会按实体的 @Entity 定义校验 schema，
                // 库里存在实体未声明的索引会导致打开数据库时报 schema 不一致。
            }
        }

        /**
         * v2.x: 检查点增加"累计并入摘要的条数"（界面分隔线展示用）。
         *
         * 只增列，旧检查点该值为 0 —— 展示层遇到 0 按"未知"处理，不回填也不猜测。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE context_checkpoints ADD COLUMN total_covered_count INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
