package io.zer0.muse.data.knowledge

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * R8 schema 迁移 — 稳定目录身份与绑定(128→129)。
 *
 * 只 CREATE 新表,不动旧 `knowledge_folders`(path 派生主键)与既有行;
 * 旧绑定(kbId::folderPath 字符串)迁移到稳定 ID 的适配由 R8 服务层按需解析,
 * 无法解析的绑定进入报告而不扩成全库(计划 §15.2)。
 */
object KnowledgeFolderSchemaMigrations {

    val MIGRATION_128_129 = object : Migration(128, 129) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_folders` (" +
                    "`folderId` TEXT NOT NULL, " +
                    "`kbId` TEXT NOT NULL, " +
                    "`parentId` TEXT NOT NULL DEFAULT '', " +
                    "`name` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                    "`updated_at` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`folderId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_folders_kbId` " +
                    "ON `knowledge_v2_folders` (`kbId`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_v2_folders_kbId_parentId_name` " +
                    "ON `knowledge_v2_folders` (`kbId`, `parentId`, `name`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `knowledge_v2_bindings` (" +
                    "`bindingId` TEXT NOT NULL, " +
                    "`ownerType` TEXT NOT NULL, " +
                    "`ownerId` TEXT NOT NULL, " +
                    "`targetType` TEXT NOT NULL, " +
                    "`targetId` TEXT NOT NULL, " +
                    "`dataset_epoch` INTEGER NOT NULL DEFAULT 0, " +
                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`bindingId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_knowledge_v2_bindings_ownerType_ownerId` " +
                    "ON `knowledge_v2_bindings` (`ownerType`, `ownerId`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_v2_bindings_ownerType_ownerId_targetType_targetId` " +
                    "ON `knowledge_v2_bindings` (`ownerType`, `ownerId`, `targetType`, `targetId`)",
            )
        }
    }
}
