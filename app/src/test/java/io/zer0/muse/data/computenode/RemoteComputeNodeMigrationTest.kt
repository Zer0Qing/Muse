package io.zer0.muse.data.computenode

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.MuseDbMigrations
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v2.7.x: MuseDb 138 → 139 迁移契约（remote_compute_nodes）。
 *
 * 两条证据互相独立：
 *  1. 直接执行迁移 DDL，逐列核对类型、NOT NULL 与默认值 —— 列漂移会让真机
 *     Room 校验报 "Migration didn't properly handle: remote_compute_nodes"；
 *  2. 用 138 版 schema JSON 建库后交给 Room 打开，核对 room_master_table 的
 *     identity_hash 等于 139 快照 —— 证明 Room 真的做过实体级校验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteComputeNodeMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** 与 [MuseDbMigrations.MIGRATION_138_139] 完全一致的 DDL 通过真实迁移执行。 */
    @Test
    fun migration138To139_createsRemoteComputeNodesTable() {
        val dbName = "migration_138_139_remote_nodes.db"
        deleteDatabaseFiles(dbName)
        val helper = openHelper(dbName, version = 138) { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sessions (" +
                    "id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL DEFAULT '')",
            )
            db.execSQL("INSERT INTO sessions (id, title) VALUES ('legacy-session', '迁移前')")
        }
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO sessions (id, title) VALUES ('neighbor-session', '邻居')")

            // 真实迁移对象执行一次；再执行一次验证幂等。
            MuseDbMigrations.MIGRATION_138_139.migrate(db)
            MuseDbMigrations.MIGRATION_138_139.migrate(db)

            val columns = columnsOf(db, "remote_compute_nodes")
            assertEquals(
                "列集合与顺序必须与 RemoteComputeNodeEntity 完全一致",
                listOf(
                    "nodeId",
                    "name",
                    "connectionMode",
                    "host",
                    "sshPort",
                    "daemonPort",
                    "username",
                    "authType",
                    "encryptedCredential",
                    "encryptedNodeToken",
                    "tlsFingerprint",
                    "osInfoJson",
                    "installedPacksJson",
                    "status",
                    "isDefault",
                    "lastSeenAt",
                    "createdAt",
                    "updatedAt",
                ),
                columns.map { it.name },
            )
            columns.forEach { column ->
                assertEquals(
                    "列 ${column.name} 的类型/可空性必须与实体一致",
                    expectedTypeAndNullability(column.name),
                    "${column.type}|notnull=${column.notNull}",
                )
            }
            assertEquals(
                "默认值必须与实体 @ColumnInfo/defaultValue 逐项一致",
                mapOf(
                    "name" to "''",
                    "connectionMode" to "'SSH_TUNNEL'",
                    "host" to "''",
                    "sshPort" to "22",
                    "daemonPort" to "18765",
                    "username" to "''",
                    "authType" to "'PASSWORD'",
                    "encryptedCredential" to "''",
                    "encryptedNodeToken" to "''",
                    "tlsFingerprint" to "''",
                    "osInfoJson" to "'{}'",
                    "installedPacksJson" to "'[]'",
                    "status" to "'UNINITIALIZED'",
                    "isDefault" to "0",
                    "lastSeenAt" to "0",
                    "createdAt" to "0",
                    "updatedAt" to "0",
                ),
                columns.filter { it.defaultValue != null }.associate { it.name to it.defaultValue },
            )

            // 只写 nodeId 时全部默认值生效，确保旧代码/空行也能读出可解释的初始状态。
            db.execSQL("INSERT INTO remote_compute_nodes (nodeId) VALUES ('node-minimal')")
            db.query(
                "SELECT name, connectionMode, host, sshPort, daemonPort, username, authType, " +
                    "encryptedCredential, encryptedNodeToken, tlsFingerprint, osInfoJson, " +
                    "installedPacksJson, status, isDefault, lastSeenAt, createdAt, updatedAt " +
                    "FROM remote_compute_nodes WHERE nodeId = 'node-minimal'",
            ).use { cursor ->
                assertTrue("插入的最小行必须可读回", cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals("SSH_TUNNEL", cursor.getString(1))
                assertEquals("", cursor.getString(2))
                assertEquals(22, cursor.getInt(3))
                assertEquals(18765, cursor.getInt(4))
                assertEquals("", cursor.getString(5))
                assertEquals("PASSWORD", cursor.getString(6))
                assertEquals("", cursor.getString(7))
                assertEquals("", cursor.getString(8))
                assertEquals("", cursor.getString(9))
                assertEquals("{}", cursor.getString(10))
                assertEquals("[]", cursor.getString(11))
                assertEquals("UNINITIALIZED", cursor.getString(12))
                assertEquals(0, cursor.getInt(13))
                assertEquals(0L, cursor.getLong(14))
                assertEquals(0L, cursor.getLong(15))
                assertEquals(0L, cursor.getLong(16))
            }

            // 完整行按列写回，防止列名与实体字段错位。
            db.execSQL(
                "INSERT INTO remote_compute_nodes (" +
                    "nodeId, name, connectionMode, host, sshPort, daemonPort, username, authType, " +
                    "encryptedCredential, encryptedNodeToken, tlsFingerprint, osInfoJson, " +
                    "installedPacksJson, status, isDefault, lastSeenAt, createdAt, updatedAt" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "node-full", "测试机", "DIRECT_TLS", "127.0.0.1", 2222, 18765, "muse",
                    "PRIVATE_KEY", "enc_v1:credential", "enc_v1:token", "SHA256:abc",
                    "{\"os\":\"ubuntu-24.04\"}", "[\"core\",\"browser\"]", "ONLINE",
                    1, 1000L, 900L, 1100L,
                ),
            )
            db.query(
                "SELECT name, connectionMode, host, sshPort, daemonPort, username, authType, " +
                    "encryptedCredential, encryptedNodeToken, tlsFingerprint, osInfoJson, " +
                    "installedPacksJson, status, isDefault, lastSeenAt, createdAt, updatedAt " +
                    "FROM remote_compute_nodes WHERE nodeId = 'node-full'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("测试机", cursor.getString(0))
                assertEquals("DIRECT_TLS", cursor.getString(1))
                assertEquals("127.0.0.1", cursor.getString(2))
                assertEquals(2222, cursor.getInt(3))
                assertEquals(18765, cursor.getInt(4))
                assertEquals("muse", cursor.getString(5))
                assertEquals("PRIVATE_KEY", cursor.getString(6))
                assertEquals("enc_v1:credential", cursor.getString(7))
                assertEquals("enc_v1:token", cursor.getString(8))
                assertEquals("SHA256:abc", cursor.getString(9))
                assertEquals("{\"os\":\"ubuntu-24.04\"}", cursor.getString(10))
                assertEquals("[\"core\",\"browser\"]", cursor.getString(11))
                assertEquals("ONLINE", cursor.getString(12))
                assertEquals(1, cursor.getInt(13))
                assertEquals(1000L, cursor.getLong(14))
                assertEquals(900L, cursor.getLong(15))
                assertEquals(1100L, cursor.getLong(16))
            }

            // 邻居表与既有数据必须原样保留（纯新增表迁移不触碰别的表）。
            db.query("SELECT COUNT(*) FROM sessions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("既有会话行不得丢失", 2, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM remote_compute_nodes").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("重复执行迁移不得重复建表或清空数据", 2, cursor.getInt(0))
            }
        } finally {
            helper.close()
            deleteDatabaseFiles(dbName)
        }
    }

    /** Room 实际打开最新版（当前 140），核对 identity_hash 与最新实体快照一致。 */
    @Test
    fun roomValidatesMigratedRemoteComputeNodeSchema() {
        val dbName = "migration_138_139_room_validate.db"
        deleteDatabaseFiles(dbName)
        val dbFile = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        createSchemaAtVersion(138, dbFile.absolutePath)

        val room = Room.databaseBuilder(context.applicationContext, MuseDb::class.java, dbName)
            .addMigrations(MuseDbMigrations.MIGRATION_138_139, MuseDbMigrations.MIGRATION_139_140)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals("Room 必须把库升到最新版本", MuseDb.LATEST_SCHEMA_VERSION, db.version)
            val expected = loadSchema(MuseDb.LATEST_SCHEMA_VERSION).getJSONObject("database").getString("identityHash")
            db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
                assertTrue("Room 必须写入迁移后的 identity_hash", cursor.moveToFirst())
                assertEquals(
                    "identity_hash 必须等于最新快照（证明 Room 已校验并通过全部实体）",
                    expected,
                    cursor.getString(0),
                )
            }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='remote_compute_nodes'").use { cursor ->
                assertTrue("Room 打开后 remote_compute_nodes 必须存在", cursor.moveToFirst())
                assertNotNull(cursor.getString(0))
            }
        } finally {
            room.close()
            deleteDatabaseFiles(dbName)
        }
    }

    /** 枚举与数据库锁值保持一致，避免 UI/服务层用错字符串。 */
    @Test
    fun wireValuesMatchLockedSchema() {
        assertEquals("SSH_TUNNEL", ComputeNodeMode.SSH_TUNNEL.wireValue)
        assertEquals("DIRECT_TLS", ComputeNodeMode.DIRECT_TLS.wireValue)
        assertNull(ComputeNodeMode.fromWire("UNKNOWN"))
        assertEquals(
            setOf("UNINITIALIZED", "BOOTSTRAPPING", "ONLINE", "OFFLINE", "ERROR"),
            ComputeNodeStatus.entries.map { it.wireValue }.toSet(),
        )
        assertNull(ComputeNodeStatus.fromWire("UNKNOWN"))
    }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Int,
        val defaultValue: String?,
    )

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<ColumnInfo> {
        val out = mutableListOf<ColumnInfo>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                out += ColumnInfo(
                    name = cursor.getString(nameIndex),
                    type = cursor.getString(typeIndex),
                    notNull = cursor.getInt(notNullIndex),
                    defaultValue = cursor.getString(defaultIndex),
                )
            }
        }
        return out
    }

    private fun expectedTypeAndNullability(column: String): String = when (column) {
        "sshPort", "daemonPort", "isDefault", "lastSeenAt", "createdAt", "updatedAt" -> "INTEGER|notnull=1"
        else -> "TEXT|notnull=1"
    }

    private fun openHelper(
        dbName: String,
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit,
    ): SupportSQLiteOpenHelper {
        val dbFile = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
    }

    /** 用 138 快照还原全部实体表与 setupQueries，供 Room 迁移校验使用。 */
    private fun createSchemaAtVersion(version: Int, dbPath: String) {
        val databaseJson = loadSchema(version).getJSONObject("database")
        val entities = databaseJson.getJSONArray("entities")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbPath)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            for (i in 0 until entities.length()) {
                                val entity = entities.getJSONObject(i)
                                val tableName = entity.getString("tableName")
                                db.execSQL(
                                    entity.getString("createSql").replace("\${TABLE_NAME}", tableName),
                                )
                                if (entity.has("indices")) {
                                    val indices = entity.getJSONArray("indices")
                                    for (j in 0 until indices.length()) {
                                        db.execSQL(
                                            indices.getJSONObject(j).getString("createSql")
                                                .replace("\${TABLE_NAME}", tableName),
                                        )
                                    }
                                }
                            }
                            val setupQueries = databaseJson.getJSONArray("setupQueries")
                            for (i in 0 until setupQueries.length()) {
                                db.execSQL(setupQueries.getString(i))
                            }
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase
        helper.close()
    }

    private fun loadSchema(version: Int): JSONObject = JSONObject(
        File("schemas/io.zer0.muse.data.session.MuseDb/$version.json").readText(),
    )

    private fun deleteDatabaseFiles(dbName: String) {
        val dbFile = context.getDatabasePath(dbName)
        listOf(dbFile, File("${dbFile.absolutePath}-wal"), File("${dbFile.absolutePath}-shm"))
            .forEach { if (it.exists()) it.delete() }
    }
}
