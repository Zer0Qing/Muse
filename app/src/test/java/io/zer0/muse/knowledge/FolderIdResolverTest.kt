package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.storage.FolderIdResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 步骤 7 合同(计划:「将显式与虚拟目录归一为稳定 folderId/parentId,保留空目录;
 * 迁移 kbId::folderPath 绑定;冲突和无法解析绑定进入报告而不是 All」—— 本类钉 folderId
 * 归一半;绑定解析兼容与报告结构同批落地于 BindingScopeMigration)。
 */
class FolderIdResolverTest {

    /** 内存目录(等价 knowledge_folders 表的唯一约束语义)。 */
    private class InMemoryDirectory : FolderIdResolver.FolderDirectory {
        val rows = mutableMapOf<Pair<String, String>, String>()
        override fun findById(kbId: String, path: String): String? = rows[kbId to path]
        override fun create(kbId: String, path: String): String {
            val id = FolderIdResolver.folderIdOf(kbId, path)
            rows[kbId to path] = id
            return id
        }
    }

    @Test
    fun `virtual path resolves to stable id and empty folders are preserved`() {
        val dir = InMemoryDirectory()
        val resolver = FolderIdResolver(dir)

        val first = resolver.resolve("kb-a", "合同")
        val second = resolver.resolve("kb-a", "合同")

        assertEquals("同一 (kb,path) 归一到同一 folderId(幂等)", first, second)
        assertTrue("folderId 非空(根除外)", first.isNotBlank())
        // 目录行已创建:空文件夹也有一等公民行(保留空目录)
        assertTrue("目录行保留", dir.rows.containsKey("kb-a" to "合同"))
    }

    @Test
    fun `same folder name in two kbs resolves to different ids`() {
        val dir = InMemoryDirectory()
        val resolver = FolderIdResolver(dir)

        val alpha = resolver.resolve("kb-a", "共享目录")
        val beta = resolver.resolve("kb-b", "共享目录")

        assertNotEquals("跨库同名目录不合并(不同 folderId)", alpha, beta)
    }

    @Test
    fun `paths normalize before resolution`() {
        val dir = InMemoryDirectory()
        val resolver = FolderIdResolver(dir)

        val a = resolver.resolve("kb-a", "合同/2026")
        val b = resolver.resolve("kb-a", "合同//2026/")
        val c = resolver.resolve("kb-a", "合同/./2026")

        assertEquals("归一化后同一路径(斜杠冗余/当前段)", a, b)
        assertEquals("归一化后同一路径(点段)", a, c)
    }

    @Test
    fun `root path resolves to empty folderId without creating a row`() {
        val dir = InMemoryDirectory()
        val resolver = FolderIdResolver(dir)

        assertEquals("库根 = 空串(根不落表,与旧表语义一致)", "", resolver.resolve("kb-a", ""))
        assertEquals("空目录行数为 0(根不创建行)", 0, dir.rows.size)
    }
}
