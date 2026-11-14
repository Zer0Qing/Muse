package io.zer0.muse.data.knowledge

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 确保「默认知识库」存在。
 *
 * ## 为什么需要这一步
 *
 * 文档表 `knowledge_docs.kb_id` 的默认值是 `'default'`，普通文件导入时也写这个值。
 * 但 `knowledge_bases` 里从来没有对应的行：
 *
 *  - `MIGRATION_38_39` 只 `CREATE TABLE knowledge_bases`，**没有插入默认行**
 *    （实体注释里写的「MIGRATION_38_39 会自动插入」与实现不符）；
 *  - 只有「新建知识库」和「导入压缩包」两条路径会 `upsert` KB。
 *
 * 结果就是：文档全挂在 `kb_id='default'` 下，而知识库页面顶层先列 KB 列表，
 * 列表为空 → 直接显示「知识库为空」。首页的文档计数却直接数文档表，
 * 所以会出现「首页显示 148 个文档、点进去是空的」这种自相矛盾的状态。
 *
 * ## 为什么用 upsert 而不是 insert
 *
 * 幂等：已有 `default` 行时 `REPLACE` 会把它覆盖成同样的内容（name/desc 保持不变），
 * 代价可忽略；用户自己改过默认库名字的话，这里会把它改回「默认知识库」——
 * 这是刻意取舍：宁可名字回到出厂值，也不能让入口消失。为避免误伤，
 * 已存在时**直接返回**，不做任何写入。
 */
class KnowledgeDefaultKbEnsurer(
    private val db: MuseDb,
) {
    companion object {
        private const val TAG = "KnowledgeDefaultKb"

        /** 与 [KnowledgeDocEntity.kbId] 的默认值保持一致，不可另立常量。 */
        const val DEFAULT_KB_ID = "default"

        private const val DEFAULT_KB_NAME_ZH = "默认知识库"
    }

    /**
     * 确保默认 KB 存在。
     *
     * @return true 表示本次补建了默认库；false 表示已存在或无需补建
     */
    suspend fun ensureExists(): Boolean = withContext(Dispatchers.IO) {
        val dao = db.knowledgeBaseDao()
        val existing = resultOf { dao.getById(DEFAULT_KB_ID) }
            .onError { msg, t -> Logger.w(TAG, "读取默认知识库失败: $msg", t) }
            .getOrNull()
        if (existing != null) return@withContext false

        // 只在确实有文档挂在这个 id 下、或库里一个 KB 都没有时才补建：
        // 前者是数据不一致必须修，后者是新装/清库后需要有个落点。
        val docCount = resultOf { db.knowledgeDocDao().countByKb(DEFAULT_KB_ID) }
            .onError { msg, t -> Logger.w(TAG, "统计默认库文档数失败: $msg", t) }
            .getOrNull() ?: 0
        val kbCount = resultOf { dao.count() }
            .onError { msg, t -> Logger.w(TAG, "统计知识库总数失败: $msg", t) }
            .getOrNull() ?: 0
        if (docCount == 0 && kbCount > 0) return@withContext false

        val now = System.currentTimeMillis()
        val created = resultOf {
            dao.upsert(
                KnowledgeBaseEntity(
                    id = DEFAULT_KB_ID,
                    name = DEFAULT_KB_NAME_ZH,
                    description = "",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            true
        }.onError { msg, t -> Logger.w(TAG, "补建默认知识库失败: $msg", t) }
            .getOrNull() ?: false

        if (created) {
            Logger.i(TAG, "已补建默认知识库（原 kb_id=default 下有 $docCount 篇文档）")
        }
        created
    }
}
