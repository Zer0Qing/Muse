package io.zer0.muse.data.relationship

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v2.6.7（P1-1）: 113 → 114 迁移契约测试。
 *
 * 只验证"新表被正确创建且列定义与实体一致"这条最小不变量。
 * 不经 Room 打开数据库 —— Room 会对全部实体表做完整结构校验，
 * 而这里只需要一张最小骨架库来执行迁移 SQL。完整跨版本升级链由
 * MuseDbMigrationTest 的反射矩阵覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantRelationshipMigrationTest {

    @Test
    fun migrate113To114_createsRelationshipTable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "muse_relationship_migration.db"
        val dbFile = context.getDatabasePath(dbName).apply {
            if (exists()) delete()
            parentFile?.mkdirs()
        }

        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(113) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                // 最小骨架：只要能让 113→114 迁移执行即可
                                db.execSQL("CREATE TABLE IF NOT EXISTS sessions (id TEXT NOT NULL PRIMARY KEY)")
                            }

                            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                        },
                    )
                    .build(),
            )

        helper.writableDatabase.use { db ->
            // 直接执行迁移，不经 Room 的实体校验
            MuseDb.MIGRATION_113_114.migrate(db)

            val columns = mutableMapOf<String, String?>()
            db.query("PRAGMA table_info(assistant_relationship)").use { cursor ->
                while (cursor.moveToNext()) {
                    columns[cursor.getString(1)] = cursor.getString(4)
                }
            }

            assertTrue(
                "迁移后应存在 assistant_relationship 表\n实际列: $columns",
                columns.isNotEmpty(),
            )
            listOf(
                "assistantId",
                "relationLabel",
                "affinity",
                "lastInteractionAt",
                "proactiveLevel",
                "proactiveEnabled",
                "dailyProactiveLimit",
                "createdAt",
                "updatedAt",
            ).forEach { expected ->
                assertTrue("缺少列 $expected\n实际列: $columns", columns.containsKey(expected))
            }
            // 关键默认值必须与实体一致，否则 Room 校验会报结构不匹配
            assertEquals("normal", columns["proactiveLevel"]?.trim('\'', '"'))
            assertEquals("1", columns["proactiveEnabled"])
            assertEquals("0", columns["affinity"])

            // 幂等：重复执行不应报错（迁移可能因中断被重跑）
            MuseDb.MIGRATION_113_114.migrate(db)
        }

        helper.close()
        if (dbFile.exists()) dbFile.delete()
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
    }
}
