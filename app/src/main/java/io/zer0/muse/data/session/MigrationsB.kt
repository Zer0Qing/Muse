package io.zer0.muse.data.session

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.zer0.common.Logger

/**
Room 迁移链第二段（37 个迁移）。
 */
internal object MigrationsB {
    /**
     * v2.7.x: 帮你跟进（Watcher）与长期目标（Goal），以及卡片的 Spaces 刷新字段。
     *
     * 两张新表均为纯新增，不触碰既有行；卡片表通过 ALTER TABLE 追加五列，
     * 全部带 NOT NULL DEFAULT，存量卡片读出来就是「未钉入、不自动刷新」。
     * 列名、类型与默认值必须逐项对齐 AgentWatcherEntity / AgentGoalEntity / CardEntity，
     * 索引名按 Room 的 index_<table>_<column...> 规则生成。
     */
    val MIGRATION_139_140 = object : Migration(139, 140) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `agent_watchers` (" +
                    "`watcherId` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`originSessionId` TEXT NOT NULL DEFAULT '', `title` TEXT NOT NULL DEFAULT '', " +
                    "`instruction` TEXT NOT NULL DEFAULT '', `script` TEXT NOT NULL DEFAULT '', " +
                    "`scriptKind` TEXT NOT NULL DEFAULT 'python', " +
                    "`computeTarget` TEXT NOT NULL DEFAULT 'local', " +
                    "`intervalMinutes` INTEGER NOT NULL DEFAULT 30, " +
                    "`webhookUrl` TEXT NOT NULL DEFAULT '', `remoteWatcherId` TEXT NOT NULL DEFAULT '', " +
                    "`lastFingerprint` TEXT NOT NULL DEFAULT '', `lastResult` TEXT NOT NULL DEFAULT '', " +
                    "`lastCheckedAt` INTEGER NOT NULL DEFAULT 0, `nextCheckAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`status` TEXT NOT NULL DEFAULT 'ACTIVE', `lastError` TEXT NOT NULL DEFAULT '', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`watcherId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_watchers_botId_status` " +
                    "ON `agent_watchers` (`botId`, `status`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_watchers_originSessionId` " +
                    "ON `agent_watchers` (`originSessionId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_watchers_nextCheckAt` " +
                    "ON `agent_watchers` (`nextCheckAt`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `agent_goals` (" +
                    "`goalId` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`originSessionId` TEXT NOT NULL DEFAULT '', `title` TEXT NOT NULL DEFAULT '', " +
                    "`description` TEXT NOT NULL DEFAULT '', `milestonesJson` TEXT NOT NULL DEFAULT '[]', " +
                    "`progress` INTEGER NOT NULL DEFAULT 0, `status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                    "`dueAt` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`goalId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_goals_botId_status` " +
                    "ON `agent_goals` (`botId`, `status`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_goals_originSessionId` " +
                    "ON `agent_goals` (`originSessionId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_goals_updatedAt` " +
                    "ON `agent_goals` (`updatedAt`)",
            )
            db.execSQL("ALTER TABLE `card_entities` ADD COLUMN `spacePinned` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `card_entities` ADD COLUMN `spaceCategory` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `card_entities` ADD COLUMN `refreshIntervalMinutes` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `card_entities` ADD COLUMN `refreshPrompt` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `card_entities` ADD COLUMN `lastRefreshedAt` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** M4: 新增媒体创作台后台任务表与本地资产表（纯新增表，不改动既有行）。 */
    val MIGRATION_140_141 = object : Migration(140, 141) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `media_gen_tasks` (" +
                    "`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`prompt` TEXT NOT NULL, `providerId` TEXT NOT NULL, `modelId` TEXT NOT NULL, " +
                    "`paramsJson` TEXT NOT NULL, `referenceImagePath` TEXT, " +
                    "`elapsedSeconds` INTEGER NOT NULL, `errorMessage` TEXT, `source` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_gen_tasks_kind` ON `media_gen_tasks` (`kind`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_gen_tasks_status` ON `media_gen_tasks` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_gen_tasks_createdAt` ON `media_gen_tasks` (`createdAt`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `media_gen_assets` (" +
                    "`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`localPath` TEXT NOT NULL, `thumbnailPath` TEXT, `remoteUrl` TEXT, " +
                    "`mimeType` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, " +
                    "`durationMs` INTEGER NOT NULL, `prompt` TEXT NOT NULL, `modelId` TEXT NOT NULL, " +
                    "`providerId` TEXT NOT NULL, `paramsJson` TEXT NOT NULL, `favorite` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_gen_assets_taskId` ON `media_gen_assets` (`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_gen_assets_kind` ON `media_gen_assets` (`kind`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_gen_assets_createdAt` ON `media_gen_assets` (`createdAt`)")
        }
    }

    /** 为牵挂账本追加可空定时触发列 triggerAt（与 expiresAt 有效期窗口解耦）。 */
    val MIGRATION_141_142 = object : Migration(141, 142) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val cols = mutableSetOf<String>()
            db.query("PRAGMA table_info(`pending_thought`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) cols += cursor.getString(nameIdx)
                }
            }
            if ("triggerAt" !in cols) {
                db.execSQL("ALTER TABLE `pending_thought` ADD COLUMN `triggerAt` INTEGER DEFAULT NULL")
            }
        }
    }

    /**
     * Linux 轻量云电脑节点表。
     *
     * 纯新增表，不触碰任何既有行。列名、类型与默认值必须逐项对齐
     * `RemoteComputeNodeEntity` —— 任一列不一致，Room 在升级后的 schema 校验阶段
     * 会直接报 "Migration didn't properly handle: remote_compute_nodes"。
     */
    val MIGRATION_138_139 = object : Migration(138, 139) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `remote_compute_nodes` (" +
                    "`nodeId` TEXT NOT NULL, `name` TEXT NOT NULL DEFAULT '', " +
                    "`connectionMode` TEXT NOT NULL DEFAULT 'SSH_TUNNEL', " +
                    "`host` TEXT NOT NULL DEFAULT '', " +
                    "`sshPort` INTEGER NOT NULL DEFAULT 22, " +
                    "`daemonPort` INTEGER NOT NULL DEFAULT 18765, " +
                    "`username` TEXT NOT NULL DEFAULT '', " +
                    "`authType` TEXT NOT NULL DEFAULT 'PASSWORD', " +
                    "`encryptedCredential` TEXT NOT NULL DEFAULT '', " +
                    "`encryptedNodeToken` TEXT NOT NULL DEFAULT '', " +
                    "`tlsFingerprint` TEXT NOT NULL DEFAULT '', " +
                    "`osInfoJson` TEXT NOT NULL DEFAULT '{}', " +
                    "`installedPacksJson` TEXT NOT NULL DEFAULT '[]', " +
                    "`status` TEXT NOT NULL DEFAULT 'UNINITIALIZED', " +
                    "`isDefault` INTEGER NOT NULL DEFAULT 0, " +
                    "`lastSeenAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`nodeId`))",
            )
        }
    }

    val MIGRATION_123_124 = object : Migration(123, 124) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `session_proactive_preferences` (" +
                    "`sessionId` TEXT NOT NULL, `muted` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`sessionId`), " +
                    "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_session_proactive_preferences_sessionId` " +
                    "ON `session_proactive_preferences` (`sessionId`)",
            )
        }
    }

    /**
     * M4a: 会话内运行环境安装卡。
     *
     * 只建表不动既有行。列与索引必须和 `RuntimeInstallCardEntity` 逐项一致 ——
     * 名字对不上时 Room 在升级后的 schema 校验阶段会直接报 unexpected，而不是静默通过。
     */
    val MIGRATION_131_132 = object : Migration(131, 132) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `runtime_install_cards` (" +
                    "`messageId` TEXT NOT NULL, `runtimeId` TEXT NOT NULL, `version` TEXT NOT NULL, " +
                    "`state` TEXT NOT NULL, `progress` INTEGER NOT NULL DEFAULT 0, " +
                    "`errorKind` TEXT, `dismissed` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))",
            )
        }
    }

    /**
     * Bot 标注表（表情 / 贴纸 / 引用定位）。
     *
     * 与 `messages.reaction` 分开：那一列是**用户**的反应，单值；
     * 主 Bot 的标注可能多条、可能带资源、可能指向句子。共用一列会互相覆盖。
     * 纯新增表，旧 reaction 列无损。
     */
    val MIGRATION_133_134 = object : Migration(133, 134) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_annotations` (" +
                    "`id` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`actorType` TEXT NOT NULL DEFAULT 'bot', " +
                    "`originSessionId` TEXT NOT NULL DEFAULT '', " +
                    "`targetMessageId` TEXT NOT NULL DEFAULT '', " +
                    "`targetSentenceId` TEXT NOT NULL DEFAULT '', " +
                    "`targetContentHash` TEXT NOT NULL DEFAULT '', " +
                    "`kind` TEXT NOT NULL DEFAULT 'REACTION', " +
                    "`assetRef` TEXT NOT NULL DEFAULT '', " +
                    "`status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_annotations_targetMessageId` ON `bot_annotations`(`targetMessageId`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bot_annotations_botId_originSessionId` ON `bot_annotations`(`botId`,`originSessionId`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_annotations_status` ON `bot_annotations`(`status`)")
        }
    }

    /**
     * 主动消息 backlog 的派发账。
     *
     * 纯新增表，不动 pending_thought / proactive_outcome 的任何行。thoughtId 唯一索引
     * 保证一条牵挂最多一条派发；领取与回收都按 state + 时间条件更新，这两个组合
     * 必须有索引，否则每轮巡检都是全表扫。列名与默认值逐项对齐
     * `ProactiveBacklogDispatchEntity` —— 不一致时 Room 会在升级后的校验阶段直接报错。
     */
    val MIGRATION_134_135 = object : Migration(134, 135) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `proactive_backlog_dispatch` (" +
                    "`dispatchId` TEXT NOT NULL, `thoughtId` TEXT NOT NULL DEFAULT '', " +
                    "`assistantId` TEXT NOT NULL DEFAULT '', `sessionId` TEXT NOT NULL DEFAULT '', " +
                    "`state` TEXT NOT NULL DEFAULT 'READY', `claimToken` TEXT DEFAULT NULL, " +
                    "`claimUntil` INTEGER DEFAULT NULL, `attemptCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`nextAttemptAt` INTEGER NOT NULL DEFAULT 0, `lastError` TEXT DEFAULT NULL, " +
                    "`outcomeId` TEXT DEFAULT NULL, `createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`dispatchId`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_proactive_backlog_dispatch_thoughtId` " +
                    "ON `proactive_backlog_dispatch`(`thoughtId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_proactive_backlog_dispatch_scope` " +
                    "ON `proactive_backlog_dispatch`(`assistantId`,`sessionId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_proactive_backlog_dispatch_ready` " +
                    "ON `proactive_backlog_dispatch`(`state`,`nextAttemptAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_proactive_backlog_dispatch_claim` " +
                    "ON `proactive_backlog_dispatch`(`state`,`claimUntil`)",
            )
        }
    }

    val MIGRATION_135_136 = object : Migration(135, 136) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `proactive_outcome` ADD COLUMN `notificationState` TEXT NOT NULL DEFAULT 'NONE'")
            db.execSQL("ALTER TABLE `proactive_outcome` ADD COLUMN `notificationId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `proactive_outcome` ADD COLUMN `notificationTitle` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `proactive_outcome` ADD COLUMN `notificationPreview` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `proactive_outcome` ADD COLUMN `notificationError` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `proactive_outcome` ADD COLUMN `notificationAttempts` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `proactive_outcome` ADD COLUMN `notificationNextAttemptAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_proactive_outcome_notification` " +
                    "ON `proactive_outcome`(`notificationState`,`notificationNextAttemptAt`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `proactive_backlog_schedule` (" +
                    "`id` INTEGER NOT NULL, `nextBacklogDispatchAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
        }
    }

    /**
     * Agent 交互升级：
     * - 扩充 bot_task_steps 四列（展示标题、执行态、结果摘要、负责队友），支持持久化实时时间线；
     * - 新增 bot_message_cards（对话流动态交互卡片）；
     * - 新增 bot_routines（可复用惯例与触发规则）。
     */
    val MIGRATION_136_137 = object : Migration(136, 137) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val stepCols = mutableSetOf<String>()
            db.query("PRAGMA table_info(`bot_task_steps`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) stepCols += cursor.getString(nameIdx)
                }
            }
            if ("displayLabel" !in stepCols) {
                db.execSQL("ALTER TABLE `bot_task_steps` ADD COLUMN `displayLabel` TEXT NOT NULL DEFAULT ''")
            }
            if ("status" !in stepCols) {
                db.execSQL("ALTER TABLE `bot_task_steps` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'RUNNING'")
            }
            if ("summary" !in stepCols) {
                db.execSQL("ALTER TABLE `bot_task_steps` ADD COLUMN `summary` TEXT NOT NULL DEFAULT ''")
            }
            if ("assigneeBotId" !in stepCols) {
                db.execSQL("ALTER TABLE `bot_task_steps` ADD COLUMN `assigneeBotId` TEXT NOT NULL DEFAULT ''")
            }

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_message_cards` (" +
                    "`cardId` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`originSessionId` TEXT NOT NULL DEFAULT '', `anchorMessageId` TEXT NOT NULL DEFAULT '', " +
                    "`taskId` TEXT NOT NULL DEFAULT '', `cardKind` TEXT NOT NULL DEFAULT 'CHOICE', " +
                    "`title` TEXT NOT NULL DEFAULT '', `subtitle` TEXT NOT NULL DEFAULT '', " +
                    "`payloadJson` TEXT NOT NULL DEFAULT '{}', `status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                    "`selectedOptionId` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`cardId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bot_message_cards_botId_originSessionId_createdAt` " +
                    "ON `bot_message_cards`(`botId`,`originSessionId`,`createdAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bot_message_cards_taskId` " +
                    "ON `bot_message_cards`(`taskId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bot_message_cards_anchorMessageId` " +
                    "ON `bot_message_cards`(`anchorMessageId`)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_routines` (" +
                    "`routineId` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`title` TEXT NOT NULL DEFAULT '', `goalPrompt` TEXT NOT NULL DEFAULT '', " +
                    "`stepsSummary` TEXT NOT NULL DEFAULT '', `resourceScope` TEXT NOT NULL DEFAULT '', " +
                    "`triggerType` TEXT NOT NULL DEFAULT 'MANUAL', `triggerExpr` TEXT NOT NULL DEFAULT '', " +
                    "`scheduledTaskId` TEXT NOT NULL DEFAULT '', `sourceTaskId` TEXT NOT NULL DEFAULT '', " +
                    "`skillId` TEXT NOT NULL DEFAULT '', `enabled` INTEGER NOT NULL DEFAULT 1, " +
                    "`lastRunAt` INTEGER NOT NULL DEFAULT 0, `lastRunStatus` TEXT NOT NULL DEFAULT '', " +
                    "`runCount` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`routineId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bot_routines_botId_enabled` " +
                    "ON `bot_routines`(`botId`,`enabled`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bot_routines_triggerType_enabled` " +
                    "ON `bot_routines`(`triggerType`,`enabled`)",
            )
        }
    }

    /**
     * 思考过程耗时持久化：为单聊/Agent 共用的 messages 表与群聊 group_chat_messages 表
     * 新增 reasoningDurationMs（可空毫秒，旧数据默认 NULL）。
     */
    val MIGRATION_137_138 = object : Migration(137, 138) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addNullableIntColumnIfMissing(db, "messages", "reasoningDurationMs")
            addNullableIntColumnIfMissing(db, "group_chat_messages", "reasoningDurationMs")
        }

        private fun addNullableIntColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String) {
            val cols = mutableSetOf<String>()
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) cols += cursor.getString(nameIdx)
                }
            }
            if (column !in cols) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` INTEGER DEFAULT NULL")
            }
        }
    }

    /**
     * Agent 中枢七表。
     *
     * 纯新增,不动任何既有行:中枢功能关着时这七张表就是空的,普通对话
     * 完全不受影响。列名与默认值必须逐项对齐各 Entity —— 名字不一致时
     * Room 会在升级后的 schema 校验阶段直接报 unexpected,而不是静默通过。
     */
    val MIGRATION_132_133 = object : Migration(132, 133) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_tasks` (" +
                    "`taskId` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`originSessionId` TEXT NOT NULL DEFAULT '', " +
                    "`requestMessageId` TEXT NOT NULL DEFAULT '', " +
                    "`goal` TEXT NOT NULL DEFAULT '', `acceptanceCriteria` TEXT NOT NULL DEFAULT '', " +
                    "`resourceScope` TEXT NOT NULL DEFAULT '', `permissionScopeId` TEXT NOT NULL DEFAULT '', " +
                    "`revision` INTEGER NOT NULL DEFAULT 1, " +
                    "`status` TEXT NOT NULL DEFAULT 'QUEUED', `priority` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`taskId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_tasks_botId_status` ON `bot_tasks`(`botId`,`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_tasks_originSessionId` ON `bot_tasks`(`originSessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_tasks_updatedAt` ON `bot_tasks`(`updatedAt`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_task_runs` (" +
                    "`runId` TEXT NOT NULL, `taskId` TEXT NOT NULL DEFAULT '', " +
                    "`attempt` INTEGER NOT NULL DEFAULT 1, `threadId` TEXT NOT NULL DEFAULT '', " +
                    "`taskRevision` INTEGER NOT NULL DEFAULT 1, " +
                    "`status` TEXT NOT NULL DEFAULT 'QUEUED', `leaseOwner` TEXT NOT NULL DEFAULT '', " +
                    "`leaseUntil` INTEGER NOT NULL DEFAULT 0, `fenceToken` INTEGER NOT NULL DEFAULT 0, " +
                    "`cancelRequestedAt` INTEGER NOT NULL DEFAULT 0, `startedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`finishedAt` INTEGER NOT NULL DEFAULT 0, `checkpointRef` TEXT NOT NULL DEFAULT '', " +
                    "`errorCode` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`runId`))",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bot_task_runs_taskId_attempt` ON `bot_task_runs`(`taskId`,`attempt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_task_runs_status` ON `bot_task_runs`(`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_task_runs_leaseUntil` ON `bot_task_runs`(`leaseUntil`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_task_steps` (" +
                    "`stepId` TEXT NOT NULL, `runId` TEXT NOT NULL DEFAULT '', " +
                    "`stepIndex` INTEGER NOT NULL DEFAULT 0, `toolName` TEXT NOT NULL DEFAULT '', " +
                    "`argumentsHash` TEXT NOT NULL DEFAULT '', `baseHash` TEXT NOT NULL DEFAULT '', " +
                    "`approvalId` TEXT NOT NULL DEFAULT '', " +
                    "`sideEffect` TEXT NOT NULL DEFAULT 'READ_ONLY', " +
                    "`startedAt` INTEGER NOT NULL DEFAULT 0, `finishedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`stepId`))",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bot_task_steps_runId_stepIndex` ON `bot_task_steps`(`runId`,`stepIndex`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_task_steps_approvalId` ON `bot_task_steps`(`approvalId`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_events` (" +
                    "`eventId` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`originSessionId` TEXT NOT NULL DEFAULT '', `taskId` TEXT NOT NULL DEFAULT '', " +
                    "`runId` TEXT NOT NULL DEFAULT '', `kind` TEXT NOT NULL DEFAULT '', " +
                    "`sequence` INTEGER NOT NULL DEFAULT 0, `payloadRef` TEXT NOT NULL DEFAULT '', " +
                    "`dedupeKey` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`consumedAt` INTEGER NOT NULL DEFAULT 0, `leaseOwner` TEXT NOT NULL DEFAULT '', " +
                    "`leaseUntil` INTEGER NOT NULL DEFAULT 0, `fenceToken` INTEGER NOT NULL DEFAULT 0, " +
                    "`schemaVersion` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`eventId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_events_botId_sequence` ON `bot_events`(`botId`,`sequence`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_events_taskId` ON `bot_events`(`taskId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bot_events_dedupeKey` ON `bot_events`(`dedupeKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_events_consumedAt` ON `bot_events`(`consumedAt`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_approvals` (" +
                    "`approvalId` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`originSessionId` TEXT NOT NULL DEFAULT '', `taskId` TEXT NOT NULL DEFAULT '', " +
                    "`runId` TEXT NOT NULL DEFAULT '', `stepId` TEXT NOT NULL DEFAULT '', " +
                    "`taskRevision` INTEGER NOT NULL DEFAULT 1, `toolCallId` TEXT NOT NULL DEFAULT '', " +
                    "`toolName` TEXT NOT NULL DEFAULT '', `argumentsRef` TEXT NOT NULL DEFAULT '', " +
                    "`argumentsHash` TEXT NOT NULL DEFAULT '', `resourceVersion` TEXT NOT NULL DEFAULT '', " +
                    "`risk` TEXT NOT NULL DEFAULT '', `status` TEXT NOT NULL DEFAULT 'PENDING', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, `readyAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`expiresAt` INTEGER NOT NULL DEFAULT 0, `resolvedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`decisionActor` TEXT NOT NULL DEFAULT '', `decisionReason` TEXT NOT NULL DEFAULT '', " +
                    "PRIMARY KEY(`approvalId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_approvals_botId_status` ON `bot_approvals`(`botId`,`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_approvals_taskId` ON `bot_approvals`(`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_approvals_expiresAt` ON `bot_approvals`(`expiresAt`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_publications` (" +
                    "`id` TEXT NOT NULL, `publicationId` TEXT NOT NULL DEFAULT '', " +
                    "`botId` TEXT NOT NULL DEFAULT '', `originSessionId` TEXT NOT NULL DEFAULT '', " +
                    "`causeEventIds` TEXT NOT NULL DEFAULT '', `requestRevision` INTEGER NOT NULL DEFAULT 0, " +
                    "`taskRevision` INTEGER NOT NULL DEFAULT 1, `itemIndex` INTEGER NOT NULL DEFAULT 0, " +
                    "`kind` TEXT NOT NULL DEFAULT 'TEXT', `payloadRef` TEXT NOT NULL DEFAULT '', " +
                    "`status` TEXT NOT NULL DEFAULT 'PENDING', `messageId` TEXT NOT NULL DEFAULT '', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_bot_publications_publicationId_itemIndex` " +
                    "ON `bot_publications`(`publicationId`,`itemIndex`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_publications_botId_status` ON `bot_publications`(`botId`,`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_publications_messageId` ON `bot_publications`(`messageId`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_bindings` (" +
                    "`botId` TEXT NOT NULL, `assistantId` TEXT NOT NULL DEFAULT '', " +
                    "`canonicalAgentSessionId` TEXT NOT NULL DEFAULT '', " +
                    "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                    "`schemaVersion` INTEGER NOT NULL DEFAULT 1, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`botId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bot_bindings_canonicalAgentSessionId` ON `bot_bindings`(`canonicalAgentSessionId`)",
            )
        }
    }

    /**
     * v2.7.x(E2): 会话术语表。
     *
     * 只建表,不动任何既有行。列名与默认值逐项对齐 `SessionTermEntity`,
     * 索引名沿用 Room 的命名规则(`index_<表>_<列...>`)—— 名字不一致时
     * Room 会在升级后校验阶段报"unexpected index",而不是安静通过。
     */
    val MIGRATION_130_131 = object : Migration(130, 131) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 置顶记忆注入独立开关：默认 1（保持现有行为，升级用户不会突然失去置顶注入）。
            db.execSQL(
                "ALTER TABLE `assistants` ADD COLUMN `enablePinnedInjection` INTEGER NOT NULL DEFAULT 1",
            )
        }
    }

    val MIGRATION_124_125 = object : Migration(124, 125) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `session_terms` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`term` TEXT NOT NULL, `meaning` TEXT, " +
                    "`session_id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, " +
                    "`scope` TEXT NOT NULL DEFAULT 'session', " +
                    "`occurrences` INTEGER NOT NULL DEFAULT 1, " +
                    "`confirmed` INTEGER NOT NULL DEFAULT 0, " +
                    "`first_seen_at` INTEGER NOT NULL, `last_seen_at` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_terms_assistant_id` ON `session_terms` (`assistant_id`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_session_terms_scope_session_id_term` " +
                    "ON `session_terms` (`scope`, `session_id`, `term`)",
            )
        }
    }

    val MIGRATION_117_118 = object : Migration(117, 118) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `card_entities` (" +
                    "`id` TEXT NOT NULL, " +
                    "`legacyCardId` TEXT DEFAULT NULL, " +
                    "`originSessionId` TEXT DEFAULT NULL, " +
                    "`originMessageId` TEXT DEFAULT NULL, " +
                    "`title` TEXT NOT NULL DEFAULT '', " +
                    "`code` TEXT NOT NULL DEFAULT '', " +
                    "`isSvg` INTEGER NOT NULL DEFAULT 0, " +
                    "`dataJson` TEXT DEFAULT NULL, " +
                    "`dockSlot` INTEGER DEFAULT NULL, " +
                    "`dockedAt` INTEGER DEFAULT NULL, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_card_entities_dockSlot` ON `card_entities` (`dockSlot`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_card_entities_originMessageId` ON `card_entities` (`originMessageId`)",
            )
        }
    }

    /**
     * v2.6.7（P2-4）: 114 → 115 评论支持嵌套回复。
     *
     * 旧数据全为 NULL，语义等同“顶层评论”，无需回填。
     */
    val MIGRATION_114_115 = object : Migration(114, 115) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE ai_moment_comments ADD COLUMN parentCommentId TEXT DEFAULT NULL")
        }
    }

    /**
     * Phase 8.2: v1 → v2 迁移.
     * - 新建 assistants 表
     * - sessions 加 assistantId(默认 'default') + pinned(默认 0)字段
     * - 插入默认 Assistant(id='default')
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 新建 assistants 表
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS assistants (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    sortIndex INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0,
                    modelId TEXT,
                    temperature REAL,
                    topP REAL,
                    maxTokens INTEGER,
                    contextMessageSize INTEGER NOT NULL DEFAULT 20,
                    reasoningLevel TEXT NOT NULL DEFAULT 'AUTO',
                    systemPrompt TEXT NOT NULL DEFAULT '',
                    messageTemplate TEXT NOT NULL DEFAULT '',
                    presetMessagesJson TEXT NOT NULL DEFAULT '[]',
                    toolIdsJson TEXT NOT NULL DEFAULT '[]',
                    mcpServerIdsJson TEXT NOT NULL DEFAULT '[]',
                    streamOutput INTEGER NOT NULL DEFAULT 1,
                    memoryEnabled INTEGER NOT NULL DEFAULT 1,
                    useGlobalMemory INTEGER NOT NULL DEFAULT 1,
                    enableRecentChatsReference INTEGER NOT NULL DEFAULT 1,
                    enableTimeReminder INTEGER NOT NULL DEFAULT 1,
                    avatarEmoji TEXT NOT NULL DEFAULT '',
                    avatarImageUrl TEXT NOT NULL DEFAULT '',
                    backgroundUrl TEXT NOT NULL DEFAULT '',
                    backgroundOpacity REAL NOT NULL DEFAULT 1.0,
                    useGradientBackground INTEGER NOT NULL DEFAULT 0,
                    tagsJson TEXT NOT NULL DEFAULT '[]',
                    quickMessageIdsJson TEXT NOT NULL DEFAULT '[]',
                    lorebookIdsJson TEXT NOT NULL DEFAULT '[]',
                    modeInjectionIdsJson TEXT NOT NULL DEFAULT '[]',
                    skillIdsJson TEXT NOT NULL DEFAULT '[]',
                    customHeadersJson TEXT NOT NULL DEFAULT '{}',
                    customBodiesJson TEXT NOT NULL DEFAULT '{}',
                    capabilitiesJson TEXT NOT NULL DEFAULT '[]'
                )
                """.trimIndent(),
            )
            // 插入默认 Assistant
            // Android 16 (SDK 36) 起禁止 SQLiteDatabase.execSQL 执行 DML,只允许 DDL;
            // 改用 compileStatement + executeInsert(SQLiteStatement 方法,Android 16 仍允许)
            db.compileStatement(
                """
                INSERT OR IGNORE INTO assistants (id, name, sortIndex, createdAt, updatedAt)
                VALUES ('default', '默认助手', 0, 0, 0)
                """.trimIndent(),
            ).use { it.executeInsert() }
            // sessions 加字段
            db.execSQL("ALTER TABLE sessions ADD COLUMN assistantId TEXT NOT NULL DEFAULT 'default'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Phase 8.3: v2 → v3 迁移。
     * - messages 加 favorite 字段(默认 0)
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Phase 8.4: v3 → v4 迁移。
     * - messages 加 citationUrlsJson 字段(默认 "[]",JSON 字符串)
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN citationUrlsJson TEXT NOT NULL DEFAULT '[]'")
        }
    }

    /**
     * Phase 8.5: v4 → v5 迁移。
     * - 新建 lorebooks 表(关键词触发的世界书)
     * - 新建 quick_messages 表(快捷消息模板)
     * - 新建 prompt_injections 表(模式注入提示词)
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS lorebooks (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    keywordsJson TEXT NOT NULL DEFAULT '[]',
                    content TEXT NOT NULL DEFAULT '',
                    priority INTEGER NOT NULL DEFAULT 0,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    caseSensitive INTEGER NOT NULL DEFAULT 0,
                    insertionPosition TEXT NOT NULL DEFAULT 'after_system',
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS quick_messages (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    scope TEXT NOT NULL DEFAULT 'global',
                    assistantId TEXT NOT NULL DEFAULT '',
                    sortIndex INTEGER NOT NULL DEFAULT 0,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS prompt_injections (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    displayName TEXT NOT NULL DEFAULT '',
                    content TEXT NOT NULL DEFAULT '',
                    priority INTEGER NOT NULL DEFAULT 0,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    insertionPosition TEXT NOT NULL DEFAULT 'after_system',
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * Phase 8.6: v5 → v6 迁移。
     * - messages 加 imageBase64Json 字段(默认 "[]",JSON 字符串)
     *   用于多模态:USER 发图(本地图片 base64)/ Gemini 绘图输出(ASSISTANT)
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN imageBase64Json TEXT NOT NULL DEFAULT '[]'")
        }
    }

    /**
     * Phase 8.8: v6 → v7 迁移。
     * - 新建 skills 表(Kotlin 直实现 skill,不用 QuickJS)
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS skills (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    parametersJson TEXT NOT NULL DEFAULT '{}',
                    requiredJson TEXT NOT NULL DEFAULT '[]',
                    implementationKotlin TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    category TEXT NOT NULL DEFAULT 'custom',
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * Phase 9.1 (M13): v7 → v8 迁移。
     * - 新建 folders 表(会话文件夹分组)
     * - sessions 加 folderId 字段(默认 '',与 SessionEntity @ColumnInfo(defaultValue="") 对齐)
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS folders (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    sortIndex INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0,
                    expanded INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            // sessions 加 folderId 字段(默认 '',与 SessionEntity @ColumnInfo(defaultValue="") 对齐)
            db.execSQL("ALTER TABLE sessions ADD COLUMN folderId TEXT DEFAULT ''")
        }
    }

    /**
     * Phase 10.3: v8 → v9 迁移。
     * - 新建 messages_fts FTS4 虚拟表(中文 ngram 全文索引)
     *
     * 注意:
     * - SQL 必须与 @Fts4 注解生成的完全一致(含反引号 + 列顺序),否则 Room schema 校验失败。
     * - 迁移只建空表,不填数据。首次启动由 SessionRepository.ensureFtsIndexConsistent
     *   比较两表 count 后全量 rebuild(用 MessageFtsManager.toNgram 转换)。
     * - 不在迁移里 INSERT SELECT: SQL 无法调用 Kotlin ngram 函数,直接塞原文会导致
     *   索引/查询不一致(MATCH 不到)。rebuild 必须在 Kotlin 层做。
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(" +
                    "`message_id` TEXT, `content_ngram` TEXT" +
                    ")",
            )
        }
    }

    /**
     * v9 → v10 迁移。
     * - 新建 scheduled_tasks 表(定时任务)
     * - 新建 knowledge_docs 表(知识库文档)
     *
     * 注意: skills 表已在 v6→v7 迁移中创建,此处不重复。
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // scheduled_tasks 表(SQL 必须与 ScheduledTaskEntity @Entity 注解生成的完全一致)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scheduled_tasks (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    prompt TEXT NOT NULL DEFAULT '',
                    assistant_id TEXT NOT NULL DEFAULT 'default',
                    interval TEXT NOT NULL DEFAULT 'daily',
                    cron_expr TEXT NOT NULL DEFAULT '',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    next_run_at INTEGER NOT NULL DEFAULT 0,
                    last_run_at INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            // knowledge_docs 表
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_docs (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    file_path TEXT NOT NULL DEFAULT '',
                    file_type TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * P1-7: v10 → v11 迁移。
     * - 新建 scheduled_task_executions 表(定时任务执行历史)
     *
     * 注意: 迁移链已覆盖标准升级路径;未知版本或降级由备份恢复流程处理，不执行 destructive migration。
     * 此迁移仅用于 v10 → v11 的标准升级路径,DDL 用 execSQL(Room 迁移标准做法)。
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // scheduled_task_executions 表(SQL 必须与 ScheduledTaskExecutionEntity @Entity 注解生成的完全一致)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scheduled_task_executions (
                    id TEXT NOT NULL PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    executed_at INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'success',
                    reply_summary TEXT NOT NULL DEFAULT '',
                    error_message TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            // 索引: 按 task_id 查询执行历史(对应 @Index 注解)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_task_executions_task_id ON scheduled_task_executions(task_id)")
        }
    }

    /**
     * v0.45: v11 → v12 迁移。
     * - sessions 加 archived 字段(默认 0,归档功能)
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v1.28: v12 → v13 迁移。
     * - sessions 加 isAgentSession 字段(默认 0,区分 Agent Tab 会话与任务会话)
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN isAgentSession INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v1.30: v13 → v14 迁移。
     * - 新建 group_chats 表(多 Agent 群聊元数据)
     * - 新建 group_chat_messages 表(群聊消息)
     *
     * 群聊功能:用户在群聊中发消息后,GroupChatScheduler 串行触发各 Agent 成员轮转发言。
     * 迁移链已覆盖标准升级路径;未知版本或降级由备份恢复流程处理，不执行 destructive migration。
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // group_chats 表(SQL 必须与 GroupChatEntity @Entity 注解生成的完全一致)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_chats (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    memberIdsJson TEXT NOT NULL,
                    teamId TEXT,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            // group_chat_messages 表(SQL 必须与 GroupChatMessageEntity @Entity 注解生成的完全一致)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_chat_messages (
                    id TEXT NOT NULL PRIMARY KEY,
                    chatId TEXT NOT NULL,
                    senderType TEXT NOT NULL,
                    senderId TEXT NOT NULL,
                    senderName TEXT NOT NULL,
                    body TEXT NOT NULL,
                    timestamp INTEGER NOT NULL DEFAULT 0,
                    mood TEXT
                )
                """.trimIndent(),
            )
            // 索引: 按 chatId 查询消息(对应 @Index 注解)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_messages_chatId ON group_chat_messages(chatId)")
        }
    }

    /**
     * v1.41: v14 → v15 迁移。
     * - group_chat_messages 加 imageBase64Json 字段(默认 "[]",JSON 字符串)
     *   用于群聊多模态:用户发送图片附件。
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN imageBase64Json TEXT NOT NULL DEFAULT '[]'")
        }
    }

    /**
     * v1.43: v15 → v16 迁移。
     * - 新建 artifacts 表(会话产物:代码/文档/HTML/SVG/图片等)
     * - messages 加 artifactIdsJson 字段(默认 "[]",JSON 字符串)
     */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS artifacts (
                    id TEXT NOT NULL PRIMARY KEY,
                    sessionId TEXT NOT NULL,
                    messageId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    type TEXT NOT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    language TEXT,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_sessionId ON artifacts(sessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_messageId ON artifacts(messageId)")
            db.execSQL("ALTER TABLE messages ADD COLUMN artifactIdsJson TEXT NOT NULL DEFAULT '[]'")
        }
    }

    /**
     * v1.44: v16 → v17 迁移。
     * - group_chats 加 pinned 字段(默认 0),支持群聊置顶
     *
     * v1.46: v17 → v18 迁移。
     * - group_chat_messages 加 reasoning 字段(可选),支持群聊消息思考过程展示
     */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE group_chats ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v1.46: v17 → v18 迁移。
     * - group_chat_messages 加 reasoning 字段(可选),支持群聊消息思考过程展示
     */
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN reasoning TEXT")
        }
    }

    /**
     * v1.49: v18 → v19 迁移。
     *
     * 修复:旧版 MIGRATION_6_7 建表语句可能与当前 SkillEntity schema 不一致
     * (category 列缺失或 defaultValue 不匹配),导致 Room schema 校验崩溃
     * "Migration didn't properly handle: skills"。
     *
     * 方案:重建 skills 表,确保 schema 与当前 SkillEntity 完全一致。
     * - CREATE TABLE skills_new(完整 schema)
     * - INSERT INTO skills_new SELECT ... FROM skills(用 COALESCE 补缺失列)
     * - DROP TABLE skills
     * - ALTER TABLE skills_new RENAME TO skills
     *
     * Android 16 (SDK 36) 限制:execSQL 禁止 DML(INSERT/UPDATE/DELETE),
     * 数据迁移用 db.query() 执行 INSERT INTO ... SELECT(DDL 用 execSQL)。
     */
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 建新表(DDL,execSQL 可用)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS skills_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    parametersJson TEXT NOT NULL DEFAULT '{}',
                    requiredJson TEXT NOT NULL DEFAULT '[]',
                    implementationKotlin TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    category TEXT NOT NULL DEFAULT 'custom',
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            // 2. 数据迁移(DML,Android 16 必须用 query() 而非 execSQL)
            //    MIGRATION_6_7 建表时已包含 category/createdAt/updatedAt 列,直接 INSERT 即可。
            //    若旧表异常缺列,放弃迁移(技能可重新导入)。
            //    (原 catch 分支用 COALESCE 补缺列是死代码:SQLite 对不存在的列在解析
            //    阶段即报错,COALESCE 无法补救,故移除。)
            try {
                db.query(
                    """
                    INSERT INTO skills_new
                        (id, name, description, parametersJson, requiredJson,
                         implementationKotlin, enabled, category, createdAt, updatedAt)
                    SELECT
                        id, name, description, parametersJson, requiredJson,
                        implementationKotlin, enabled, category, createdAt, updatedAt
                    FROM skills
                    """.trimIndent(),
                ).use { /* 执行 INSERT,关闭 Cursor */ }
            } catch (e: RuntimeException) {
                // 旧表异常:放弃数据迁移,skills 表将重建为空(内置 skill 会在启动时重新写入)
                // v1.71: 记录日志,便于排查用户 skill 丢失问题
                Logger.w("MuseDb", "skills 表迁移失败,用户自定义 skill 将丢失", e)
            }
            // 3. 替换旧表(DDL)
            db.execSQL("DROP TABLE skills")
            db.execSQL("ALTER TABLE skills_new RENAME TO skills")
        }
    }

    /**
     * v1.53: MIGRATION_19_20 — 为 messages 表补索引,优化长会话查询性能。
     *
     * 新增两个复合索引(DDL,execSQL 可用):
     *  - index_messages_sessionId_createdAt:覆盖 observeBySession(ORDER BY createdAt)
     *    + deleteFromCreatedAt(WHERE sessionId AND createdAt >= ?)
     *  - index_messages_sessionId_createdAt_role:覆盖带 role 过饰的查询
     *
     * 原 v19 仅有 index_messages_sessionId 单列索引,长会话(几千条)下
     * ORDER BY createdAt 需 filesort,补复合索引后可走索引顺序扫描。
     */
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt ON messages(sessionId, createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt_role ON messages(sessionId, createdAt, role)")
        }
    }

    /**
     * v1.54: MIGRATION_20_21 — RAG 体系化:知识库分块表 + 文档索引追踪字段。
     *
     * 1. 新建 knowledge_chunks 表(存分块文本 + embedding 向量 JSON)
     * 2. knowledge_docs 加 chunk_count + embedding_model 字段(ALTER TABLE ADD COLUMN,DDL)
     *
     * embedding 以 JSON 浮点数组字符串存储(如 "[0.012, -0.034, ...]"),
     * 检索时一次性加载到内存做余弦相似度遍历(规模 <1 万 chunk 性能足够)。
     */
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 新建 knowledge_chunks 表
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_chunks (
                    id TEXT NOT NULL PRIMARY KEY,
                    doc_id TEXT NOT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    embedding TEXT NOT NULL DEFAULT '',
                    chunk_index INTEGER NOT NULL DEFAULT 0,
                    token_count INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunks_doc_id ON knowledge_chunks(doc_id)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_knowledge_chunks_doc_id_chunk_index ON knowledge_chunks(doc_id, chunk_index)",
            )
            // 2. knowledge_docs 加索引追踪字段(ALTER TABLE ADD COLUMN 是 DDL,execSQL 可用)
            db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN chunk_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN embedding_model TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * v1.81: MIGRATION_21_22 — 为 messages.role 和 scheduled_tasks(enabled, next_run_at) 补索引。
     *
     * 对应阶段3(M-SESS5)和阶段4(M-SC1)新增的 @Index 注解:
     *  - index_messages_role: 加速 WHERE role=? 无 sessionId 前缀的查询
     *  - index_scheduled_tasks_enabled_next_run_at: 覆盖 getDueTasks 的 WHERE enabled AND next_run_at <= ? 查询
     *
     * DDL(CREATE INDEX),Android 16 允许 execSQL 执行。
     */
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_role ON messages(role)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_tasks_enabled_next_run_at ON scheduled_tasks(enabled, next_run_at)")
        }
    }

    /**
     * v1.82: MIGRATION_22_23 — 为 prompt_injections 和 quick_messages 补索引。
     *
     * 对应阶段4(M-PID1/M-QM1)新增的 @Index 注解:
     *  - index_prompt_injections_mode_enabled: 加速 getEnabledByMode(WHERE mode=? AND enabled=1)
     *  - index_quick_messages_scope_assistantId_enabled: 加速 observeForAssistant
     *    (WHERE scope='global' OR (scope='assistant' AND assistantId=?)) AND enabled=1)
     *
     * DDL(CREATE INDEX),Android 16 允许 execSQL 执行。
     */
    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_prompt_injections_mode_enabled ON prompt_injections(mode, enabled)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "index_quick_messages_scope_assistantId_enabled " +
                    "ON quick_messages(scope, assistantId, enabled)",
            )
        }
    }

    /**
     * v1.83: MIGRATION_23_24 — 为 skills 和 knowledge_docs 补索引。
     *
     * 对应第5步(M-SD1/L-KE1)新增的 @Index 注解:
     *  - index_skills_enabled: 加速 listEnabled(WHERE enabled=1)
     *  - index_skills_category: 加速 observeAll(ORDER BY category ASC)
     *  - index_knowledge_docs_updated_at: 加速 observeAll(ORDER BY updated_at DESC)
     */
    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_enabled ON skills(enabled)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_category ON skills(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_docs_updated_at ON knowledge_docs(updated_at)")
        }
    }

    /**
     * v1.85: MIGRATION_24_25 — 修复 skills 表 implementationKotlin 列缺失 DEFAULT ''。
     *
     * 问题:第5步(v1.83)给 SkillEntity.implementationKotlin 加了 @ColumnInfo(defaultValue = ""),
     * 但 MIGRATION_6_7 / MIGRATION_18_19 建表时 implementationKotlin TEXT NOT NULL 无 DEFAULT,
     * MIGRATION_23_24 只加索引未重建表,导致 Room schema 校验崩溃
     * "Migration didn't properly handle: skills"。
     *
     * 方案:重建 skills 表,给 implementationKotlin 加 DEFAULT ''。
     * - CREATE TABLE skills_new(完整 schema,implementationKotlin 加 DEFAULT '')
     * - INSERT INTO skills_new SELECT ... FROM skills
     * - DROP TABLE skills(索引随表一起删除)
     * - ALTER TABLE skills_new RENAME TO skills
     * - 重建索引(index_skills_enabled, index_skills_category)
     */
    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 建新表(DDL,implementationKotlin 加 DEFAULT '')
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS skills_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    parametersJson TEXT NOT NULL DEFAULT '{}',
                    requiredJson TEXT NOT NULL DEFAULT '[]',
                    implementationKotlin TEXT NOT NULL DEFAULT '',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    category TEXT NOT NULL DEFAULT 'custom',
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            // 2. 数据迁移(DML,Android 16 必须用 query() 而非 execSQL)
            try {
                db.query(
                    """
                    INSERT INTO skills_new
                        (id, name, description, parametersJson, requiredJson,
                         implementationKotlin, enabled, category, createdAt, updatedAt)
                    SELECT
                        id, name, description, parametersJson, requiredJson,
                        implementationKotlin, enabled, category, createdAt, updatedAt
                    FROM skills
                    """.trimIndent(),
                ).use { /* 执行 INSERT,关闭 Cursor */ }
            } catch (e: RuntimeException) {
                // 旧表异常:放弃数据迁移,skills 表将重建为空(内置 skill 会在启动时重新写入)
                Logger.w("MuseDb", "skills 表迁移失败,用户自定义 skill 将丢失", e)
            }
            // 3. 替换旧表(DDL)
            db.execSQL("DROP TABLE skills")
            db.execSQL("ALTER TABLE skills_new RENAME TO skills")
            // 4. 重建索引(MIGRATION_23_24 加的索引随 DROP TABLE 一起删除了)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_enabled ON skills(enabled)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_category ON skills(category)")
        }
    }

    /**
     * v1.97: MIGRATION_25_26 — 为 assistants 表加 regexRulesJson 字段(正则替换规则)。
     *
     * 新字段默认值 '[]'(空 JSON 数组),与 AssistantEntity.regexRulesJson 的 @ColumnInfo(defaultValue) 对齐。
     * 旧版助手迁移后无规则,行为与原版一致(无替换)。
     */
    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE assistants ADD COLUMN regexRulesJson TEXT NOT NULL DEFAULT '[]'",
            )
        }
    }

    /**
     * v1.98: MIGRATION_26_27 — 新增 experiences 表(经验库)。
     *
     * 存储用户在对话中积累的经验性知识(最佳实践/踩坑教训/工作流),
     * 与普通记忆(fact)区分:fact 记录"用户是谁",experience 记录"如何做某事"。
     * 当 experienceEnabled=true 时注入到 system prompt 供 AI 参考。
     */
    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS experiences (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    category TEXT NOT NULL DEFAULT '通用',
                    tagsJson TEXT NOT NULL DEFAULT '[]',
                    source TEXT NOT NULL DEFAULT 'manual',
                    sessionId TEXT,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * v1.103: MIGRATION_27_28 — 为 messages 表补齐 mood / reflection 列。
     *
     * 之前 MessageEntity 缺这两个字段,任务会话/Agent 聊天(用 messages 表)
     * 落盘时 mood/reflection 被丢弃,切页/重载后 MOOD 卡片消失。
     * 群聊表(group_chat_messages)在 MIGRATION_13_14 建表时已含 mood 列,
     * 本次只补 messages 表。reflection 列两表此前都没有,一并补上。
     */
    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v1.105 修复:加 DEFAULT NULL,与 Entity @ColumnInfo(defaultValue="NULL") 对齐
            // (原 "ALTER TABLE ADD COLUMN mood TEXT" 无 DEFAULT,SQLite dflt_value=null,
            //  Room 期望 "NULL",验证不匹配崩溃)
            db.execSQL("ALTER TABLE messages ADD COLUMN mood TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE messages ADD COLUMN reflection TEXT DEFAULT NULL")
        }
    }

    /**
     * v1.104 U7: MIGRATION_28_29 — 为 messages 表加 favoriteTag 列(收藏分组标签)。
     *
     * NULL 表示未分组;旧用户升级后所有现有收藏的 favoriteTag 都为 NULL,
     * UI 上归入"全部"和"未分组"两个 chip 都能看到。
     */
    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v1.105 修复:加 DEFAULT NULL,与 Entity @ColumnInfo(defaultValue="NULL") 对齐
            db.execSQL("ALTER TABLE messages ADD COLUMN favoriteTag TEXT DEFAULT NULL")
        }
    }

    /**
     * v1.106: MIGRATION_29_30 — 重建 messages 表,修复历史 ALTER TABLE 未带 DEFAULT NULL
     * 导致的 schema 验证崩溃。
     *
     * 背景:MIGRATION_27_28(mood/reflection)和 MIGRATION_28_29(favoriteTag)原用
     * "ALTER TABLE ADD COLUMN x TEXT" 无 DEFAULT 子句,SQLite 的 PRAGMA table_info
     * 对这些列返回 dflt_value=null;而 Entity 声明 @ColumnInfo(defaultValue="NULL"),
     * Room 期望 dflt_value="NULL"。两者不匹配 → IllegalStateException 崩溃。
     *
     * 本 migration 用"建新表 + 复制数据 + 替换"重建 messages 表,确保三列都有
     * DEFAULT NULL。已升级到 v29 的崩溃用户走此 migration 修复。
     * MIGRATION_27_28/28_29 已同步加 DEFAULT NULL,未来升级用户无需走 29_30。
     */
    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 建新表(列定义与 onCreate 完全一致,含 DEFAULT NULL)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `messages_new` (
                    `id` TEXT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `role` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `reasoning` TEXT,
                    `modelId` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `imageUrlsJson` TEXT NOT NULL DEFAULT '[]',
                    `favorite` INTEGER NOT NULL DEFAULT 0,
                    `favoriteTag` TEXT DEFAULT NULL,
                    `citationUrlsJson` TEXT NOT NULL DEFAULT '[]',
                    `imageBase64Json` TEXT NOT NULL DEFAULT '[]',
                    `artifactIdsJson` TEXT NOT NULL DEFAULT '[]',
                    `mood` TEXT DEFAULT NULL,
                    `reflection` TEXT DEFAULT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            // 2. 复制数据(列顺序与建表一致)
            db.execSQL(
                """
                INSERT INTO `messages_new` (
                    `id`,`sessionId`,`role`,`content`,`reasoning`,`modelId`,
                    `createdAt`,`imageUrlsJson`,`favorite`,`favoriteTag`,
                    `citationUrlsJson`,`imageBase64Json`,`artifactIdsJson`,
                    `mood`,`reflection`
                )
                SELECT
                    `id`,`sessionId`,`role`,`content`,`reasoning`,`modelId`,
                    `createdAt`,`imageUrlsJson`,`favorite`,`favoriteTag`,
                    `citationUrlsJson`,`imageBase64Json`,`artifactIdsJson`,
                    `mood`,`reflection`
                FROM `messages`
                """.trimIndent(),
            )
            // 3. 替换旧表
            db.execSQL("DROP TABLE `messages`")
            db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
            // 4. 重建索引(DROP TABLE 会删除所有索引)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_role` ON `messages` (`role`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt` ON `messages` (`sessionId`, `createdAt`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt_role` ON `messages` (`sessionId`, `createdAt`, `role`)",
            )
        }
    }
}
