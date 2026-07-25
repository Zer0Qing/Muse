package io.zer0.muse.data.quicknote

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v1.0.17: 快速记录 Room 持久化(替代 JSON 文件存储)。
 *
 * 增加 deleted / deleted_at 标记实现回收站功能:
 *  - deleted=0: 正常记录(observeActive 查询)
 *  - deleted=1: 回收站(observeTrash 查询,deleted_at < before 的会被 cleanOldTrash 清理)
 *
 * tags 列通过 [QuickNoteConverters] 的 TypeConverter 把 List<String> 序列化为
 * 逗号分隔字符串存储(项目惯例:既有表如 assistants.tagsJson 用 JSON 列,
 * 此处为简化标签检索用逗号分隔,LIEK '%tag%' 即可命中)。
 *
 * 索引:
 *  - (deleted, updated_at): 覆盖 observeActive / observeTrash 的 WHERE + ORDER BY
 *  - (pinned, updated_at): 覆盖 ORDER BY pinned DESC, updated_at DESC
 *
 * v1.0.18 快速记录增强(9 项):
 *  - folder: 分类/文件夹,空串表示未分类
 *  - contentType: 内容类型 plain / markdown
 *  - attachmentsJson: 图片附件路径的 JSON 数组(空串表示无附件)
 *  - reminderAt: 提醒时间戳(0 表示无提醒)
 *  - encrypted: 是否加密
 *  - encryptedContent: 加密后的内容密文(encrypted=true 时 content 置空)
 */
@Entity(
    tableName = "quick_notes",
    indices = [
        Index(value = ["deleted", "updated_at"]),
        Index(value = ["pinned", "updated_at"]),
        Index(value = ["folder"]),
    ],
)
data class QuickNoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val tags: List<String>,
    @ColumnInfo(name = "pinned", defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(name = "deleted", defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(name = "deleted_at", defaultValue = "0") val deletedAt: Long = 0,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    // v1.0.18: 分类/文件夹
    @ColumnInfo(name = "folder", defaultValue = "") val folder: String = "",
    // v1.0.18: 富文本支持(plain / markdown)
    @ColumnInfo(name = "content_type", defaultValue = "plain") val contentType: String = "plain",
    // v1.0.18: 图片附件路径 JSON 数组(如 '["path1","path2"]')
    @ColumnInfo(name = "attachments_json", defaultValue = "") val attachmentsJson: String = "",
    // v1.0.18: 提醒时间戳(0 = 无提醒)
    @ColumnInfo(name = "reminder_at", defaultValue = "0") val reminderAt: Long = 0,
    // v1.0.18: 加密标记
    @ColumnInfo(name = "encrypted", defaultValue = "0") val encrypted: Boolean = false,
    // v1.0.18: 加密后的内容密文(encrypted=true 时存储,content 置空)
    @ColumnInfo(name = "encrypted_content", defaultValue = "") val encryptedContent: String = "",
)
