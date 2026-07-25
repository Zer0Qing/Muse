package io.zer0.memory.summary

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Memory 模块的 Room 数据库。
 *
 * 汇总了 memory 系统的所有持久化表:
 *  - [SessionSummaryEntity]: session 摘要(rolling summary)
 *  - [DailyStateEntity]: 每日编译进度(daily-state.json 的 Room 版本)
 *  - [CompiledSectionEntity]: 四块编译产物(facts/today/week/longterm.md 的 Room 版本)
 *  - [FactEntity] 由独立的 [io.zer0.memory.fact.FactDb] 管理(因为 FTS5 需要 raw SQL)
 *
 * 注意: Phase 2 简化,把 openhanako 的"文件系统 + facts.db"两套存储
 * 全部迁到 Room,Android 习惯更一致。
 */
@Database(
    entities = [
        SessionSummaryEntity::class,
        DailyStateEntity::class,
        CompiledSectionEntity::class,
    ],
    version = 1,
    // v1.78 (H4): 开启 schema 导出,为未来 version 升级编写 Migration 提供基线
    exportSchema = true,
)
abstract class MemoryDb : RoomDatabase() {
    abstract fun sessionSummaryDao(): SessionSummaryDao
    abstract fun dailyStateDao(): DailyStateDao
    abstract fun compiledSectionDao(): CompiledSectionDao
}
