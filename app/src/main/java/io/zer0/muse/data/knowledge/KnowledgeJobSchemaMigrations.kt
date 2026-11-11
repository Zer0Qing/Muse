package io.zer0.muse.data.knowledge

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * R4 schema 迁移 — 作业账本与 generation 化索引表(126→127)。
 *
 * 规则(计划全局约束):不 destructive migration、不 REPLACE 丢行、不删旧表;
 * 只 CREATE 新表,不动任何既有行。旧 knowledge_chunks/knowledge_chunks_fts 保持原样。
 *
 * DDL 与 KnowledgeJobEntities.kt 的实体逐列对齐(Room 启动校验按实体 schema 严格比对)。
 */
object KnowledgeJobSchemaMigrations {

    val MIGRATION_126_127 = object : Migration(126, 127) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 批次总账
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_jobs` (" +
                    "`jobId` TEXT NOT NULL, " +
                    "`owner_type` TEXT NOT NULL, " +
                    "`owner_id` TEXT NOT NULL, " +
                    "`state` TEXT NOT NULL, " +
                    "`dataset_epoch` INTEGER NOT NULL DEFAULT 0, " +
                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                    "`updated_at` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`jobId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_jobs_state` " +
                    "ON `knowledge_v2_jobs` (`state`)",
            )

            // 逐文件账本
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_job_items` (" +
                    "`jobItemId` TEXT NOT NULL, " +
                    "`jobId` TEXT NOT NULL, " +
                    "`doc_id` TEXT NOT NULL, " +
                    "`state` TEXT NOT NULL, " +
                    "`stage` TEXT NOT NULL, " +
                    "`checkpoint` TEXT NOT NULL DEFAULT '', " +
                    "`dataset_epoch` INTEGER NOT NULL DEFAULT 0, " +
                    "`generation_fence` INTEGER NOT NULL DEFAULT 0, " +
                    "`cancel_requested` INTEGER NOT NULL DEFAULT 0, " +
                    "`lease_holder` TEXT NOT NULL DEFAULT '', " +
                    "`lease_expires_at` INTEGER NOT NULL DEFAULT 0, " +
                    "`result_reason` TEXT NOT NULL DEFAULT '', " +
                    "`attempts` INTEGER NOT NULL DEFAULT 0, " +
                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                    "`updated_at` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`jobItemId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_job_items_jobId` " +
                    "ON `knowledge_v2_job_items` (`jobId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_job_items_doc_id` " +
                    "ON `knowledge_v2_job_items` (`doc_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_job_items_state` " +
                    "ON `knowledge_v2_job_items` (`state`)",
            )

            // 索引代
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_generations` (" +
                    "`generationId` TEXT NOT NULL, " +
                    "`docId` TEXT NOT NULL, " +
                    "`generation_no` INTEGER NOT NULL, " +
                    "`state` TEXT NOT NULL, " +
                    "`revision_id` TEXT NOT NULL DEFAULT '', " +
                    "`embedding_space_id` TEXT NOT NULL DEFAULT '', " +
                    "`chunk_count` INTEGER NOT NULL DEFAULT 0, " +
                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`generationId`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_v2_generations_docId_generation_no` " +
                    "ON `knowledge_v2_generations` (`docId`, `generation_no`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_generations_docId` " +
                    "ON `knowledge_v2_generations` (`docId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_generations_state` " +
                    "ON `knowledge_v2_generations` (`state`)",
            )

            // 代内 chunk 行
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_gen_chunks` (" +
                    "`id` TEXT NOT NULL, " +
                    "`generationId` TEXT NOT NULL, " +
                    "`chunk_index` INTEGER NOT NULL, " +
                    "`content` TEXT NOT NULL DEFAULT '', " +
                    "`locator` TEXT NOT NULL DEFAULT '', " +
                    "`kind` TEXT NOT NULL DEFAULT '', " +
                    "`token_count` INTEGER NOT NULL DEFAULT 0, " +
                    "`metadata_json` TEXT NOT NULL DEFAULT '{}', " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_v2_gen_chunks_generationId_chunk_index` " +
                    "ON `knowledge_v2_gen_chunks` (`generationId`, `chunk_index`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_gen_chunks_generationId` " +
                    "ON `knowledge_v2_gen_chunks` (`generationId`)",
            )

            // 代内词法行
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_gen_lexical` (" +
                    "`id` TEXT NOT NULL, " +
                    "`generationId` TEXT NOT NULL, " +
                    "`chunk_index` INTEGER NOT NULL, " +
                    "`lexical_text` TEXT NOT NULL DEFAULT '', " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_v2_gen_lexical_generationId_chunk_index` " +
                    "ON `knowledge_v2_gen_lexical` (`generationId`, `chunk_index`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_gen_lexical_generationId` " +
                    "ON `knowledge_v2_gen_lexical` (`generationId`)",
            )

            // 活动代指针
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_active_generation` (" +
                    "`docId` TEXT NOT NULL, " +
                    "`generationId` TEXT NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`docId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_active_generation_generationId` " +
                    "ON `knowledge_v2_active_generation` (`generationId`)",
            )
        }
    }
}
