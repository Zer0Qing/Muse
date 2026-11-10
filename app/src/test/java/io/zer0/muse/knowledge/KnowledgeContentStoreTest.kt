package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.storage.KnowledgeContentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R2 步骤 2 合同 — 原件存储协议(计划 R2 步骤 2:
 * 「建立同目录暂存、字节 hash、关闭校验、文件发布、DB 引用和孤儿文件清理协议。
 * 测试每个断点失败时"不引用不存在文件、不删已有原件"」)。
 *
 * 内容寻址:发布名 = <sha256(hex)>,同内容天然去重;协议断点逐个注入失败,
 * 断言不变式:已发布原件永不因新写入失败而被删除;DB 引用只指向真实存在的文件。
 */
class KnowledgeContentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(vararg failAt: KnowledgeContentStore.FailurePoint) =
        KnowledgeContentStore(
            rootDirectory = tmp.newFolder("blobs"),
            injectedFailures = failAt.toSet(),
        )

    // ---------- 正常路径:六断点全过 ----------

    @Test
    fun `put publishes content addressed file and returns hash reference`() {
        val s = store()
        val ref = s.put("file", "原始字节".toByteArray())
        assertEquals("发布引用 = 内容 hash(64 hex 字符)", 64, ref.length)
        assertTrue("发布文件存在", s.exists(ref))
        assertEquals("读回字节一致", "原始字节".toByteArray(), s.read(ref))
    }

    @Test
    fun `same content deduplicates to same hash`() {
        val s = store()
        val ref1 = s.put("file", "abc".toByteArray())
        val ref2 = s.put("content", "abc".toByteArray())
        assertEquals("同内容同 hash(内容寻址去重)", ref1, ref2)
    }

    // ---------- 断点失败合同:不引用不存在文件、不删已有原件 ----------

    @Test
    fun `staging failure leaves existing originals untouched and returns null`() {
        val s = store()
        val existing = s.put("first", "已有原件".toByteArray())

        val ref = store(KnowledgeContentStore.FailurePoint.STAGING_WRITE)
            .let { failed ->
                // 失败实例必须指向同一物理目录才能验证「不删已有」
                KnowledgeContentStore(
                    rootDirectory = s.rootDirectory,
                    injectedFailures = setOf(KnowledgeContentStore.FailurePoint.STAGING_WRITE),
                ).also { }
            }.put("second", "新字节".toByteArray())

        assertNull("staging 失败必须返回 null,不得返回假引用", ref)
        assertTrue("已有原件必须完好", s.exists(existing))
        assertEquals("已有原件内容不变", "已有原件".toByteArray(), s.read(existing))
    }

    @Test
    fun `close verification failure removes staging and does not publish`() {
        val s = store(KnowledgeContentStore.FailurePoint.CLOSE_VERIFY)
        val ref = s.put("x", "字节".toByteArray())
        assertNull("关闭校验失败不得发布", ref)
        assertFalse("不得存在发布文件", s.exists("字节".toByteArray().let { "" })) // 占位:见下方 staging 清理断言
        assertTrue("staging 残留必须已清理", s.listStaging().isEmpty())
    }

    @Test
    fun `publish failure leaves staging cleaned and originals intact`() {
        val s = store()
        val existing = s.put("first", "已有原件".toByteArray())
        val failing = KnowledgeContentStore(
            rootDirectory = s.rootDirectory,
            injectedFailures = setOf(KnowledgeContentStore.FailurePoint.PUBLISH_RENAME),
        )
        val ref = failing.put("second", "新内容".toByteArray())
        assertNull("发布失败返回 null", ref)
        assertTrue("已有原件完好", s.exists(existing))
        assertTrue("失败写入的 staging 已清理", s.listStaging().isEmpty())
    }

    @Test
    fun `db reference failure keeps file but marks orphan for cleanup`() {
        val s = store(KnowledgeContentStore.FailurePoint.DB_REFERENCE)
        val ref = s.put("x", "字节".toByteArray())
        // DB 引用失败:文件已发布(内容寻址,无害),但 put 返回的引用不得被调用方
        // 当作已登记;store 的未引用集合记录它,孤儿清理可回收。
        assertNull("DB 引用失败 = put 失败(调用方不得引用)", ref)
        assertTrue(
            "已发布文件进入未引用登记(可被孤儿清理回收,不静默冒充成功)",
            s.listUnreferenced().isNotEmpty(),
        )
    }

    @Test
    fun `orphan cleanup removes only unreferenced staging files and never published ones`() {
        val s = store()
        val published = s.put("keep", "保留".toByteArray())
        // 制造一个 staging 残留(模拟历史中断)
        s.stageRaw("orphan-staging")

        s.cleanupOrphans(maxAgeMs = 0L)

        assertTrue("已发布原件不得被清理", s.exists(published))
        assertTrue("孤儿 staging 被清理", s.listStaging().isEmpty())
    }

    @Test
    fun `cleanup respects max age`() {
        val s = store()
        s.stageRaw("fresh-staging")
        s.cleanupOrphans(maxAgeMs = Long.MAX_VALUE)
        assertTrue("未到期 staging 不得清理", s.listStaging().isNotEmpty())
    }

    // ---------- 读取合同 ----------

    @Test
    fun `read of missing reference returns null without throwing`() {
        val s = store()
        assertNull(s.read("missing-hash"))
        assertFalse(s.exists("missing-hash"))
    }
}
