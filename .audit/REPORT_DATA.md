# 数据层 / 备份恢复 / 迁移 审查报告

## 摘要

本报告覆盖 Muse 仓库（E:\1Project\Muse\1muse）数据层全链路：MuseDb v110 全迁移链（重点 v107→v110）、备份导出/导入（单 JSON / NDJSON / 云备份 / 持久化 recovery point）、自动备份 VACUUM INTO 快照、memory 模块 FactDb v15 / MemoryDb v5、A2A 与 Tasker 新入口的对话落库归属、diagnostic 导出、插件 Hook 持久化、以及与已修"partial-page 合并"同类的分页边界。方法：Glob/Grep/Read 逐文件验证，逐版本核对 Migration 对象 SQL 与 Entity/Room schema 声明，并与 `.audit/DEDUPE_DATA.md` 与全局已知项逐条对照。总体结论：v107→v110 三个新列（knowledge_folders 表 / knowledgeFolderScopesJson / translationsJson / configSnapshotJson）迁移 SQL 与实体声明一致、迁移链注册完整，但**备份/恢复链路对 v2.4.6 知识库文件夹表存在两处系统性遗漏（NDJSON 导入分支缺失 + recovery point 缺字段）**；A2A/Tasker 两个新入口的会话归属与恢复闸门接入存在缺陷。无 P0。

---

## 发现清单

### F1. NDJSON 恢复分支丢失 `knowledge_folders` 全部数据，且 B-34 对账失效掩盖丢失（P1，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/backup/BackupService.kt:1015-1384（`applyNdJsonStreaming` 的 `when(type)` 分发）、BackupService.kt:988（清空）、BackupService.kt:719-724 与 2782（导出侧与 meta 键存在）
- 证据：NDJSON 导入分支处理了 `knowledgeBase`（:1317）、`knowledgeDoc`（:1161）、`knowledgeChunk`（:1171），**没有 `"knowledgeFolder" ->` 分支**，也没有对应 buffer 与尾部 flush（:1406-1439 的 flush 列表中无 knowledgeFolder）。而清空阶段执行 `db.knowledgeFolderDao().deleteAll()`（:988）。导出侧正常写出行（:721-722）并计入 meta（:588 `"knowledgeFolders"` 计数；typeToMetaKey 含 `"knowledgeFolder" to "knowledgeFolders"` :2782）。
- 关键放大器：B-34 计数对账（:1007-1014 先对 `typeToMetaKey` 中的行做 `actualCounts[key]++`，再 :1388-1393 校验 `actual == expected`）**只统计行数、不校验数据是否真正入库**——`knowledgeFolder` 行会被计数但被 `when` 丢弃，对账通过，丢失完全静默。
- 影响场景：所有走 `applyNdJsonStreaming` 的恢复路径——本地流式导出导入（`exportStreaming`→`import`，BackupSection.kt:139 是 UI 唯一导出入口）、云备份恢复（`applyCloudBackupData` NDJSON 分支 :2121-2133）、NDJSON recovery point 回滚与启动续恢复（:1509-1548、:2496-2542）。恢复完成后 v2.4.6 新增的知识库文件夹（含空文件夹与文件夹树）被清空且不重建；文件夹作为"检索隔离单位"（KnowledgeFolderEntity.kt:17-37），丢失后助手 KB 作用域树显示错误。
- 建议：在 `applyNdJsonStreaming` 增加 `"knowledgeFolder"` 分支 + buffer + 尾部 flush，并在 B-34 对账中改为"实际插入数"而非"解析行数"（至少对新增类型补一条插入数断言或契约测试）。

### F2. `buildBackup()`（单 JSON 路径）漏传 `knowledgeFolders`，导致 recovery point 回滚清空知识库文件夹（P1，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/backup/BackupService.kt:2268（事务内读取了 `db.knowledgeFolderDao().getAll()`）与 :2326-2372（`Backup(...)` 构造调用**未传 `knowledgeFolders`**，字段落默认 `emptyList()`）
- 证据：`DbSnapshot` 声明了 `knowledgeFolders` 字段（:2203）且赋值正确，但局部提取列表（:2312-2316 一带）缺 `val knowledgeFolders = snap.knowledgeFolders`，`Backup(...)` 实参列表（sessions/messages/.../fileStores 共 40 项）中没有 `knowledgeFolders = ...`（对比 :2365 有 `knowledgeBases = knowledgeBases`）。
- 影响场景（两条）：
  1. **recovery point 被污染（主要）**：`applyBackup` 在导入前执行 `val preImportSnapshot = buildBackup()` 写入持久化恢复点（:2403，`RestoreStagingStore.writeRecoveryPoint`）。恢复失败回滚时 `applyBackupInternal(recovery)`（:2464）会执行 `db.knowledgeFolderDao().deleteAll()`（:2622）后插入 `recovery.knowledgeFolders`（:2663，空列表）——**一次失败回滚即清空用户全部知识库文件夹**。
  2. 单 JSON 本地导出（`export()` :250-271）产物缺该字段；虽然当前 UI 只调用 `exportStreaming`（BackupSection.kt:139），`export()` 仍是被 `applyBackup` 依赖的恢复点生产者，风险由路径 1 承接。
