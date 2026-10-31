# 链路 7：记忆与知识库（RAG） — 去重基线

## 已确认修复（不得再报）
- 记忆：FactDb 原子删除（facts+FTS+links 同事务）；自动保存指纹（失败不写、窗口与 MAX_MESSAGE_CHARS 一致、幂等键含 assistant/scope/space、失败补跑）；FACTS 增量对账与空表清空；scoped 记忆编译不写共享根文件。
- 记忆恢复闸门：MemoryTicker forceCompileNow/daily pipeline/writeDailyState 写前 fail-closed。
- RAG 六轮修复：内部文档隔离 fail-closed；流式索引失败清理 chunk/FTS/HNSW 并清零文档索引状态；首批 embedding/维度失败保留旧索引；contentHash 全导入入口写入（流式增量计算）；关键词兜底走 chunks FTS BM25 + 缺表自愈 + scopeDocIds 范围不扩大；注入预算=实际 200 字符 snippet。
- devdocs：BuiltInKnowledgeDocSeeder 统一 seed + ensureIndexed 增量向量索引（is_internal，静默降级）；search_conversation SAFE 工具（sessionId 来自宿主上下文 fail-closed）；会话回溯 conversationRecallEnabled（messages_fts/LIKE，预算内原文注入）。
- HNSW：embedding 指纹与维度切换回归；导入口径守卫统一（a603c4f7）。
- 每日总结/朋友圈调度、记忆角色映射回归补齐。

## 已知未解决（可引用编号补充新证据）
- 主知识库页与 F-31 导入口径不一致、并入流式导入未做（已知）。
- DocumentParser 大结构化文档保护限制（已知）。
- P1 #20-31 中 RAG 相关残余（MMR 缺口只在部分 HNSW 路径、metadataFilter 过滤路径回退已部分修复——核实现状）。
- MemoryDb 高版本归档无应用内恢复界面（已知）。

## 本链路重点探查方向
1. **ensureIndexed 增量索引的新风险**：启动后台索引与用户同时导入的并发；embedding 配置中途变更（模型/维度切换）时 is_internal 文档的旧向量处理；静默降级是否会让用户误以为 devdocs 可语义检索（可发现性）。
2. 会话回溯 + search_conversation + knowledge_search 三个检索入口的预算叠加：一轮生成里三路注入的总量控制。
3. FactDb v14→v15 迁移；记忆星座/反思管线的 v15 实体兼容。
4. RAG 分块语义：中文/无空格文本的分块质量（分块器对 CJK 的处理）；FTS tokenizer 对中文的分词现状（BM25 中文支路历史上"静默失效"——核实现状，若已失效算旧账新证据，若已修标注）。
5. 向量索引磁盘占用与清理：删除文档/知识库后 HNSW 文件回收；多知识库的 HNSW 存储布局。
6. knowledge_search include_internal 的暴露面：哪些助手/渠道能看到 internal 文档（配置一致性）。
