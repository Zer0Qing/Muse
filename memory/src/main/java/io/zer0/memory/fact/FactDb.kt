package io.zer0.memory.fact

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.zer0.memory.ai.MemoryLinkEntity
import io.zer0.memory.space.MemorySpaceEntity
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Fact 数据库。
 *
 * v3 schema: 仅 facts 主表(LIKE 搜索,无 FTS)。
 * v4 schema: 新增 importance 字段(0=普通,1=重要,2=关键),关键事实永不衰减。
 * v5 schema: 新增 category / confidence / source / expires_at / last_confirmed_at 结构化字段。
 * v6 schema: 新增 facts_fts FTS4 虚拟表(中文 ngram 全文索引),保留 LIKE 作为单字/异常回退。
 * v7 schema: 新增 last_hit_at 字段,支持命中加成(hitBonus)重置衰减时钟。
 * v8 schema: 新增 scope 字段(记忆作用域,默认 "main" 表示主助手作用域),
 *   用于隔离不同 Agent 的记忆,避免子助手误用主助手事实或团队成员记忆混淆。
 * v9 schema: 新增 space_id 字段(记忆空间,默认 "default"),用于多 Space 隔离
 *   (类似 Notion 工作区,工作/生活/学习场景互不干扰);同时新增 memory_spaces 表
 *   存储 Space 元数据。space_id 与 scope 正交:scope 按 Agent 隔离,space_id 按场景隔离。
 * v10 schema: 新增 memory_links 表(记忆知识图谱边),存储事实间关系
 *   (causes/explains/part_of/related_to/contradicts),用于 AI 驱动记忆管理
 *   构建用户记忆图谱(P2-3)。
 * v12 schema: 新增 entity_key 列(实体归一化键),同一实体不同写法共享同一键,
 *   用于写入时精确查重与跨写法合并(解决同名重复记忆)。历史数据为 NULL,
 *   由反思任务在整理时回填。
 * v13 schema: 新增 fact_revisions 表(关键记忆修订记录),支持审计与回滚。
 *
 * FTS4 选型说明:
 *  - 部分国产 ROM(如 OPPO Android 16)的 SQLite 未编译 FTS5 模块,
 *    `CREATE VIRTUAL TABLE ... USING fts5(...)` 会抛 `no such module: fts5`。
 *  - FTS4 自 SQLite 3.7.4(2010)内置,Android 自带 SQLite 均支持,兼容性可靠。
 *  - 中文检索由应用层 [FactFtsManager.toNgram] 预处理为 2-gram,不依赖内置 tokenizer。
 */
@Database(
    entities = [FactEntity::class, FactFtsEntity::class, MemorySpaceEntity::class, MemoryLinkEntity::class, FactRevisionEntity::class],
    version = 13,
    // v1.78 (H4): 开启 schema 导出,未来 v4+ 升级时编写 Migration 替代 destructive
    // 历史 v1→v2→v3 的 destructive migration 已无法补救,从 v3 开始留基线
    exportSchema = true,
)
abstract class FactDb : RoomDatabase() {

    abstract fun factDao(): FactDao

    /** v13 (T4-1): 事实修订记录 DAO。 */
    abstract fun factRevisionDao(): FactRevisionDao

    abstract fun memorySpaceDao(): io.zer0.memory.space.MemorySpaceDao

    /** v10: 记忆知识图谱边 DAO(P2-3)。 */
    abstract fun memoryLinkDao(): io.zer0.memory.ai.MemoryLinkDao

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