- 与 F1 的关系：单 JSON 导入侧（`applyBackupInternal` :2663）正确处理 knowledgeFolders，缺陷只在导出/快照生产侧；NDJSON 侧则相反（导出正确、导入缺失）。两处合并说明 v2.4.6 接线在备份四条路径（NDJSON 导出/NDJSON 导入/JSON 导出/JSON 导入）中只完成了一半×一半。
- 建议：`buildBackup()` 补传 `knowledgeFolders = snap.knowledgeFolders`；补一条"Backup 构造实参与 DbSnapshot 字段一一对应"的源码契约测试（现有 BackupEntityCoverageTest 只校验字段存在，不校验构造传参）。

### F3. 自动备份 VACUUM INTO 快照链路遗漏子助手分库 `facts_<id>.db`，与 JSON/NDJSON 备份口径不一致（P1，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/data/stats/AutoBackupHelper.kt:83（`val dbNames = listOf(ENTITY_MUSE_DB, ENTITY_MEMORY_DB, ENTITY_FACTS_DB)`）、:262-295（vacuumInto）；对照 BackupService.kt:1582-1591（`listScopedFactDbIds` 枚举 `facts_` 前缀分库）、:730-744/:2318-2320（NDJSON/单 JSON 导出均含 scopedFacts）
- 证据：每日自动备份（AutoBackupWorker.kt:55-63 → backupNow）只对三个默认库做 `VACUUM INTO` 打包；`restoreFromAutoBackup`（BackupService.kt:1928-2028）也只替换这三个文件。子助手分库 facts_&lt;assistantId&gt;.db（v8 scope 隔离，FactDb.kt:25-27；P0-10 专门补进 JSON 备份体系）完全不在快照内，也不在恢复替换范围内。
- 影响场景：依赖"从自动备份恢复"（BackupSection 一键恢复，BackupSection.kt:464）做整库回退/迁移到新环境时，子助手事实记忆停留在当前磁盘状态：若此前分库被删/损坏则无法恢复；若用户预期"恢复=回到备份时点"，分库数据违反该预期（时间点错位）。三个默认库与分库在恢复时点不一致，属于快照一致性问题而非丢文件。
- 建议：`AutoBackupHelper.backupNow` 枚举 `facts_*.db` 一并 VACUUM INTO 进 ZIP（条目名即文件名，恢复侧按存在性替换），或明确在文档/UI 声明自动备份不含分库并把分库纳入 JSON 备份唯一恢复路径。

### F4. A2A 复用应用级单例 ChatViewModel 强制切换全局会话：状态抢占、并发互踩、无会话隔离标记（P1，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/AppChatModule.kt:46-89（`single { ChatViewModel(...) }`，注释明言"应用级单例…所有页面共享同一实例"）；app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:70-88（`chatViewModel.switchSession(requested)` / `createNewSession` / `updateInput(text); send()`）
- 证据：A2A `SendMessage` 直接驱动共享 ChatViewModel：
  - 指定 sessionId → `switchSession` 强制切换全局当前会话（ChatSessionController.kt:540-660 会 detach 流式、清草稿、清审批缓存、释放/获取会话引用）；
  - 未指定 → `createNewSession`（ChatSessionController.kt:147-206）新建任务会话（无 `isAgentSession`/`isMiniPhone` 标记），A2A 轮次混入用户主会话列表，与主链路/渠道会话零隔离；
  - `handleA2aJsonRpc`（WebServer.kt:648-679）对并发 SendMessage 无串行化，两次 A2A 调用互相 `detachStreaming`/打断对方生成；与前台用户操作共享同一 `currentSessionId`、输入框、审批队列。
