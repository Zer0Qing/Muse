package io.zer0.muse.knowledge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R2 步骤 9 护栏(计划 R2 步骤 9:「保留旧投影兼容读;真实用户写入切换仍关闭,
 * 直至 R9 便携备份与回退完成。合成库可继续后续开发」)。
 *
 * 这些测试钉住「未切换」状态本身:
 *  1. 旧 knowledge_docs 实体未加 V2 字段(兼容读 = 旧表结构原样);
 *  2. 生产 DI 模块未注册 V2 写入路径(切换点不存在 = 无法被无意接通)。
 * R9 完成便携备份与回退后,由 R9 的接线批次删除/改写这些护栏(它们届时会红,
 * 红即提醒先完成备份回退合同 —— 这是故意的阻碍设计,不是遗漏)。
 */
class WriteCutoverGuardTest {

    /**
     * Gradle test JVM 的 user.dir = 模块目录(app/),故源码相对 app/ 解析;
     * 兼容两种工作目录(模块根 / 仓库根),路径探测一次定型。
     */
    private fun readSource(relPathFromAppModule: String): String {
        val moduleDir = File(System.getProperty("user.dir"))
        val direct = File(moduleDir, relPathFromAppModule)
        if (direct.isFile) return direct.readText()
        val fromRepoRoot = File(moduleDir, "app/$relPathFromAppModule")
        check(fromRepoRoot.isFile) { "源文件不可达: $relPathFromAppModule (cwd=${moduleDir.absolutePath})" }
        return fromRepoRoot.readText()
    }

    @Test
    fun `legacy doc entity is unchanged by v2 (compatible read preserved)`() {
        val src = readSource("src/main/java/io/zer0/muse/data/knowledge/KnowledgeDocEntity.kt")
        // 兼容读合同:旧实体不得携带 V2 专属字段(V2 用新表,不改旧表)。
        assertFalse(
            "旧 KnowledgeDocEntity 不得引用 V2 概念(兼容投影保持)",
            src.contains("KnowledgeV2") || src.contains("revisionId") || src.contains("sourceRef"),
        )
        assertTrue("旧实体仍含 kbId(多库兼容读)", src.contains("kb_id"))
    }

    @Test
    fun `production DI does not register v2 write path (cutover closed)`() {
        val koinSrc = readSource("src/main/java/io/zer0/muse/AppKoinModule.kt")
        assertFalse(
            "AppKoinModule 不得注册 KnowledgeRepository(真实用户写入切换必须保持关闭至 R9)",
            koinSrc.contains("KnowledgeRepository("),
        )
        assertFalse(
            "AppKoinModule 不得注册 V2 DAO 注入(同上)",
            koinSrc.contains("knowledgeV2Dao"),
        )
    }

    @Test
    fun `v2 write entry points exist only in knowledge storage package (no legacy import wiring)`() {
        // R2 的 V2 写入类只存在于 knowledge/storage;旧导入链(RagService/DocumentParser 等)
        // 不得调用它们。扫描旧链关键文件不含 V2 写入调用。
        val ragService = readSource("src/main/java/io/zer0/muse/rag/RagService.kt")
        assertFalse(
            "旧导入链不得调用 V2 写入(切换在 R9)",
            ragService.contains("KnowledgeRepository(") || ragService.contains("importLegacyRow"),
        )
    }
}