        /**
         * v8→v9 迁移: P2-2 多 Space 记忆隔离。
         *
         *  1. facts 表新增 space_id 列(默认 "default"),并建索引。
         *  2. 新建 memory_spaces 表存储 Space 元数据(id/name/icon/description/created_at/sort_index)。
         *  3. 插入默认 Space("default"),历史 facts 通过 DEFAULT 'default' 自动归入。
         *
         * 注意:
         *  - ALTER TABLE ADD COLUMN 在 SQLite 中是 O(1) 元数据操作,不会重写整张表。
         *  - memory_spaces 表结构必须与 [MemorySpaceEntity] @Entity 注解生成的 SQL 完全一致,
         *    否则 Room schema 校验失败(包括列顺序、类型、默认值、索引名)。
         *  - 索引名 idx_memory_spaces_sort 与 @Index(name = "idx_memory_spaces_sort") 对齐。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. facts 表新增 space_id 列
                db.execSQL("ALTER TABLE facts ADD COLUMN space_id TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_facts_space_id ON facts(space_id)")

                // 2. 创建 memory_spaces 表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memory_spaces (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        icon TEXT,
                        description TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL,
                        sort_index INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_spaces_sort ON memory_spaces(sort_index)")

                // 3. 插入默认 Space(用 INSERT OR IGNORE 防止重复)
                val defaultCreatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO memory_spaces (id, name, icon, description, created_at, sort_index)
                    VALUES ('default', '默认', 'bookmark', '', '$defaultCreatedAt', 0)
                    """.trimIndent()
                )
            }
        }

        /**
         * v9→v10 迁移: P2-3 记忆知识图谱。
         *
         *  新建 memory_links 表存储事实间关系(causes/explains/part_of/related_to/contradicts)。
         *  - source_fact_id / target_fact_id 指向 facts.id(不加 FOREIGN KEY,避免级联性能损耗)
         *  - source_title / target_title 冗余标题(事实删除后仍可展示关系语义)
         *  - space_id / scope 与 facts 表对齐,支持多 Space + 多 Agent 隔离
         *  - weight 关系强度 0.0~1.0
         *
         * 注意:
         *  - 表结构必须与 [MemoryLinkEntity] @Entity 注解生成的 SQL 完全一致
         *    (列名/类型/默认值/索引名),否则 Room schema 校验失败。
         *  - 索引名 idx_memory_links_* 与 @Index(name = ...) 对齐。
         *  - 历史数据无 memory_links 记录,迁移仅建空表。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memory_links (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        source_fact_id INTEGER NOT NULL,
                        target_fact_id INTEGER NOT NULL,
                        source_title TEXT NOT NULL,
                        target_title TEXT NOT NULL,
                        link_type TEXT NOT NULL DEFAULT 'related_to',
                        weight REAL NOT NULL DEFAULT 0.5,
                        space_id TEXT NOT NULL DEFAULT 'default',
                        scope TEXT NOT NULL DEFAULT 'main',
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_links_source ON memory_links(source_fact_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_links_target ON memory_links(target_fact_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_links_space ON memory_links(space_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_links_scope ON memory_links(scope)")
            }
        }

        /**
         * B4-05: v10→v11 迁移 — facts 表加 pinned_at 列(手动置顶记忆)。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE facts ADD COLUMN pinned_at TEXT DEFAULT NULL")
            }
        }

        /**
         * v11→v12 迁移 — facts 表加 entity_key 列(实体归一化键)。
         *
         * - 仅 ADD COLUMN,SQLite O(1) 元数据操作,不重写表,历史数据无损。
         * - 新列可空,历史数据保持 NULL,由反思任务在整理时按实体名回填。
         * - 索引 idx_facts_entity_key 加速写入时查重(同实体键候选扫描)。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE facts ADD COLUMN entity_key TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_facts_entity_key ON facts(entity_key)")
            }
        }

        /**
         * v12→v13 迁移 — 新建 fact_revisions 表(关键记忆修订记录)。
         * 仅建空表,历史无修订数据。表结构必须与 [FactRevisionEntity] 对齐。
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS fact_revisions (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        fact_id INTEGER NOT NULL,
                        old_content TEXT NOT NULL,
                        new_content TEXT NOT NULL,
                        changed_at TEXT NOT NULL,
                        reason TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_revisions_fact_id ON fact_revisions(fact_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_revisions_changed_at ON fact_revisions(changed_at)")
            }
        }
        /**
         * R-DB-03: 归档早期 v1/v2 或损坏的 facts 数据库。
         * 归档为 <name>.bak 后由 Room 重建空库,避免打开时崩溃。
         */
        private fun archiveLegacyOrCorruptDatabase(context: Context, name: String) {
            val file = context.getDatabasePath(name)
            if (!file.exists()) return

            val legacyOrCorrupt = try {
                val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                val version = db.version
                db.close()
                version < 3
            } catch (_: Exception) {
                true
            }
            if (!legacyOrCorrupt) return

            val bak = File(file.parentFile, "$name.bak")
            runCatching { if (bak.exists()) bak.delete() }
            val renamed = runCatching { file.renameTo(bak) }.getOrDefault(false)
            if (!renamed) {
                // 归档失败时仍删除旧库,避免 Room 打开即崩溃;早期数据已不可用。
                runCatching { file.delete() }
            }
            listOf(file, File(file.parentFile, "$name-wal"), File(file.parentFile, "$name-shm")).forEach {
                runCatching { if (it.exists()) it.delete() }
            }
            MemoryLegacyReset.mark(context, name)
        }

        /**
         * 审计修复 (0.2): 未知/更高版本数据库在 destructive fallback 前归档。
         * 原实现 fallbackToDestructiveMigration 会静默清空用户事实记忆且无备份;
         * 这里在 Room 打开前检查版本: 高于迁移链覆盖(高版本降级)或版本异常时,
         * 先重命名 .bak 保留数据,Room 再重建空库,数据可恢复。
         */
        private fun archiveUnknownVersionDatabase(context: Context, name: String) {
            val file = context.getDatabasePath(name)
            if (!file.exists()) return
            val version = try {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
            } catch (_: Exception) {
                return // 损坏库交给 archiveLegacyOrCorruptDatabase 处理
            }
            if (version <= 13) return // 迁移链覆盖范围内(3..13)
            val bak = File(file.parentFile, "$name.pre-destructive.bak")
            runCatching { if (bak.exists()) bak.delete() }
            val renamed = runCatching { file.renameTo(bak) }.getOrDefault(false)
            if (renamed) {
                listOf(
                    file,
                    File(file.parentFile, "$name-wal"),
                    File(file.parentFile, "$name-shm"),
                ).forEach { runCatching { if (it.exists()) it.delete() } }
                MemoryLegacyReset.mark(context, name)
            }
        }

        /** 单例数据库实例。全局唯一,内存数据库失败时回退。 */
        fun create(context: Context, name: String = "facts.db"): FactDb {
            archiveLegacyOrCorruptDatabase(context, name)
            archiveUnknownVersionDatabase(context, name)
            return Room.databaseBuilder(context, FactDb::class.java, name)
                .addMigrations(
                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        createFtsCleanupTrigger(db)
                        // v9: 全新安装时插入默认 Space(与 MIGRATION_8_9 行为对齐)
                        val defaultCreatedAt = LocalDateTime.now()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        db.execSQL(
                            """
                            INSERT OR IGNORE INTO memory_spaces
                                (id, name, icon, description, created_at, sort_index)
                            VALUES ('default', '默认', 'bookmark', '', '$defaultCreatedAt', 0)
                            """.trimIndent()
                        )
                    }
                })
                // v1.78 (M4): 移除 upgrade 的 destructive migration,避免升级时静默清空用户事实;
                // 仅保留降级保护(从历史更高版本降到当前 v11 时不崩溃)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                // R-DB-03: 早期 v1/v2 或损坏库已在上方归档;未知版本兜底重建,避免崩溃。
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