- 影响场景：用户在前台对话时收到 A2A 请求 → 当前会话被切走、正在进行的流式被 detach、输入草稿被 A2A 文本覆盖发送；A2A 落库会话出现在任务列表中与用户会话不可区分；指定 sessionId 时外部 agent 可向任意会话（含 Agent/小手机会话）插入对话轮次。
- 建议：A2A 改走独立会话通道（如 `isAgentSession` 专用标记或按调用方前缀命名的专用会话），或为 A2A 构造无 UI 状态的生成路径；至少对 `sendMessage` 加互斥并拒绝与前台流式并发。

### F5. Tasker 触发落库未接恢复写闸门：恢复窗口内的写入被恢复事务清空吞掉（P1，可信度：中，静态推断+代码直接证据）

- 位置：app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:84-100（`sessionRepo.appendMessage(...)` 直接落库）；grep 证实 intent/ 目录无任何 `ProcessWriteGate` 引用
- 证据：AUD-20261006 系列把全部后台写点接入 `ProcessWriteGate.restoring` 检查——ScheduledTaskRunner.kt:470/509、ProactiveMessageRunner.kt:265/1119、MomentScheduler.kt:90/99、GroupChatScheduler.kt:229/440、ChatStreamCoordinator.kt:373/423、ChannelInbox.kt:426/458 等 30+ 检查点。Tasker 是 v2.5.8 新增写点，`runTrigger` 中 `appendMessage`（USER + ASSISTANT 两条）前无任何 `restoring` 检查，`SessionRepository.appendMessage`（SessionRepository.kt:716-725）内部也不检查。
- 影响场景：JSON/NDJSON 恢复的大事务内（`applyNdJsonStreaming` :955-1440 先 `clearConversationStateForRestore` + `deleteAll` 再插入）Tasker 触发到达：其独立写事务排队后提交，消息写入被随后的清空/替换覆盖，或在文件级替换（`restoreFromAutoBackup` close→swap 窗口）中写到旧连接；结果为 Tasker 轮次静默丢失或写坏窗口。与全局已知项 2 的区别：已知项是"检查了但 volatile 非互斥的窄竞态"，本条是新入口**根本未接闸门**（覆盖缺口，非竞态边界）。
- 建议：`runTrigger` 在 LLM 调用前与两次 `appendMessage` 前检查 `ProcessWriteGate.restoring`，为 true 时跳过并返回（与其他 Runner 同模式）。

### F6. Tasker 专用会话按"标题字符串相等"复用，归属弱约定且可被用户数据意外命中（P2，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:84-94（`observeSessions().first().firstOrNull { it.title == TASKER_SESSION_TITLE }`）
- 证据：会话标题是用户可编辑自由字段（SessionDao.updateTitle :118-119；首条 user 消息还会自动按内容前 20 字命名，SessionDao.updatePreviewAndTitle :134-146）。对比同源实现 ScheduledTaskRunner 用持久化 `dedicated_session_id` 列做归属（ScheduledTaskEntity.kt:35、ScheduledTaskRunner.kt:472-483），Tasker 未复用该模式。
- 影响场景：用户把任意会话改名/自动命名为"Tasker 触发"→ Tasker 结果追加进该会话（串话）；用户重命名 Tasker 会话 → 下次触发新建第二个同名会话，历史分叉；备份恢复后同名匹配仍成立但非原会话。`observeTaskSessions`（SessionDao.kt:25-28）不过滤 isMiniPhone，匹配可命中小手机会话。
- 建议：仿照 dedicated_session_id 持久化"Tasker 会话 id"（DataStore 键或复用 ScheduledTaskEntity 机制），标题仅作展示。

### F7. 消息分页链路 deletedAt 口径不一致（与已修 partial-page 合并同类的分页边界缺陷，当前为潜伏缺陷）（P2，可信度：中）

- 位置：
  - app/src/main/java/io/zer0/muse/data/session/MessageDao.kt:86-87（`countBySession` 无 `deletedAt IS NULL`）
  - MessageDao.kt:77-83（`getOlderBySession` 上滑分页无过滤）
  - MessageDao.kt:185-186（`getLastBySession` 无过滤）
  - 对照正确实现：MessageDao.kt:46、:57（`observeRecentBySession`/`getRecentBySession` 均带 `deletedAt IS NULL`）
  - 消费方：ChatMessageController.kt:40-45（`loadMessagesPaged` 用 `countBySession` 判 `hasMoreHistory`）、SessionRepository.kt:651-653（`getOlderMessages`）、SessionRepository.kt:1244-1250（`truncateFromInternal` 用 `getLastBySession`+`countBySession` 重算预览/计数）
