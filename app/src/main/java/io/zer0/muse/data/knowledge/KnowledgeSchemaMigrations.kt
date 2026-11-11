package io.zer0.muse.data.knowledge

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * R2 schema 迁移 — V2 四表(125→126)。
 *
 * 规则(计划全局约束):不 destructive migration、不 REPLACE 丢行、不删旧表;
 * 只 CREATE 新表,不动任何既有行。版本号从当时真源(LATEST_SCHEMA_VERSION=125)分配。
 *
 * 表清单(列名/默认值与 KnowledgeV2*Entity 逐项对齐,Room 启动校验按实体 schema 严格比对):
 *  - knowledge_v2_docs:持久 owner(ownerType+ownerId)+ folderId + 活动 revision 指针 + epoch
 *  - knowledge_v2_revisions:不可变版本链(docId+revisionNo 唯一)
 *  - knowledge_v2_dataset_epoch:单调 epoch(恢复/回滚身份)
 *  - knowledge_v2_retention_leases:内容租约
 */
object KnowledgeSchemaMigrations {

    val MIGRATION_125_126 = object : Migration(125, 126) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_docs` (" +
                    "`docId` TEXT NOT NULL, " +
                    "`ownerType` TEXT NOT NULL, " +
                    "`ownerId` TEXT NOT NULL, " +
                    "`folderId` TEXT NOT NULL DEFAULT '', " +
                    "`current_revision_id` TEXT NOT NULL DEFAULT '', " +
                    "`dataset_epoch` INTEGER NOT NULL DEFAULT 0, " +
                    "`source_ref_json` TEXT NOT NULL DEFAULT '', " +
                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                    "`updated_at` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`docId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_docs_ownerType_ownerId` " +
                    "ON `knowledge_v2_docs` (`ownerType`, `ownerId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_docs_folderId` " +
                    "ON `knowledge_v2_docs` (`folderId`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_revisions` (" +
                    "`revisionId` TEXT NOT NULL, " +
                    "`docId` TEXT NOT NULL, " +
                    "`revision_no` INTEGER NOT NULL, " +
                    "`content_hash` TEXT NOT NULL, " +
                    "`full_content` TEXT NOT NULL DEFAULT '', " +
                    "`blob_ref` TEXT NOT NULL DEFAULT '', " +
                    "`preview` TEXT NOT NULL DEFAULT '', " +
                    "`origin` TEXT NOT NULL DEFAULT '', " +
                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`revisionId`), " +
                    "FOREIGN KEY(`docId`) REFERENCES `knowledge_v2_docs`(`docId`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_v2_revisions_docId_revision_no` " +
                    "ON `knowledge_v2_revisions` (`docId`, `revision_no`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_revisions_content_hash` " +
                    "ON `knowledge_v2_revisions` (`content_hash`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_dataset_epoch` (" +
                    "`epochId` TEXT NOT NULL, " +
                    "`epoch_no` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                    "`reason` TEXT NOT NULL DEFAULT '', " +
                    "PRIMARY KEY(`epochId`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_retention_leases` (" +
                    "`leaseId` TEXT NOT NULL, " +
                    "`content_key` TEXT NOT NULL, " +
                    "`holder_id` TEXT NOT NULL, " +
                    "`acquired_at` INTEGER NOT NULL DEFAULT 0, " +
                    "`expires_at` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`leaseId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_retention_leases_content_key` " +
                    "ON `knowledge_v2_retention_leases` (`content_key`)",
            )
        }
    }
}
