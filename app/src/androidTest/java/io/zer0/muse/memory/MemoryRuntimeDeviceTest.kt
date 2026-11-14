package io.zer0.muse.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.memory.fact.FactDb
import io.zer0.memory.summary.MemoryDb
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 设备上打开当前 schema 的 MemoryDb/FactDb，验证 v8/v21 迁移链和生产 Room 配置可承载。
 */
@RunWith(AndroidJUnit4::class)
class MemoryRuntimeDeviceTest {

    @Test
    fun memoryDbOpensWithCurrentSchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, MemoryDb::class.java, "memory_runtime_device_test.db")
            .addMigrations(
                MemoryDb.MIGRATION_1_2,
                MemoryDb.MIGRATION_2_3,
                MemoryDb.MIGRATION_3_4,
                MemoryDb.MIGRATION_4_5,
                MemoryDb.MIGRATION_5_6,
                MemoryDb.MIGRATION_6_7,
                MemoryDb.MIGRATION_7_8,
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertNotNull(db.dailyStateDao())
            assertNotNull(db.sessionSummaryDao())
        } finally {
            db.close()
            context.deleteDatabase("memory_runtime_device_test.db")
        }
    }

    @Test
    fun factDbOpensWithCurrentSchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, FactDb::class.java, "facts_runtime_device_test.db")
            .addMigrations(
                FactDb.MIGRATION_3_4,
                FactDb.MIGRATION_4_5,
                FactDb.MIGRATION_5_6,
                FactDb.MIGRATION_6_7,
                FactDb.MIGRATION_7_8,
                FactDb.MIGRATION_8_9,
                FactDb.MIGRATION_9_10,
                FactDb.MIGRATION_10_11,
                FactDb.MIGRATION_11_12,
                FactDb.MIGRATION_12_13,
                FactDb.MIGRATION_13_14,
                FactDb.MIGRATION_14_15,
                FactDb.MIGRATION_15_16,
                FactDb.MIGRATION_16_17,
                FactDb.MIGRATION_17_18,
                FactDb.MIGRATION_18_19,
                FactDb.MIGRATION_19_20,
                FactDb.MIGRATION_20_21,
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertNotNull(db.factDao())
            assertNotNull(db.factProjectionDao())
        } finally {
            db.close()
            context.deleteDatabase("facts_runtime_device_test.db")
        }
    }
}