- 证据：当前代码库**没有任何写路径把 `messages.deletedAt` 置为非 null**（grep `deletedAt = ` 仅 QuickNote/ScheduledTask/会话级 softDelete 命中；消息级删除走物理 `deleteMessageRow` :584-608），所以三条口径差暂无实际差异。但一旦消息级软删除启用（列与 UI 通路已预留），`countBySession` 含软删会使 `hasMoreHistory` 虚高，`getOlderBySession` 捞回软删行再被 `projectMessagesForRead`（MessageProjection.kt:60）过滤 → `older.size < pageSize` → `hasMoreHistory = older.size >= messagePageSize`（ChatMessageController.kt:106）误判提前终止，**真实更早历史加载不到**——与去重基线中已修的"partial-page 合并"同构。
- 建议：三个查询统一补 `deletedAt IS NULL`（或显式注释"消息级软删未启用，口径待统一"），并在启用消息级软删的 PR 中将本条转为必改项。

### F8. 备份恢复对 `scheduled_task_executions` 用裸 `@Insert`（ABORT），重复 id 行会让整次恢复回滚（P2，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/backup/BackupService.kt:2638（`db.scheduledTaskExecutionDao().insert(it)`）、:1151-1160（NDJSON 分支同样逐条 `insert`）；ScheduledTaskExecutionDao 的 `@Insert` 无 OnConflictStrategy（默认 ABORT）
- 证据：同方法内 conversationEvents 用 IGNORE + 逐条校验返回值（:2651-2656，对应去重基线"批量恢复检查 Room IGNORE 返回值"），而 executions 未对齐该范式。备份文件手工编辑/合并或历史导出 bug 产生的重复 execution id，会使 MuseDb 大事务在中间批抛 SQLiteConstraintException → 整体回滚 → 恢复失败（有 recovery point 兜底但用户侧表现为"恢复一直失败"且无具体行提示）。
- 影响：低概率但恢复失败的诊断成本高；与既有幂等修复范式不一致。
- 建议：改 IGNORE + 冲突校验（与 conversation_events 同范式），或恢复前对备份内 execution id 去重。

### F9. 降级归档 `.bak` 直接覆盖旧归档：连续两次 destructive 只保留最后一次（P2，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/data/session/MuseDb.kt:3104-3126（`onDestructiveMigration` 中 `runCatching { if (bak.exists()) bak.delete() }` 后 `dbFile.copyTo(bak, overwrite = true)`）
- 证据：固定文件名 `muse.db.pre-destructive.bak`，每次归档先删旧再写。FactDb 侧的同类实现已用 `nextBackupFile` 递增序号避免覆盖（FactDb.kt:498-508），MuseDb 侧未对齐。
- 影响场景：测试版反复回滚/多轮 destructive 时，只剩最后一次降级前状态；第一次被销毁的数据不可追回。
- 建议：复用 `nextBackupFile` 模式保留带序号的多份归档（或至少按时间戳命名）。

### F10. A2A `CancelTask` 不校验任务存在即停止当前生成，可终止用户前台会话的流式（P2，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:130-139（`fun cancelTask(taskId: String)` 直接 `chatViewModel.stop()` 并返回 CANCELED）
- 证据：`stop()` 作用于共享 ChatViewModel 的当前生成（与 F4 同根）；`recentTasks` 校验缺失，任何持 JWT 的客户端用任意 taskId 都能触发 stop。Web 端点有 JWT 鉴权（WebServer.kt:613-618）故为授权内风险，但与 GetTask 的 `TASK_NOT_FOUND` 语义（:662-667）不一致。
- 建议：`cancelTask` 先查 `recentTasks`，未知 id 返回 TASK_NOT_FOUND；已知 id 仅在任务仍在执行时才 stop。

### F11. artifact / 记忆浏览 / 朋友圈等 UI 查询全量加载，无分页上限（P2，可信度：高，性能债）

