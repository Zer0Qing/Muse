package io.zer0.muse.data.notification

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 通知历史的有界存储（对比清单 P2-02「通知历史」）。
 *
 * 三条约束都要守住：**条数上限**、**保留期限**、**坏行不影响整份历史**。
 * 最后一条尤其重要 —— 一次写到一半的断电不该让用户的通知历史全没了。
 */
class NotificationHistoryStoreTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var store: NotificationHistoryStore

    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("muse-notif").toFile()
        file = File(dir, NotificationHistoryStore.FILE_NAME)
        store = NotificationHistoryStore(file)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun entry(at: Long, title: String = "标题", text: String = "正文") =
        NotificationHistoryStore.Entry(atMillis = at, packageName = "com.example.app", title = title, text = text)

    @Test
    fun `the newest entry comes first`() {
        store.append(entry(now - 1000, title = "旧"))
        store.append(entry(now, title = "新"))

        assertEquals(listOf("新", "旧"), store.recent().map { it.title })
    }

    @Test
    fun `entries beyond the cap are dropped`() {
        repeat(NotificationHistoryStore.MAX_ENTRIES + 20) { index ->
            store.append(entry(now - index, title = "n$index"))
        }

        val kept = store.prune(now)

        assertEquals(NotificationHistoryStore.MAX_ENTRIES, kept)
        assertEquals(NotificationHistoryStore.MAX_ENTRIES, store.recent(1_000).size)
    }

    @Test
    fun `entries past the retention window are pruned`() {
        store.append(entry(now - 10 * dayMs, title = "很旧"))
        store.append(entry(now, title = "刚收到"))

        store.prune(now, retentionDays = 3)

        assertEquals(listOf("刚收到"), store.recent().map { it.title })
    }

    @Test
    fun `a corrupt line does not destroy the whole history`() {
        store.append(entry(now, title = "正常"))
        file.appendText("{ 这不是合法 JSON\n")
        store.append(entry(now + 1, title = "也正常"))

        val titles = store.recent().map { it.title }

        assertEquals(listOf("也正常", "正常"), titles)
    }

    @Test
    fun `long titles and texts are truncated before writing`() {
        store.append(entry(now, title = "标".repeat(500), text = "文".repeat(2_000)))

        val stored = store.recent().single()

        assertEquals(NotificationHistoryStore.MAX_TITLE_CHARS, stored.title.length)
        assertEquals(NotificationHistoryStore.MAX_TEXT_CHARS, stored.text.length)
    }

    @Test
    fun `reading a missing file yields nothing instead of throwing`() {
        val missing = NotificationHistoryStore(File(dir, "nope.jsonl"))

        assertTrue(missing.recent().isEmpty())
        assertEquals(0, missing.prune(now))
    }

    @Test
    fun `pruning keeps the file valid for the next read`() {
        repeat(5) { index -> store.append(entry(now - index * dayMs, title = "d$index")) }

        store.prune(now, retentionDays = 2)

        // 清理后还能正常读；保留边界是**闭区间**（恰好两天的仍保留）
        assertEquals(listOf("d0", "d1", "d2"), store.recent().map { it.title })
    }
}
