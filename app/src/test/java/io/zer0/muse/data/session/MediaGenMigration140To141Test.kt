package io.zer0.muse.data.session

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Migration regression for MuseDb 140 -> 141 adding `media_gen_tasks` and `media_gen_assets`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaGenMigration140To141Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun openAt140(name: String): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(140) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE `sessions` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        ).writableDatabase
    }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            while (c.moveToNext()) names += c.getString(0)
        }
        return names
    }

    private fun indexNames(db: SupportSQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index'").use { c ->
            while (c.moveToNext()) names += c.getString(0)
        }
        return names
    }

    @Test
    fun `migration 140 to 141 creates media studio tables and indices idempotently`() {
        val db = openAt140("test_mig_140_141.db")
        try {
            MuseDbMigrations.MIGRATION_140_141.migrate(db)
            MuseDbMigrations.MIGRATION_140_141.migrate(db)

            val tables = tableNames(db)
            assertTrue("media_gen_tasks" in tables)
            assertTrue("media_gen_assets" in tables)

            val indices = indexNames(db)
            assertTrue("index_media_gen_tasks_kind" in indices)
            assertTrue("index_media_gen_tasks_status" in indices)
            assertTrue("index_media_gen_tasks_createdAt" in indices)
            assertTrue("index_media_gen_assets_taskId" in indices)
            assertTrue("index_media_gen_assets_kind" in indices)
            assertTrue("index_media_gen_assets_createdAt" in indices)

            db.execSQL(
                "INSERT INTO media_gen_tasks VALUES ('t1','IMAGE','SUCCEEDED','cat','p1','m1','{}',NULL,0,NULL,'studio',100,100)",
            )
            db.execSQL(
                "INSERT INTO media_gen_assets VALUES ('a1','t1','IMAGE','/tmp/a.png',NULL,NULL,'image/png',512,512,0,'cat','m1','p1','{}',0,100)",
            )
            db.query("SELECT COUNT(*) FROM media_gen_assets WHERE taskId='t1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        } finally {
            db.close()
            context.deleteDatabase("test_mig_140_141.db")
        }
    }
}