- 位置：app/src/main/java/io/zer0/muse/data/artifact/ArtifactDao.kt:37-42（`getAll`、`observeAll` 全量）；memory/src/main/java/io/zer0/memory/fact/FactDao.kt:34-35（`getAll` 全量）、:393-401（`observeBySpace`/`getBySpace` 全量）；MomentEntity.kt:125-133 与 TranslateHistoryDao.kt:11、QuickNoteDao.kt:24（仅 `LIMIT :limit` 无 offset 续页）
- 证据：主消息链路已有 50/页窗口 + seq 锚点分页（MessageDao.kt:46-83），而产物中心、记忆中心、朋友圈、翻译历史、快捷记录仍一次性加载全部行；这些表无软删、行数随使用单调增长，长周期设备上 UI 首帧与内存随库线性劣化。与去重基线"partial-page 合并"修复点同类的其余分页点审计结论：GroupChatMessageDao 锚点分页（:58）与 AuditLogPage hasMore 判定（AuditLogPage.kt:117-130）正确，无同类边界错误。
- 建议：对 artifact/记忆/朋友圈补 offset 或 keyset 分页（可低优先级排期）。

### F12. diagnostic 导出的 debug-log 可能携带表结构/SQL 文本与堆栈，sanitizePii 不覆盖 schema 信息（P2，可信度：中，待验证假设）

- 位置：app/src/main/java/io/zer0/muse/diagnostic/DiagnosticExporter.kt:65-70（原样打包 `DebugLogStore.exportToFile()`）；common/src/main/java/io/zer0/common/Logger.kt:142-153（sanitizePii 只过滤 PII 与凭据格式，不过滤表名/列名/SQL）；common/Logger.kt:226（throwable 堆栈全文写入且不过 sanitizePii）
- 证据：诊断导出红线声明"不含消息正文与 API key"（DiagnosticExporter.kt:28）。数据库异常（Room "Migration didn't properly handle"、SQLiteException）的 message/堆栈会进 DebugLogStore（Logger.sink → DebugLogStore.log :81-95），其中含表名、列名、建表 SQL 片段；绑定参数值通常不在 SQL 文本内，**消息正文经缓存抽查路径（如 FTS probe 内容）进入异常文本的可能性未观察到**，故定级 P2。
- 验证方法：构造一次 Room 迁移失败捕获 DebugLogStore 内容，检查是否含 schema 字符串；如需收紧，对 `messageDao`/`MuseDb` tag 的堆栈在导出前做表名级脱敏或截断。
- 结论：未发现数据库**参数值/正文**泄露路径；红线在"结构信息"维度未声明边界，建议补文档或过滤。

### F13. GenerationTrace 滚动截断按字节切半，可能产生残缺 JSONL 首行（P2，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/diagnostic/GenerationTrace.kt:180-185（`file.length() > MAX_FILE_BYTES` 时 `bytes.copyOfRange(bytes.size / 2, ...)` 后整写）
- 证据：截断点为文件中点，落在某行 JSON 中间 → 导出的 `generation-trace.jsonl` 首行为残缺 JSON；离线分析脚本需逐行容错。
- 建议：从截断点后第一个换行处起写。

### F14. 迁移测试矩阵无 v107→v110 专项断言（观察项，P2，可信度：高）

- 位置：app/src/test/java/io/zer0/muse/data/session/MuseDbMigrationTest.kt:40（版本矩阵 `availableSchemaVersions() + (55..68) + 74`）、:171-230（`migrate105ToLatest` 只断言 useGlobalMemory）
- 证据：schema JSON 已导出 107/108/109/110（app/schemas/.../MuseDb/{107..110}.json 存在），105→latest 路径覆盖 105_106..109_110 全链且 Room 校验会兜住列缺失，**正确性暂无缺口**；但 knowledge_folders 表/唯一索引、translationsJson 默认值 '{}'、configSnapshotJson 默认值 NULL 均无显式断言，v2.4.6/v2.5.3 回归依赖 Room 校验失败（即真机崩溃）才暴露。
- 建议：为 105→110 补三列一表的 PRAGMA 断言（与既有 68/74/105 专项测试同风格）。

### F15. `restoreFromAutoBackup` 回滚副本只拷主库文件，不含 -wal/-shm（P2，可信度：低，理论风险）

- 位置：app/src/main/java/io/zer0/muse/backup/BackupService.kt:1969-1973（`copyToDir` 仅 `src.copyTo`）、:2049-2053
- 证据：回滚目录副本在 `db.close()` 之后创建（:1964），SQLite 最后连接关闭通常自动 checkpoint WAL，主文件应完整；但依赖该隐式行为，若 ROM 定制 SQLite 保留 WAL（PERSIST_WAL），回滚副本丢未合并帧。对照 FactDb 归档路径已显式 `wal_checkpoint(TRUNCATE)` 后处理 sidecar（FactDb.kt:389-416）。
- 建议：回滚副本前对当前库执行 `PRAGMA wal_checkpoint(TRUNCATE)`，与 FactDb 归档对齐。

