package io.zer0.muse.backup

import io.zer0.muse.data.card.CardEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.6.8: `card_entities` 备份接线守护。
 *
 * 背景：卡片停靠后即脱离消息流，消息里只剩一条占位；不备份这张表，换机后
 * 所有停靠卡永久丢失，且无法从消息重建。本测试钉住四件事：
 *  1. 手写列清单不能漏列（实体加列时最危险的静默丢数据点）；
 *  2. 线格式 round-trip 不丢字段，含 null 与停靠槽位；
 *  3. 导出顺序稳定（停靠卡按槽位、未停靠卡按创建时间）；
 *  4. 源码层面四处接线都在（导出快照 / 恢复清空 / 恢复写入 / NDJSON 映射）。
 *
 * 第 4 条用「源码契约」模式，与仓库既有 BackupRestoreTodoRelationshipTest /
 * BackupNdJsonRestoreContractTest 同型：buildBackup / applyBackupInternal 都是
 * private 且依赖三套真实 Room 库，单元层无法直接调用。
 */
class BackupCardEntitiesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun card(id: String, dockSlot: Int? = null, createdAt: Long = 1_000L) = CardEntity(
        id = id,
        legacyCardId = "c_$id",
        originSessionId = "s1",
        originMessageId = "m1",
        title = "标题 $id",
        code = "<div>$id</div>",
        isSvg = false,
        dataJson = """{"v":1}""",
        dockSlot = dockSlot,
        dockedAt = dockSlot?.let { 2_000L },
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun source(): String = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()

    // ── 1. 手写列清单守护 ──

    /**
     * 备份读写走的是手写列清单（DAO 没有全量/清空方法），实体加列时若忘了同步
     * `CardEntityTable.COLUMNS`，新列会在备份里静默丢失。这里用反射把实体属性名与
     * 列清单对齐：少一列即红，多一列（拼错）也红。
     */
    @Test
    fun `card entity columns match the room entity properties`() {
        val entityProps = instanceFields(CardEntity::class.java)
        val declaredColumns = CardEntityTable.COLUMNS.toSet()

        val missing = entityProps - declaredColumns
        val extra = declaredColumns - entityProps
        assertEquals(
            "card_entities 备份列清单漏了实体的字段(新列会在备份里静默丢失):\n" + missing.joinToString("\n"),
            emptySet<String>(),
            missing,
        )
        assertEquals(
            "备份列清单里有实体不存在的列(拼错或实体已改名):\n" + extra.joinToString("\n"),
            emptySet<String>(),
            extra,
        )
    }

    @Test
    fun `line format mirrors every entity field`() {
        val entityProps = instanceFields(CardEntity::class.java)
        val recordProps = instanceFields(CardEntityRecord::class.java)

        assertEquals(
            "线格式与实体字段不对应,新增字段未进备份",
            entityProps,
            recordProps,
        )
        assertEquals(
            "列清单长度必须与线格式一致(手写绑定语句按顺序排位)",
            CardEntityTable.COLUMNS.size,
            recordProps.size,
        )
    }

    /**
     * 绑定语句 [bindCard] 把列下标硬编码在 1..12，所以列清单的**顺序**本身就是契约：
     * 改顺序不改绑定语句 = 把 title 写进 code 列，而且不会报任何错。
     * 这里把顺序字面量钉住。
     */
    @Test
    fun `column order is pinned to the hand written binding`() {
        assertEquals(
            "列顺序是手写绑定语句的契约，改动必须同时改 bindCard",
            listOf(
                "id",
                "legacyCardId",
                "originSessionId",
                "originMessageId",
                "title",
                "code",
                "isSvg",
                "dataJson",
                "dockSlot",
                "dockedAt",
                "createdAt",
                "updatedAt",
            ),
            CardEntityTable.COLUMNS,
        )
    }

    /**
     * data class 的实例字段名。排除编译期生成的东西：kotlinx.serialization 的
     * 静态 `Companion` 字段，以及 `$` 开头的合成内部类字段。
     */
    private fun instanceFields(type: Class<*>): Set<String> = type.declaredFields
        .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.name }
        .filterNot { it.contains('$') }
        .toSet()

    // ── 2. round-trip ──

    @Test
    fun `docked and inline cards round trip without data loss`() {
        val docked = card("docked-a", dockSlot = 0, createdAt = 1_000L)
        val inline = card("inline-b", dockSlot = null, createdAt = 2_000L)

        val backup = BackupService.Backup(
            exportedAt = 1L,
            sessions = emptyList(),
            messages = emptyList(),
            cardEntities = listOf(docked.toRecord(), inline.toRecord()),
        )
        val restored = json.decodeFromString(
            BackupService.Backup.serializer(),
            json.encodeToString(BackupService.Backup.serializer(), backup),
        )

        assertEquals(2, restored.cardEntities.size)
        assertEquals("停靠态必须原样往返", docked, restored.cardEntities[0].toEntity())
        assertEquals("未停靠卡同样必须保留", inline, restored.cardEntities[1].toEntity())
        assertNull("未停靠卡的 dockSlot 必须是 null 而不是 0", restored.cardEntities[1].dockSlot)
    }

    @Test
    fun `null optional fields survive round trip`() {
        val bare = CardEntity(id = "bare", createdAt = 0L, updatedAt = 0L)
        val restored = json.decodeFromString(
            BackupService.Backup.serializer(),
            json.encodeToString(
                BackupService.Backup.serializer(),
                BackupService.Backup(
                    exportedAt = 1L,
                    sessions = emptyList(),
                    messages = emptyList(),
                    cardEntities = listOf(bare.toRecord()),
                ),
            ),
        ).cardEntities.single()

        assertEquals(bare, restored.toEntity())
        assertNull(restored.legacyCardId)
        assertNull(restored.originSessionId)
        assertNull(restored.originMessageId)
        assertNull(restored.dataJson)
        assertNull(restored.dockSlot)
        assertNull(restored.dockedAt)
    }

    @Test
    fun `old backup without cardEntities decodes to empty list`() {
        val oldStyle = """{"version":4,"exportedAt":1,"sessions":[],"messages":[]}"""
        val restored = json.decodeFromString(BackupService.Backup.serializer(), oldStyle)

        assertEquals(emptyList<CardEntityRecord>(), restored.cardEntities)
    }

    // ── 3. 顺序 ──

    @Test
    fun `dock order puts docked cards first by slot then inline by createdAt`() {
        val records = listOf(
            card("inline-late", dockSlot = null, createdAt = 9_000L).toRecord(),
            card("slot-2", dockSlot = 2, createdAt = 1_000L).toRecord(),
            card("slot-0", dockSlot = 0, createdAt = 5_000L).toRecord(),
            card("inline-early", dockSlot = null, createdAt = 100L).toRecord(),
            card("slot-1", dockSlot = 1, createdAt = 3_000L).toRecord(),
        )

        assertEquals(
            "停靠卡按槽位升序在前，未停靠卡按创建时间在后",
            listOf("slot-0", "slot-1", "slot-2", "inline-early", "inline-late"),
            records.inDockOrder().map { it.id },
        )
    }

    @Test
    fun `dock order is deterministic for identical timestamps`() {
        val records = listOf(
            card("b", dockSlot = null, createdAt = 1_000L).toRecord(),
            card("a", dockSlot = null, createdAt = 1_000L).toRecord(),
        )

        assertEquals(
            "同一时间戳的卡用 id 兜底排序，两次导出结果必须一致",
            listOf("a", "b"),
            records.inDockOrder().map { it.id },
        )
        assertEquals(records.inDockOrder(), records.reversed().inDockOrder())
    }

    // ── 4. 四处接线的源码契约 ──

    @Test
    fun `buildBackup snapshots card entities`() {
        val src = source()
        assertTrue(
            "buildBackup 的 db.withTransaction 快照必须读 card_entities" +
                "(它是 recovery point 的来源，漏读会让回滚清空全部卡片)",
            src.contains("cardEntities = db.readAllCardEntities()"),
        )
        assertTrue(
            "buildBackup 必须把 cardEntities 传进 Backup(...) 构造",
            Regex("cardEntities\\s*=\\s*cardEntities").containsMatchIn(src),
        )
    }

    @Test
    fun `restore clears and writes card entities`() {
        val src = source()
        assertTrue("恢复事务里必须先清空 card_entities", src.contains("db.deleteAllCardEntities()"))
        assertTrue("恢复事务里必须写回 card_entities", src.contains("db.insertCardEntities("))
        assertTrue(
            "恢复后必须对 card_entities 做行数对账",
            src.contains("\"cardEntities\" -> db.countCardEntities()"),
        )

        val applyBackup = src.substring(
            src.indexOf("private suspend fun applyBackupInternal"),
            src.indexOf("private suspend fun pruneRestoredMessageImages"),
        )
        assertTrue("单 JSON 恢复路径也要写卡", applyBackup.contains("insertCardEntities"))
        assertTrue("单 JSON 恢复路径也要清空卡", applyBackup.contains("deleteAllCardEntities"))
    }

    @Test
    fun `ndjson exports and restores card entity records`() {
        val src = source()
        assertTrue(
            "NDJSON 导出必须写 cardEntity 行",
            src.contains("writeTypedLines(writer, \"cardEntity\""),
        )
        assertTrue(
            "NDJSON meta 必须声明 cardEntities 计数",
            src.contains("put(\"cardEntities\", cardEntities.size)"),
        )
        assertTrue(
            "type→meta 映射必须含 cardEntity(缺失会让读回校验与对账漏掉整张表)",
            src.contains("\"cardEntity\" to \"cardEntities\""),
        )
        assertTrue(
            "NDJSON 恢复分发必须有 cardEntity 分支",
            src.contains("\"cardEntity\" ->"),
        )
    }

    @Test
    fun `duplicate dock slots are rejected with a readable reason`() {
        val duplicated = listOf(card("a", dockSlot = 0), card("b", dockSlot = 0))
        val error = runCatching { requireNoDuplicateDockSlots(duplicated) }.exceptionOrNull()

        assertTrue("必须抛错", error is IllegalStateException)
        assertTrue(
            "错误信息要指出是槽位重复,而不是 SQLite 的 UNIQUE constraint failed: ${error?.message}",
            error?.message?.contains("停靠槽位重复") == true,
        )
        // 未停靠卡可以有多张，不算重复
        requireNoDuplicateDockSlots(listOf(card("a"), card("b")))
    }
}
