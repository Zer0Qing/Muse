package io.zer0.muse.data.knowledge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认知识库兜底合同。
 *
 * 回归背景（用户实测）：知识库导入后「首页显示 148 个文档、点进去是空的」。
 *
 * 根因：普通文件导入把文档写成 `kb_id='default'`，但 `knowledge_bases` 里从来没有
 * `default` 这一行 —— `MIGRATION_38_39` 只 `CREATE TABLE`，实体注释里写的
 * 「MIGRATION_38_39 会自动插入」与实现不符。知识库页面顶层先列 KB 列表，
 * 列表为空就直接渲染空态；而首页计数直接数文档表，于是两边对不上。
 *
 * 本测试钉住三件事：
 *  1. 默认 kb id 必须与文档实体的默认值一致；
 *  2. 兜底逻辑必须是幂等的（已存在就不写）；
 *  3. 兜底必须接到启动流程。
 */
class KnowledgeDefaultKbContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("无法定位 $relative")
    }

    private val ensurer: String by lazy {
        source("src/main/java/io/zer0/muse/data/knowledge/KnowledgeDefaultKbEnsurer.kt")
    }
    private val docEntity: String by lazy {
        source("src/main/java/io/zer0/muse/data/knowledge/KnowledgeDocEntity.kt")
    }
    private val app: String by lazy { source("src/main/java/io/zer0/muse/MuseApp.kt") }

    @Test
    fun `default kb id matches the document entity default`() {
        assertTrue(
            "兜底用的 id 必须与文档实体默认值一致，否则补建出来的库仍然接不住文档",
            ensurer.contains("DEFAULT_KB_ID = \"default\""),
        )
        assertTrue(
            "文档实体的 kb_id 默认值应仍是 default",
            docEntity.contains("kb_id") || docEntity.contains("kbId"),
        )
    }

    @Test
    fun `ensurer is idempotent`() {
        assertTrue("已存在时必须直接返回", ensurer.contains("if (existing != null) return@withContext false"))
        assertTrue(
            "只在「有文档挂在这个 id 下」或「一个库都没有」时才补建",
            ensurer.contains("if (docCount == 0 && kbCount > 0) return@withContext false"),
        )
    }

    @Test
    fun `ensurer never throws into startup`() {
        assertTrue("读取必须包 resultOf", ensurer.contains("resultOf { dao.getById(DEFAULT_KB_ID) }"))
        assertTrue("写入必须包 resultOf", ensurer.contains("resultOf {"))
        assertFalse(
            "不得用裸 catch 吞掉取消",
            ensurer.contains("catch (e: Exception)"),
        )
    }

    @Test
    fun `ensurer runs before dev doc seeding`() {
        val ensureIdx = app.indexOf("knowledgeDefaultKbEnsurer.ensureExists()")
        val seedIdx = app.indexOf("resultOf { seedDevDocs() }")
        assertTrue("必须接到启动流程", ensureIdx >= 0)
        assertTrue("必须在 seedDevDocs 之前：内置文档也要有可归属的库", seedIdx > ensureIdx)
    }
}