---

## 已知项核对（确认未重复上报）

- **删除事实原子性 / 记忆自动保存指纹 / conversation_events 稳定 eventId 幂等 / 对话发送恢复不变量 / shadow 事件串行器 / 定时任务事务**：FactStore.applyDecay→FactDao 触发器链、recoverInterruptedGenerations（SessionRepository.kt:1072-1100）、`upsertGenerationCheckpointAndDeleteOutbox`（:992-1017）逐条复核，未发现复发；conversationEvents 恢复冲突校验仍在（BackupService.kt:2651-2656）。
- **MemoryDb 高版本降级归档（#10/#12 已修）**：MemoryModule.kt:35-51（guard 先归档 + `fallbackToDestructiveMigrationOnDowngrade(dropAllTables=true)`）符合已修语义，未报。
- **FactDb 版本守卫历史缺陷**：`archiveUnknownVersionDatabase` 已用 `FACT_DB_VERSION=15` 常量（FactDb.kt:378），误归档恢复（:430-477）在位，未报。
- **空备份判断 v4 / 恢复点流式落盘（已修）**：`hasAnyData` 覆盖全部表（BackupService.kt:229-244）、`writeRecoveryNdJson` 流式 + fsync（RestoreStagingStore.kt:45-64）、NDJSON 回滚 allowEmptyBackup（:1556）均在位，未报。
- **RAG 六轮修复**：chunk 分页下推 SQL（KnowledgeChunkDao.kt:63-115）、VectorSearchService 分页扫描边界 `page.size < BATCH_SIZE` 终止（:241-258）复核无误，未报（OFFSET 分页在 reindex 并发下的漂移为理论项，不满足"验证过的发现"门槛，不列）。
- **全局已知 1/2/3**（渠道 at-least-once、ProcessWriteGate volatile 语义、设备验收缺失）：F5 已明确与之区分（覆盖缺口非竞态边界）；未报其他。
- **全局已知 8**（MemoryDb 高版本归档无应用内浏览入口）：未发现新证据，不报。
- **安全已修项 11/12**（云备份明文拒绝、A2A 端点 JWT）：`shouldRejectPlaintextCloudBackup`（BackupService.kt:2852-2854）与 A2A `authenticate(AUTH_JWT_NAME)`（WebServer.kt:613）均在位；AgentCard 无鉴权为页面声明的设计（:606-611），未报。
- **HANDOFF P1 #20-31 数据层相关未修项**：仅当发现新证据时报；本轮未发现新增证据。
- **插件沙盒放开（v2.5.8 产品决策）**：PluginHookBridge/PluginManifest 复核中未发现实现层越权；hooks 字段 `@EncodeDefault(NEVER)` 签名兼容（PluginManifest.kt:106-114）与 `minAppVersion` 不强制阻断（:89-90）均为注释声明的设计取舍，未列为发现（仅作观察记录：新 manifest 字段在旧 app 的向前兼容依赖 kotlinx 宽松解析，建议未来新增字段时沿用 NEVER 规则）。

---

## 重点探查方向结论速览

1. **v107→v107 迁移链**：MIGRATION_107_108（knowledge_folders 建表 + 唯一索引 + assistants 列）、108_109（translationsJson DEFAULT '{}'）、109_110（configSnapshotJson DEFAULT NULL）与实体 @ColumnInfo 逐一比对一致；注册完整（MuseDb.kt:3029-3031）；105→110 全链迁移测试在位但缺 v107+ 专项断言（F14）。
2. **A2A/Tasker 落库归属**：A2A 经共享 ChatViewModel 抢占全局会话（F4）；Tasker 独立落库但标题弱匹配（F6）且未接恢复闸门（F5）。
3. **诊断导出数据库片段**：未发现正文/参数值泄露；schema/SQL 文本经 debug-log 有携带可能（F12）。
4. **插件 Hook 持久化**：hooks 声明仅存于 manifest（不落 Room），registry/config 走 JSON 文件并纳入 fileStores 白名单（BackupService.kt:1640-1649）；签名兼容规则明确；无新缺陷。
5. **分页边界**：主消息链路锚点分页健康；三处 deletedAt 口径差为潜伏缺陷（F7）；artifact/记忆/朋友圈为全量加载（F11）；GroupChat/AuditLog 分页正确。
6. **自动备份 VACUUM INTO**：三库快照正确、写闸门复查齐全；缺子助手分库（F3）。
