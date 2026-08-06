package io.zer0.memory.fact

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-DB-03: 早期 v1/v2 facts 数据库归档重建测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class FactDbLegacyResetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `legacy v1 database is archived and flagged for ui hint`() {
        val name = "facts_test_v1.db"
        createDatabase(name, version = 1)

        FactDb.create(context, name)

        assertFalse(context.getDatabasePath(name).exists())
        assertTrue(context.getDatabasePath("$name.bak").exists())
        assertTrue(MemoryLegacyReset.consume(context))
        cleanup(name)
    }

    @Test
    fun `legacy v2 database is archived and flagged for ui hint`() {
        val name = "facts_test_v2.db"
        createDatabase(name, version = 2)

        FactDb.create(context, name)

        assertFalse(context.getDatabasePath(name).exists())
        assertTrue(context.getDatabasePath("$name.bak").exists())
        assertTrue(MemoryLegacyReset.consume(context))
        cleanup(name)
    }

    @Test
    fun `current version database is not archived`() {
        val name = "facts_test_v11.db"
        createDatabase(name, version = 11)

        FactDb.create(context, name)

        assertTrue(context.getDatabasePath(name).exists())
        assertFalse(context.getDatabasePath("$name.bak").exists())
        assertFalse(MemoryLegacyReset.consume(context))
        cleanup(name)
    }

    private fun createDatabase(name: String, version: Int) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.version = version
        } finally {
            db.close()
        }
    }

    private fun cleanup(name: String) {
        listOf(name, "$name.bak", "$name-wal", "$name-shm").forEach {
            runCatching { context.getDatabasePath(it).delete() }
        }
    }
}
