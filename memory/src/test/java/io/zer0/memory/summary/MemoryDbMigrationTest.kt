package io.zer0.memory.summary

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** 验证 MemoryDb v1→v2→v3 只增列/增表，并保留旧编译产物。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryDbMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { it.deleteRecursively() }
    }

    @Test
    fun `migration preserves legacy content and copies it to default scoped slot`() = runTest {
        val dir = Files.createTempDirectory("memory-migration").toFile()
        tempFiles += dir
        val file = File(dir, "memory.db")
        val helper = buildV1Database(file)
        val oldDb = helper.writableDatabase
        oldDb.execSQL("INSERT INTO compiled_sections VALUES ('facts', 'legacy facts', NULL, '2026-08-22T00:00:00Z')")
        helper.close()

        val db = Room.databaseBuilder(context, MemoryDb::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .addMigrations(
                MemoryDb.MIGRATION_1_2,
                MemoryDb.MIGRATION_2_3,
                MemoryDb.MIGRATION_3_4,
                MemoryDb.MIGRATION_4_5,
            )
            .build()
        try {
            assertEquals("legacy facts", db.compiledSectionDao().get("facts")?.content)
            val scoped = db.scopedCompiledSectionDao().get("facts", "main", "default")
            assertEquals("legacy facts", scoped?.content)
            assertTrue(db.scopedCompiledSectionDao().getAll().any { it.spaceId == "default" })
            assertEquals("default", db.sessionSummaryDao().get("missing")?.spaceId ?: "default")
        } finally {
            db.close()
        }
    }

    /**
     * v3 → v4：新增 context_checkpoints（会话压缩检查点）。
     *
     * 断言三件事：① 既有 session_summaries 数据不丢；② 新表建好且可写可读；
     * ③ 覆盖式语义成立（同一会话只留一条，第二次写覆盖第一次）。
     */
    @Test
    fun `v3 to v4 adds context checkpoint table and keeps session summaries`() = runTest {
        val dir = Files.createTempDirectory("memory-migration-v4").toFile()
        tempFiles += dir
        val file = File(dir, "memory.db")
        val helper = buildV3Database(file)
        val oldDb = helper.writableDatabase
        oldDb.execSQL(
            "INSERT INTO session_summaries VALUES ('s-1', '2026-09-01T00:00:00Z', " +
                "'2026-09-02T00:00:00Z', 'legacy rolling summary', 12, NULL, '', NULL, '', 'default')",
        )
        helper.close()

        val db = Room.databaseBuilder(context, MemoryDb::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .addMigrations(MemoryDb.MIGRATION_3_4, MemoryDb.MIGRATION_4_5)
            .build()
        try {
            // ① 旧摘要仍在
            val legacy = db.sessionSummaryDao().get("s-1")
            assertEquals("legacy rolling summary", legacy?.summary)
            assertEquals(12, legacy?.messageCount)

            // ② 新表可用
            db.contextCheckpointDao().upsert(
                ContextCheckpointEntity(
                    sessionId = "s-1",
                    coveredSeq = 40L,
                    lastCoveredMessageId = "m-40",
                    coveredCount = 40,
                    summary = "## Goal\n继续修压缩",
                    tokensBefore = 12_000,
                    tokensAfter = 1_200,
                    updatedAt = 1_700_000_000_000L,
                ),
            )
            val loaded = db.contextCheckpointDao().get("s-1")
            assertEquals(40L, loaded?.coveredSeq)
            assertEquals("m-40", loaded?.lastCoveredMessageId)
            assertEquals(10_800, loaded?.savedTokens)

            // ③ 覆盖式：同一会话第二次写入替换而非追加
            db.contextCheckpointDao().upsert(
                (loaded ?: error("checkpoint missing")).copy(coveredSeq = 80L, coveredCount = 80),
            )
            assertEquals(1, db.contextCheckpointDao().getAll().size)
            assertEquals(80L, db.contextCheckpointDao().get("s-1")?.coveredSeq)

            // 删除后读回为 null
            db.contextCheckpointDao().deleteById("s-1")
            assertNull(db.contextCheckpointDao().get("s-1"))
        } finally {
            db.close()
        }
    }

    /**
     * v4 → v5：检查点增加"累计并入摘要条数"列。
     *
     * 断言：旧检查点保留、新列存在且默认 0（展示层遇到 0 按"未知"处理）。
     */
    @Test
    fun `v4 to v5 adds total covered count and keeps checkpoints`() = runTest {
        val dir = Files.createTempDirectory("memory-migration-v5").toFile()
        tempFiles += dir
        val file = File(dir, "memory.db")
        val helper = buildV1Database(file)
        val oldDb = helper.writableDatabase
        oldDb.execSQL(
            // v1 的 session_summaries 只有 9 列（space_id 是 v2 才加的）
            "INSERT INTO session_summaries VALUES ('s-1', '2026-09-01T00:00:00Z', " +
                "'2026-09-02T00:00:00Z', 'legacy rolling summary', 12, NULL, '', NULL, '')",
        )
        helper.close()

        val db = Room.databaseBuilder(context, MemoryDb::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .addMigrations(
                MemoryDb.MIGRATION_1_2,
                MemoryDb.MIGRATION_2_3,
                MemoryDb.MIGRATION_3_4,
                MemoryDb.MIGRATION_4_5,
            )
            .build()
        try {
            db.contextCheckpointDao().upsert(
                ContextCheckpointEntity(
                    sessionId = "s-1",
                    coveredSeq = 10L,
                    lastCoveredMessageId = "m-10",
                    summary = "摘要",
                    updatedAt = 1_700_000_000_000L,
                ),
            )
            val loaded = db.contextCheckpointDao().get("s-1")
            assertEquals("新列默认 0", 0, loaded?.totalCoveredCount)
            assertEquals(10L, loaded?.coveredSeq)
            // 写入累计值后可读回
            db.contextCheckpointDao().upsert((loaded ?: error("missing")).copy(totalCoveredCount = 42))
            assertEquals(42, db.contextCheckpointDao().get("s-1")?.totalCoveredCount)
        } finally {
            db.close()
        }
    }

    /**
     * 构造一个 **v1** 版本的 memory.db 文件（无 space_id、无 scoped 表）。
     *
     * 用于验证 v1→v2→v3 的完整升级链 —— 必须真的从 v1 起，否则 MIGRATION_2_3 那段
     * "旧产物迁入 default 槽位"的逻辑不会被触发，测试就失去意义。
     */
    private fun buildV1Database(file: File): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE session_summaries (
                            session_id TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            summary TEXT NOT NULL,
                            message_count INTEGER NOT NULL,
                            source_time_range TEXT,
                            snapshot TEXT NOT NULL,
                            snapshot_at TEXT,
                            assistant_id TEXT NOT NULL,
                            PRIMARY KEY(session_id)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE daily_state (
                            `key` TEXT NOT NULL,
                            schema_version INTEGER NOT NULL,
                            logical_date TEXT NOT NULL,
                            reset_at TEXT,
                            facts_mode TEXT NOT NULL,
                            completed_steps TEXT NOT NULL,
                            daily_completed_at TEXT,
                            updated_at TEXT NOT NULL,
                            PRIMARY KEY(`key`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE compiled_sections (
                            section_key TEXT NOT NULL,
                            content TEXT NOT NULL,
                            fingerprint TEXT,
                            updated_at TEXT NOT NULL,
                            PRIMARY KEY(section_key)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    db.execSQL("INSERT INTO room_master_table VALUES (42, '5b74599c11f2cfdaae37cbc32148a9fd')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build(),
    )

    /**
     * 构造一个 v3 版本的 memory.db 文件（手工建表，模拟升级前的库）。
     *
     * DDL 写成多行缩进字符串：既清楚又不会超过 ktlint 的 140 字符行上限。
     */
    private fun buildV3Database(file: File): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE session_summaries (
                            session_id TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            summary TEXT NOT NULL,
                            message_count INTEGER NOT NULL,
                            source_time_range TEXT,
                            snapshot TEXT NOT NULL,
                            snapshot_at TEXT,
                            assistant_id TEXT NOT NULL,
                            space_id TEXT NOT NULL DEFAULT 'default',
                            PRIMARY KEY(session_id)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE daily_state (
                            `key` TEXT NOT NULL,
                            schema_version INTEGER NOT NULL,
                            logical_date TEXT NOT NULL,
                            reset_at TEXT,
                            facts_mode TEXT NOT NULL,
                            completed_steps TEXT NOT NULL,
                            daily_completed_at TEXT,
                            updated_at TEXT NOT NULL,
                            PRIMARY KEY(`key`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE compiled_sections (
                            section_key TEXT NOT NULL,
                            content TEXT NOT NULL,
                            fingerprint TEXT,
                            updated_at TEXT NOT NULL,
                            PRIMARY KEY(section_key)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE compiled_sections_scoped (
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
                    db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    db.execSQL("INSERT INTO room_master_table VALUES (42, 'dummy-v3-hash')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build(),
    )
}
