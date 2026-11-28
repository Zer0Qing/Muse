package io.zer0.muse.backup

/**
 * 一次备份导入的可读结果，供 UI 在完成后如实展示。
 *
 * 存在的理由：此前导入只回报「N 个会话 / M 条消息」，用户看到知识库、定时任务、
 * 便签仍是空的，只能猜「是没恢复还是备份里本来没有」。这份摘要把
 * **备份里声明的数量**与**实际跳过的条目**一起摆出来，让"空白"当场可判读。
 *
 * 所有计数都取自 NDJSON 的 meta 行（导出侧自己写的声明），不是导入后的库内行数 ——
 * 覆盖模式下两者应相等，合并模式下只并入对话，其它类型本就该为 0。
 */
data class BackupImportSummary(
    val mode: BackupImportMode = BackupImportMode.OVERWRITE,
    val sessions: Int = 0,
    val messages: Int = 0,
    val assistants: Int = 0,
    val knowledgeDocs: Int = 0,
    val scheduledTasks: Int = 0,
    val quickNotes: Int = 0,
    val groupChats: Int = 0,
    /** 备份里是否带了供应商配置（决定恢复后是否需要重填 API Key）。 */
    val providersIncluded: Boolean = false,
    /** 便携备份中声明的 MCP 连接配置数。 */
    val mcpServersIncluded: Int = 0,
    /** MCP 静态凭据与令牌未随备份导出，恢复后需配置或重新授权。 */
    val mcpCredentialsOmitted: Boolean = false,
    /** 随备份一起恢复的设置项数量。 */
    val settingsKeys: Int = 0,
    /** 因超出容量/白名单被跳过的目录型文件数（附件、头像等）。 */
    val skippedFiles: Int = 0,
    /** 被跳过的已下线小手机会话数与消息数。 */
    val skippedMiniPhoneSessions: Int = 0,
    val skippedMiniPhoneMessages: Int = 0,
)
