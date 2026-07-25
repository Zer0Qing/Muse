package io.zer0.memory.fact

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Fact 数据库 (openhanako fact-store.ts schema 移植)。
 *
 * v3 schema: 仅 facts 主表(LIKE 搜索,无 FTS)。
 * v4 schema: 新增 importance 字段(0=普通,1=重要,2=关键),关键事实永不衰减。
 * v5 schema: 新增 category / confidence / source / expires_at / last_confirmed_at 结构化字段。
 * v6 schema: 新增 facts_fts FTS4 虚拟表(中文 ngram 全文索引),保留 LIKE 作为单字/异常回退。
 * v7 schema: 新增 last_hit_at 字段,支持命中加成(hitBonus)重置衰减时钟。
 * v8 schema: 新增 scope 字段(记忆作用域,默认 "main" 表示主助手作用域),
 *   用于隔离不同 Agent 的记忆,避免子助手误用主助手事实或团队成员记忆混淆。
 *
 * FTS4 选型说明:
 *  - 部分国产 ROM(如 OPPO Android 16)的 SQLite 未编译 FTS5 模块,
 *    `CREATE VIRTUAL TABLE ... USING fts5(...)` 会抛 `no such module: fts5`。
 *  - FTS4 自 SQLite 3.7.4(2010)内置,Android 自带 SQLite 均支持,兼容性可靠。
 *  - 中文检索由应用层 [FactFtsManager.toNgram] 预处理为 2-gram,不依赖内置 tokenizer。
 */
@Database(
    entities = [FactEntity::class, FactFtsEntity::class],
    version = 8,
    // v1.78 (H4): 开启 schema 导出,未来 v4+ 升级时编写 Migration 替代 destructive
    // 历史 v1→v2→v3 的 destructive migration 已无法补救,从 v3 开始留基线
    exportSchema = true,
)
abstract class FactDb : RoomDatabase() {

    abstract fun factDao(): FactDao

    companion object {
        /**
         * v3→v4 迁移: 新增 importance 列(默认 0=普通)+ 索引。
         * ALTER TABLE ADD COLUMN 是 SQLite 原生 DDL,Android 16 无 execSQL DML 限制。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE facts ADD COLUMN importance INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_facts_importance ON facts(importance)")
            }
        }

        /**
         * v4→v5 迁移: 新增结构化事实字段(category / confidence / source / expires_at / last_confirmed_at)。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE facts ADD COLUMN category TEXT NOT NULL DEFAULT 'general'")
                db.execSQL("ALTER TABLE facts ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE facts ADD COLUMN source TEXT NOT NULL DEFAULT 'inferred'")
                db.execSQL("ALTER TABLE facts ADD COLUMN expires_at TEXT")
                db.execSQL("ALTER TABLE facts ADD COLUMN last_confirmed_at TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_facts_category ON facts(category)")
            }
        }

        /**
         * v5→v6 迁移: 新增 facts_fts FTS4 虚拟表 + 级联删除触发器。
         *
         * 注意:
         * - SQL 必须与 @Fts4 注解生成的完全一致(含反引号 + 列顺序),否则 Room schema 校验失败。
         * - 迁移只建空表,不填数据。首次启动/首次搜索由 [FactStore.ensureFtsIndexConsistent]
         *   比较两表 count 后全量 rebuild(用 [FactFtsManager.toNgram] 转换)。
         * - 不在迁移里 INSERT SELECT: SQL 无法调用 Kotlin ngram 函数,直接塞原文会导致
         *   索引/查询不一致(MATCH 不到)。rebuild 必须在 Kotlin 层做。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `facts_fts` USING FTS4(" +
                        "`fact_id` INTEGER, `content_ngram` TEXT" +
                        ")"
                )
                createFtsCleanupTrigger(db)
            }
        }

        /**
         * v6: 创建 facts 表级联清理 facts_fts 的触发器。
         * 用于 [FactStore.applyDecay] 等批量 DELETE 场景自动同步索引,避免孤儿行。
         */
        private fun createFtsCleanupTrigger(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS facts_after_delete AFTER DELETE ON facts " +
                    "BEGIN DELETE FROM facts_fts WHERE fact_id = old.id; END;"
            )
        }

        /**
         * v6→v7 迁移: 新增 last_hit_at 列,用于命中加成衰减时钟。
         * 历史数据默认保持 NULL(未命中状态),后续命中后写入当前时间。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE facts ADD COLUMN last_hit_at TEXT")
            }
        }

        /**
         * v7→v8 迁移: 新增 scope 列(记忆作用域,默认 "main")+ 索引。
         *
         * scope 用于隔离不同 Agent(主助手/子助手/团队成员)的记忆:
         *  - 历史数据全部默认为 "main"(主助手作用域),迁移后行为不变
         *  - 新增子助手记忆时由 FactStore.add(scope = assistantId) 写入
         *  - 查询时按 scope 过滤,避免跨作用域误用
         *
         * 索引名 index_facts_scope 与 @Index 注解默认命名一致
         * (Room 自动生成 `index_<table>_<column>`)。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE facts ADD COLUMN scope TEXT NOT NULL DEFAULT 'main'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_facts_scope ON facts(scope)")
            }
        }

        /** 单例数据库实例。全局唯一,内存数据库失败时回退。 */
        fun create(context: Context, name: String = "facts.db"): FactDb {
            return Room.databaseBuilder(context, FactDb::class.java, name)
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        createFtsCleanupTrigger(db)
                    }
                })
                // v1.78 (M4): 移除 upgrade 的 destructive migration,避免升级时静默清空用户事实;
                // 仅保留降级保护(从历史更高版本降到当前 v6 时不崩溃)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
        }
    }
}
