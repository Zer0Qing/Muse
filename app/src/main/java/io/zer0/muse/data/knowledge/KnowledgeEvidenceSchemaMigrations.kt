package io.zer0.muse.data.knowledge

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * R7 schema 迁移 — messages 增加证据快照列(129→130)。
 *
 * 只加列,不动任何既有行;旧消息该列为空串,读取时按「无证据快照」处理。
 * 列内容为 `EvidenceBundleSnapshot` 的 JSON(实际发送文本/片段/提供与已引用区分)。
 */
object KnowledgeEvidenceSchemaMigrations {

    val MIGRATION_129_130 = object : Migration(129, 130) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `messages` ADD COLUMN `knowledgeEvidenceJson` TEXT NOT NULL DEFAULT ''",
            )
        }
    }
}
