# 链路 3：数据层 / 备份恢复 / 迁移 — 去重基线

## 已确认修复（不得再报）
- 删除事实原子性：facts+FTS+memory_links 同一 FactDb 事务，墓碑/编译投影事务后派生。
- 记忆自动保存指纹：失败/空响应/解析失败不写指纹；指纹窗口与 MAX_MESSAGE_CHARS 一致。
- conversation_events 稳定 eventId 幂等（唯一索引）；批量恢复检查 Room IGNORE 返回值，冲突→恢复事务失败回滚。
- 对话发送恢复不变量：upsertGenerationCheckpointAndDeleteOutbox 同一 Room 事务；恢复前按 userMessageId 检查残留 checkpoint；appendMessage 幂等（不重复递增 messageCount、不刷新 preview/updatedAt）。
- shadow 事件串行器 ConversationShadowEventSequencer（session/turn/generation 串行）。
- 定时任务：执行历史/next_run_at/last_run_at/retry_count 同一事务。
- 计划任务/群聊/主动消息/朋友圈/MemoryTicker/自动备份快照的 ProcessWriteGate 写点全覆盖（AUD-20261006-* 系列）。
- MemoryDb 高版本降级：header/version 检查 → 临时目录复制 → SHA-256 → quick_check → 原子发布。
- P1 #7（硬删三条路径清图片）、#8（restore/rollback 显式清 generation_checkpoints）、#10（MemoryDb 降级归档）、#12（迁移门禁纳入 MemoryDb）已修。
- 备份 v4 文件型存储空备份判断与兼容语义；恢复点流式落盘。
- 增量向量索引 ensureIndexed（is_internal 文档，contentHash/chunkCount 判断）。
- RAG 六轮审计修复：fail-closed 内部文档隔离、流式索引失败清理+状态清零、contentHash 全入口写入、FTS 关键词兜底（chunks 优先+自愈）、注入预算=实际 snippet。

## 已知未解决（可引用编号补充新证据）
- MemoryDb 高版本归档无应用内浏览/恢复界面（已知）。
- 主知识库页与 F-31 页导入口径不一致、并入流式导入未做（已知）。
- PDF/DOCX/PPTX/XLSX/EPUB/OCR 仍受 DocumentParser 1MB/页数/ZIP entry 保护，超大结构化文档首轮索引可能不完整（已知）。
- P1 #20-31 数据层相关未修项（#20…#19 参照全局清单）。
- HNSW 持久化的维度/模型指纹与耐久性仍有残余问题（#11 部分修复：临时文件+rename 已有，剩余维度已知）。

## 本链路重点探查方向
1. **MuseDb v107→v110 迁移链**：新增实体的迁移正确性、新 DAO/索引与迁移声明一致性、check_migration_coverage 覆盖；Fresh install vs 升级路径差异。
2. **A2A 会话落库**：A2A 触发的对话轮次落到哪个会话？与主链路会话/渠道会话的隔离与冲突；Tasker 结果落库的会话归属。
3. 诊断导出的数据收集是否会包含数据库片段（如崩溃日志里的 SQL 参数）。
4. 插件 Hook 持久化（hooks 声明解析/存储）与插件市场数据的迁移/版本兼容。
5. 大体积会话（长会话树/多附件）下的批量查询分页边界——近两月大量修过"partial-page 合并"，找**同类模式的其他分页点**（比如 artifact/渠道历史/记忆浏览）是否还有未处理的页边界。
6. 自动备份 VACUUM INTO 快照链路：三个 DB 一致性窗口、写闸门复查是否覆盖所有写点。
