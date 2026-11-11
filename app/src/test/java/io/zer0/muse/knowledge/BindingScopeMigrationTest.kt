package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.storage.BindingScopeMigration
import io.zer0.muse.knowledge.storage.BindingScopeMigration.Entry
import io.zer0.muse.knowledge.storage.FolderIdResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 步骤 7 绑定迁移合同:「冲突和无法解析绑定进入报告而不是 All」。
 * Unresolved 条目调用方按 R1 失效绑定语义处理(零结果),绝不扩域。
 */
class BindingScopeMigrationTest {

    private class Dir : FolderIdResolver.FolderDirectory {
        val rows = mutableMapOf<Pair<String, String>, String>()
        override fun findById(kbId: String, path: String): String? = rows[kbId to path]
        override fun create(kbId: String, path: String): String =
            FolderIdResolver.folderIdOf(kbId, path).also { rows[kbId to path] = it }
    }

    @Test
    fun `legacy whole-library and path bindings resolve`() {
        val dir = Dir()
        val results = BindingScopeMigration.migrate(
            listOf("kb-a", "kb-a::合同", "kb-b::"),
            folderIdResolver = FolderIdResolver(dir),
        )

        assertEquals(Entry.Resolved("kb-a", "", ""), results[0]) // 无路径段 = 整库
        assertEquals("路径绑定归一到 folderId", Entry.Resolved("kb-a", FolderIdResolver.folderIdOf("kb-a", "合同"), "合同"), results[1])
        assertEquals("kb-b:: = 整库绑定", Entry.Resolved("kb-b", "", ""), results[2])
    }

    @Test
    fun `unresolvable entries go to report not to all`() {
        val results = BindingScopeMigration.migrate(
            listOf("", "::path", "kb::path::extra", "  "),
        )

        assertEquals(4, results.size)
        results.forEach {
            assertTrue(
                "无法解析条目必须进报告(Unresolved),实际=$it",
                it is Entry.Unresolved,
            )
        }
        assertEquals("空条目", (results[0] as Entry.Unresolved).reason)
        assertEquals("kbId 为空", (results[1] as Entry.Unresolved).reason)
        assertEquals("段数超过 2(既非旧格式也非 folderId 化格式)", (results[2] as Entry.Unresolved).reason)
    }

    @Test
    fun `trailing empty path segment means whole-library binding`() {
        val results = BindingScopeMigration.migrate(listOf("kb-b::"))

        assertEquals(
            "尾随空段 = 整库绑定(与 'kbId' 同义),不是格式损坏",
            listOf(Entry.Resolved("kb-b", "", "")),
            results,
        )
    }

    @Test
    fun `null resolver rejects path bindings instead of degrading to whole-library`() {
        // 回归钉(re-review Important #2):无 resolver 时目录绑定必须进报告(Unresolved),
        // 绝不降级成 folderId=""(整库语义 = 静默域扩大)。整库绑定("kbId"/"kbId::")不受影响。
        val results = BindingScopeMigration.migrate(
            listOf("kb-a", "kb-a::合同"),
            folderIdResolver = null,
        )
        assertEquals(
            "无路径段整库绑定仍解析",
            listOf(Entry.Resolved("kb-a", "", "")),
            listOf(results[0]),
        )
        assertTrue(
            "目录绑定无 resolver 必须拒绝解析,实际=${results[1]}",
            results[1] is Entry.Unresolved,
        )
        assertEquals("无 folderId 解析器,目录绑定拒绝解析", (results[1] as Entry.Unresolved).reason)
    }

    @Test
    fun `same path in two kbs gets distinct folder ids`() {
        val dir = Dir()
        val results = BindingScopeMigration.migrate(
            listOf("kb-a::共享目录", "kb-b::共享目录"),
            folderIdResolver = FolderIdResolver(dir),
        )

        val a = results[0] as Entry.Resolved
        val b = results[1] as Entry.Resolved
        assertTrue("跨库同名目录绑定不合并", a.folderId != b.folderId)
    }
}
