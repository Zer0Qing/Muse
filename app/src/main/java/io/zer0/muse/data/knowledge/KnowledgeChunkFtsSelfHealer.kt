package io.zer0.muse.data.knowledge

/**
 * R-DB-02: knowledge_chunks_fts 自愈修复器注册点。
 *
 * MuseDb 建库完成后注入 repairer;DAO 单条写操作捕获 no such table 后调用。
 * 修复动作必须走 raw SQL(DROP 影子表 + CREATE VIRTUAL TABLE),不能回调 FTS DAO,
 * 避免修复过程中再次触发自愈导致递归。
 */
object KnowledgeChunkFtsSelfHealer {

    @Volatile
    private var repairer: (suspend () -> Unit)? = null

    /** 由 [io.zer0.muse.data.session.MuseDb.get] 在实例构建完成后注册。 */
    fun install(repairer: suspend () -> Unit) {
        this.repairer = repairer
    }

    suspend fun repair() {
        val r = repairer ?: error("KnowledgeChunkFts repairer is not installed")
        r()
    }
}
