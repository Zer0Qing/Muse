package io.zer0.muse.data.card

import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.backup.BackupBinaryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 卡片数据分片存储。
 *
 * 背景：旧实现是单文件 `card_data.json`，每次 `put`/`remove` 都把整张表（最多 50 条、
 * 单条最多 64k 字符）重新序列化 + 原子写 + fsync。模型连续 `update_card_data` 时，
 * 每次都在重写别的卡片 —— 单次落盘量可到数 MB。分片后单次写入量只与这张卡有关。
 *
 * 这里锁四件事：只写自己那片、删卡片删分片、编解码往返、**分片目录必须在备份白名单内**
 * （否则分片之后卡片数据就不在备份范围了，恢复后 show_card 内容全丢）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CardDataStoreShardTest {

    private val filesDir: File get() = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
    private val shardDir: File get() = File(filesDir, "card_data")

    private fun shards(): List<File> = shardDir.listFiles().orEmpty().filter { it.isFile }

    private fun newStore(): CardDataStore = CardDataStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `card removed during restore_is_not_resurrected`() {
        // P28 把恢复搬到后台线程后引入的竞态：恢复线程手里有磁盘快照，
        // 若这期间用户删了一张卡，直接 restored + current 会把它复活。
        // 用 200 条旧数据把迁移窗口拉长，再立刻删掉其中一条。
        val legacy = File(filesDir, "card_data.json")
        legacy.parentFile?.mkdirs()
        val entries = (1..200).joinToString(separator = ",") { i ->
            "\"c-$i\":\"{\\\"v\\\":$i}\""
        }
        legacy.writeText("{" + entries + "}")
        shardDir.deleteRecursively()

        val store = newStore()
        store.remove("c-7") // 恢复还没完成就删

        val deadline = System.currentTimeMillis() + 10_000
        while (store.get("c-1") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertEquals("恢复应完成", """{"v":1}""", store.get("c-1"))
        assertNull("恢复窗口内被删的卡片不得复活", store.get("c-7"))
        assertTrue("其它卡片照常恢复", store.get("c-200") != null)
    }

    @Test
    fun `legacy_file_is_migrated_in_background`() {
        // P28：恢复/迁移改到后台线程（设备实测 50 张卡迁移 216ms，原先占主线程）。
        // 这里锁定三件事：后台确实迁了、旧文件改名 .migrated、分片落盘。
        val legacy = File(filesDir, "card_data.json")
        legacy.parentFile?.mkdirs()
        legacy.writeText("""{"legacy-1":"{\"v\":1}","legacy-2":"{\"v\":2}"}""")
        shardDir.deleteRecursively()

        val store = newStore()
        val deadline = System.currentTimeMillis() + 5_000
        while (store.get("legacy-1") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        assertEquals("""{"v":1}""", store.get("legacy-1"))
        assertEquals("""{"v":2}""", store.get("legacy-2"))
        assertTrue("旧文件应改名为 .migrated", File(filesDir, "card_data.json.migrated").exists())
        assertEquals("两条旧记录应各写一个分片", 2, shards().size)
    }

    @Test
    fun `put_writes_only_the_touched_card`() {
        val store = newStore()
        store.put("card-a", """{"v":1}""")
        val fileA = File(shardDir, CardDataFiles.shardName("card-a"))
        assertTrue("分片必须落盘", fileA.exists())
        val bytesA = fileA.length()

        store.put("card-b", """{"v":2}""")
        assertEquals("写 B 不得改动 A 的分片", bytesA, fileA.length())
        assertTrue("两张卡片应各自一个分片", shards().size >= 2)
    }

    @Test
    fun `get_reads_back_in_memory_value`() {
        val store = newStore()
        store.put("card-c", """{"title":"卡片"}""")
        assertEquals("""{"title":"卡片"}""", store.get("card-c"))
        assertNull(store.get("card-missing"))
    }

    @Test
    fun `remove_deletes_the_shard`() {
        val store = newStore()
        store.put("card-d", """{"v":4}""")
        val file = File(shardDir, CardDataFiles.shardName("card-d"))
        assertTrue(file.exists())

        store.remove("card-d")
        assertTrue("remove 后分片应被删除", !file.exists())
        assertNull(store.get("card-d"))
    }

    @Test
    fun `shard_content_round_trips_through_envelope`() {
        val store = newStore()
        store.put("card-e", """{"nested":{"a":[1,2,3]}}""")
        val text = File(shardDir, CardDataFiles.shardName("card-e")).readText()
        val decoded = CardDataFiles.decode(text)
        assertEquals("card-e", decoded?.first)
        assertEquals("""{"nested":{"a":[1,2,3]}}""", decoded?.second)
    }

    @Test
    fun `shard_name_is_stable_and_path_safe`() {
        val name = CardDataFiles.shardName("1780000000000/../evil")
        assertEquals(name, CardDataFiles.shardName("1780000000000/../evil"))
        assertTrue("不得含路径分隔符", !name.contains("/") && !name.contains("\\"))
        assertEquals(64 + 5, name.length)
    }

    @Test
    fun `corrupt_shard_is_skipped`() {
        assertNull(CardDataFiles.decode("{not json"))
        assertNull(CardDataFiles.decode(""))
    }

    @Test
    fun `card_data_directory_is_in_backup_whitelist`() {
        assertTrue(
            "分片目录必须在目录型备份白名单内，否则恢复后卡片数据全丢",
            "card_data/" in BackupBinaryFiles.allowedPrefixes,
        )
        assertTrue(
            "旧单文件仍要留在文件白名单里（迁移源 + 老备份兼容）",
            "card_data.json" in io.zer0.muse.backup.BackupService.backupFileStorePaths,
        )
    }
}
