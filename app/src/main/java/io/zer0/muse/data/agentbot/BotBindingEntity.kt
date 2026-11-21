package io.zer0.muse.data.agentbot

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Bot 与助手 / canonical 会话的绑定。
 *
 * ## 为什么需要这张表
 *
 * Agent Tab 的"当前是谁"此前借用了主动消息配置里的 agentId —— 于是改主动消息
 * 设置会顺带把 Agent Tab 的人格换掉。这张表把两者解耦：主动消息可以随便改，
 * Bot 的身份只在用户明确切换时变。
 *
 * 迁移时可以从原主动消息 agentId 初始化**一次**，之后不再回读。
 */
@Serializable
@Entity(
    tableName = "bot_bindings",
    indices = [Index(value = ["canonicalAgentSessionId"])],
)
data class BotBindingEntity(
    @PrimaryKey val botId: String,
    @ColumnInfo(defaultValue = "") val assistantId: String = "",
    /** 这个 Bot 的公共历史落在这个会话里；通知、调度、审核都走同一 resolver。 */
    @ColumnInfo(defaultValue = "") val canonicalAgentSessionId: String = "",
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)