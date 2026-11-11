package io.zer0.muse.data.knowledge

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * R5 schema 迁移 — 向量批次能力(127→128)。
 *
 * 只给 V2 generation 派生物加列,不动任何旧表/既有行:
 *  - `knowledge_v2_gen_chunks.embedding_blob`:该代 chunk 的向量;NULL = 未生成/未发布;
 *  - `knowledge_v2_generations.vector_ready`:向量批是否完整验证并写入(0/1)。
 *
 * 不变式:vector_ready=0 的代不得报告 HybridReady(由 GenerationPublisher 强制)。
 */
object KnowledgeVectorSchemaMigrations {

    val MIGRATION_127_128 = object : Migration(127, 128) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `knowledge_v2_gen_chunks` ADD COLUMN `embedding_blob` BLOB DEFAULT NULL",
            )
            db.execSQL(
                "ALTER TABLE `knowledge_v2_generations` ADD COLUMN `vector_ready` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }
}
