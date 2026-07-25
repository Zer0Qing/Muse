package io.zer0.muse.data.audit

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * P2-4 审计日志实体。
 *
 * 记录应用内关键操作(API 调用 / 用户动作 / 认证 / 系统)用于安全审计与问题回溯。
 *  - [category]: "api_call" / "user_action" / "auth" / "system"
 *  - [action]: 具体动作名,如 "send_message" / "delete_chat" / "login"
 *  - [target]: 操作目标标识(chatId / providerId 等,可空)
 *  - [detail]: JSON 字符串,存储额外字段(model / tokens / latency_ms 等)
 *  - [success]: 操作是否成功(失败时记录便于排查)
 *
 * 表结构只新增,不修改既有表。环形缓冲策略由 [AuditLogger] 维护。
 */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(defaultValue = "") val category: String,
    @ColumnInfo(defaultValue = "") val action: String,
    @ColumnInfo(defaultValue = "") val target: String = "",
    @ColumnInfo(defaultValue = "") val detail: String = "",
    @ColumnInfo(defaultValue = "1") val success: Boolean = true,
)
