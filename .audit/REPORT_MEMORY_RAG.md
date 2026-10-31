# 记忆与知识库（RAG）链路审查报告

## 摘要

审查范围：memory/ 模块全部（FactDb v15、MemoryDb v5、自动保存调度、编译管线、每日反思、记忆星座），app/ 内 RAG 全链路（RagService、HNSW、VectorSearchService、HybridSearchService、FTS、分块、导入入口、SessionAttachmentService、BuiltInKnowledgeDocSeeder ensureIndexed、ReindexAllWorker），knowledge_search 工具（SkillSearchToolsImpl.execKnowledgeSearch）、search_conversation 工具、conversationRecallEnabled 会话回溯注入、embedding 配置切换处理（EmbeddingService/OpenAIEmbeddingProvider/LocalKeywordEmbeddingProvider、指纹与维度守卫）。方法为纯静态代码审查（Glob/Grep/Read），逐条核对去重基线。总体结论：六轮已修项在代码中均能对应到修复点（applyIsInternal fail-closed、流式失败清理、维度/指纹守卫、contentHash 全入口、关键词兜底 scope+BM25、注入预算 snippet 计量），本次报告不重报。新发现集中在 **ensureIndexed 增量索引的 embedding 指纹缺口、文档删除的 HNSW 磁盘回收路径、一轮三路检索注入的总量控制缺口、OpenAIEmbeddingProvider 缓存维度回填时机、embedding 静默降级可发现性** 等 12 条；FactDb v14→v15 迁移本体经核对为正确（含唯一索引与实体声明一致），仅墓碑匹配与回滚路径有边界问题。

发现数量：12 条（P0: 0，P1: 6，P2: 6）。

## 发现清单

### F1. ensureIndexed 用旧向量索引新内部文档 — embedding 中途切换时 is_internal 文档绕过指纹失效（P1，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/data/knowledge/BuiltInKnowledgeDocSeeder.kt:101-142；app/src/main/java/io/zer0/muse/rag/RagService.kt:962-965、286-330
- 证据：`ensureIndexed` 只以 `chunkCount <= 0 || contentHash 与存储不符` 判定 pending，**不校验 `doc.embeddingModel` 是否等于当前配置 key**：

  ```kotlin
  // BuiltInKnowledgeDocSeeder.kt:108-113
  val pending = docs.filter { doc ->
      doc.chunkCount <= 0 || doc.contentHash.isBlank() ||
          doc.contentHash != RagService.computeContentHash(doc.content)
  }
  ```

  与此同时，`RagService.indexDocumentInternal`（RagService.kt:635-641）用 `chunkDao.getFirstIndexedEmbeddingDim()` 做**全库**维度校验。当用户把 embedding 从 LOCAL_KEYWORD(512d) 切到 CLOUD(1536d)：全库维度校验命中 → `indexDocument` 抛维度异常 → `failed = true` → `indexed` 不置位（下次启动重试，永远失败，此为 F1 的次生问题）；但当切换发生在两个**同维度**模型之间（如 CLOUD text-embedding-3-small(1536d) ↔ 另一 1536d 模型，或 LOCAL ↔ LOCAL_KEYWORD 同维场景外的同维切换），维度校验通过，seeder 会用新 provider 给 internal 文档写入新向量 —— 而用户文档库其余向量仍是旧模型语义。
- 影响：用户文档与内部文档在同一索引里混入两代 embedding 语义；`knowledge_search(include_internal=true)` 检索 devdoc 时新向量与旧库互不匹配，余弦分失真（同维不同语义），检索质量静默劣化且无任何提示。`vectorEmbeddingsNeedReindex` 门（RagService.kt:962-965）只在**指纹变化检测发生于 loadVectorIndexIfNeeded** 时置位，而 seeder 的 indexDocument 会先 `loadVectorIndexIfNeeded(provider)`（RagService.kt:617），若该次 load 时前一次指纹已同值（例如进程启动后第一次 load 就用新配置），旧库向量与新模型的差异不会反映在任何指纹状态里。
- 建议：pending 判定加入 `doc.embeddingModel != RagConfig.embeddingModelKey(config)` 条件（用户文档 reindexDocument 已写该 key，seeder 也写了，但比对端没用）；同时 seeder 失败不应无限重试同一堵墙（见 F2）。

### F2. ensureIndexed 指数级重试同一失败向量索引 — 每次启动全量重跑且无退避（P1，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/data/knowledge/BuiltInKnowledgeDocSeeder.kt:118-141；app/src/main/java/io/zer0/muse/MuseApp.kt:462-467
- 证据：`pending.forEach { doc -> ... }` 中任何一个文档 `indexDocument` 抛异常（如维度不匹配、embedding API 永久 404），`failed = true`，`indexed` 保持 false。MuseApp.kt:465 每次进程启动都调用 `indexDevDocs()` → `ensureIndexed()`，由于 `seeded` 标志是内存级（进程重启清零），每次启动都会重新 `indexDocument` 全部 pending 文档一遍。对比：embedding 熔断（RagService.kt:210-211 `embeddingFailureUntil` 5 分钟）只保护**检索路径**，seeder 的索引路径没有等价熔断。
- 影响：embedding 配置长期错误（API key 失效、模型名错、Anthropic/Gemini 类型降级后再切回 CLOUD 但 cloudModel 仍空）的设备，每次冷启动都会对每个 internal 文档发起一次注定失败的 embedding 调用（多文档 × 启动时网络请求），叠加 CloudEmbedding 的网络超时（10s/次，OpenAIEmbeddingProvider.kt:90），首启动会话被拉长；日志噪音 + 无意义流量。与"静默降级保留关键词兜底"的注释意图（BuiltInKnowledgeDocSeeder.kt:95-99 "失败静默"）一致，但**静默**意味着用户永远不知道 devdocs 语义检索不可用（对应去重基线末节探查方向 1 的可发现性项，此处为该方向的实现证据）。
- 建议：按 provider 维度加一个持久化的"连续失败 N 次后本版本不再自动重试"标记，或在 RAG 设置页展示 devdoc 索引状态。

### F3. OpenAIEmbeddingProvider.dimension 用上次响应回填，首次成功前指纹 guess 可能与真实维度不一致（P1，可信度：中）

- 位置：app/src/main/java/io/zer0/muse/rag/OpenAIEmbeddingProvider.kt:55-59、113-127；app/src/main/java/io/zer0/muse/rag/RagService.kt:1579-1580
- 证据：

  ```kotlin
  // OpenAIEmbeddingProvider.kt:55-59
  @Volatile private var cachedDimension: Int = -1
  override val dimension: Int
      get() = if (cachedDimension > 0) cachedDimension else guessDimension(modelName)

  // RagService.kt:1579-1580
  private fun embeddingFingerprint(provider: EmbeddingProvider): String =
      "${provider.id}\u0000${provider.modelName}\u0000${provider.dimension}"
  ```

  `guessDimension` 按模型名猜测（`else -> 1024` 兜底）。索引流程：`indexDocument` → `loadVectorIndexIfNeeded(provider)`（此刻 cachedDimension=-1，指纹=guess 值）→ embed 批次成功（cachedDimension 被回填为真实维度）→ 后续 `saveVectorIndex(fingerprint=vectorIndexFingerprint)` 用的是**load 时**捕获的旧指纹。而下次启动 load 时，若 HNSW 文件头指纹是 guess=1024、本次预期指纹是真实 dim=1536（text-embedding-3-large 实际 3072，guess 也可能猜对但 guess 错的情形必现），`loadIndexFile` 直接抛 `HnswEmbeddingFingerprintMismatchException` → 索引判失效 → `vectorIndexNeedsFullRebuild` → 全库重索引。
- 影响：**同一 embedding 模型不变、只是"写入索引文件时维度未回填"**，就会造成每次冷启动后 HNSW 索引被判 fingerprint mismatch 而重建。用户表现为：每次重启后第一轮检索走暴力遍历（大库时明显变慢），且 HNSW 落盘节流（SAVE_THROTTLE_MS=15s）内 saveVectorIndex 被禁止（vectorIndexNeedsFullRebuild=true，RagService.kt:373-376），派生索引长期处于"待重建"态。触发条件是 guessDimension 猜错（非白名单模型名 + 非默认 1024 维，如智谱 embedding-3、自建模型），概率不低——guessDimension 白名单只有 8 个名字。
- 建议：首 Embedding 响应回填 cachedDimension 后，若与构造时 guess 不一致，主动调用一次 `markVectorIndexNeedsFullRebuild` 之外的指纹刷新路径（如让指纹捕获发生在 embed 之后的首次 saveVectorIndex，或 provider 暴露 `dimensionReady` 标志由 RagService 在 embed 成功后重新解析指纹）。

### F4. 文档/知识库删除后 HNSW 磁盘回收依赖调用方补 save，且删 KB 主路径不走 saveVectorIndex（P1，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/rag/RagService.kt:471-499、1249-1264、1367-1372；app/src/main/java/io/zer0/muse/ui/knowledge/KnowledgeScreen.kt:1591-1596
- 证据：单文档删除 `deleteDocIndex`（RagService.kt:1250-1264）删完 DB/FTS 后**立即** `saveVectorIndex()`，落盘清理了 HNSW 软删节点（save 时压缩剔除，HnswVectorIndex.kt:427-453 alive 过滤）。但 KB 删除主路径是 UI 直接循环 `deleteDocument`：

  ```kotlin
  // KnowledgeScreen.kt:1592-1595
  dao.getByKbIds(listOf(kb.id)).forEach { doc -> ragService.deleteDocument(doc.id) }
  folderDao.deleteByKb(kb.id)
  kbDao.delete(kb.id)
  ```

  每个 `deleteDocument` 内都会各 save 一次 HNSW —— KB 内 50 篇文档删除就是 50 次全量序列化写盘（每次 O(全部存活节点) 重写 hnsw_index.bin）。此外 `HnswVectorIndex.remove` 是**软删除**（标记 deleted=true，HnswVectorIndex.kt:225-237），磁盘回收只在 save 时的 alive 过滤发生；若 save 因 SAVE_THROTTLE 或写盘失败被跳过（RagService.kt:387-389 仅 warning），旧节点连同向量常驻文件直到下次成功 save。
- 影响：a) 删大 KB 时 UI 卡顿（同步 IO 循环 ×N 次全量写盘）；b) `saveVectorIndex` 失败仅记日志，磁盘上 hnsw_index.bin 保留已删文档的向量，下次启动 `loadVectorIndexIfNeeded` 会把它们加载回内存，靠 `rebuildChunkMetaCache`（只按 DB chunkId 填缓存）导致这些孤儿节点在 HNSW 检索路径 `chunkMetaCache.get(r.id)` 未命中被跳过（RagService.kt:1016）——功能正确但内存与磁盘持续占用，直到某次成功 save 才回收；c) `docDao.deleteByKb`（KnowledgeDocDao.kt:90-91，注释明确"调用前应确保已删除所有相关 chunks"）无任何调用方直接使用，属死代码但注释暗示的 KB 级批量路径并不存在事务保护（逐文档循环中途取消会留下半删状态）。
- 建议：KB 删除改为一次性收集 docIds → 循环 `removeDocChunksFromVectorIndex` + DB 批删 → 最后一次 `saveVectorIndex`；或 deleteDocIndex 的 save 改为节流/合并。

### F5. 一轮生成三路检索注入（RAG 自动注入 + 会话回溯 + knowledge_search 工具）无统一总量闸门（P1，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt:829-903、956-1032；app/src/main/java/io/zer0/muse/ui/chat/ConversationRecallFormatter.kt:18-33；app/src/main/java/io/zer0/muse/rag/RagService.kt:1148-1189；app/src/main/java/io/zer0/muse/tools/SkillSearchToolsImpl.kt:489-502、516-578
- 证据：三条检索注入路径各有独立预算，彼此不感知：
  1. **RAG 自动注入**：`buildInjectionContextWithCitations` 受 `ragConfig.tokenBudget`（默认 0=不限制，RagConfig.kt:75）+ 注入后再被 `ContextBudget.RAG_CITATION`(8000 字符) clamp（ChatGenerationController.kt:1020-1024）。
  2. **会话回溯**：`ConversationRecallFormatter.build(maxTokens = memoryConfigCache.tokenBudget.coerceAtMost(1600))`（ChatGenerationController.kt:887-892），在 system prompt 拼接内，受最终 `SYSTEM_PROMPT` 80k 字符总 clamp（ChatGenerationController.kt:907-909）。
  3. **knowledge_search 工具**：`execKnowledgeSearch` 返回**每条 300 字符 × topK≤50**（SkillSearchToolsImpl.kt:500 `r.chunkContent.take(300)`，topK 上限 50，即最大 15k 字符）的**完整 chunk 内容**（不是 200 字符 snippet），进 TOOL 消息。工具结果内联预算由 `ToolOrchestrator.prepareToolOutput`（≤4KB 直接 inline，ToolOrchestrator.kt:2509）与 `toolOutputInlineBudgetTokens`（ToolOrchestrator.kt:369-374）兜底，但**该预算与 1、2 的预算无共享池**。
- 影响：`ragConfig.tokenBudget=0`（默认）时路径 1 只靠 8000 字符 clamp 兜底；路径 3 一轮内模型可多次调用 knowledge_search（tool 轮上限 10 轮默认），每轮结果经"窗口放不下→落盘+预览"机制控制在模型窗口内，但**一轮生成内的 RAG 注入 + 回溯 + N 次检索工具结果的合计 token 没有任何一处做总量核算**——`toolOutputInlineBudgetTokens` 只看 currentHistoryTokens 与本轮 tool 调用数，不看 RAG/回溯已占多少。近上限时靠 `pre-send context overflow`（ChatGenerationController.kt:1038-1052）拒绝发送，但此时用户输入已丢失，体验是"发不出去"而非"检索内容被裁剪"。与去重基线"注入预算=实际 200 字符 snippet"已修项不冲突（那是路径 1 内部计量准确性），本轮发现的是**跨路径总量**缺口。
- 建议：在 buildSystemPrompt 拼装处为 RAG+回溯共享一个组合预算（如 RAG_CITATION+回溯 ≤ 合一上限），并让 knowledge_search 的 topK 上限纳入 tool 输出预算分母。

### F6. search_conversation 全量原文注入无条目级预算（P1，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/tools/SearchConversationTool.kt:40-58；app/src/main/java/io/zer0/muse/data/session/SessionRepository.kt:1337-1378；app/src/main/java/io/zer0/muse/tools/ToolOrchestrator.kt:2504-2558
- 证据：`searchMessagesInSession` 返回的 `SearchResult.content` 是**完整原文**（SessionRepository.kt:1375 `content = join.content`）；`SearchConversationTool.execute` 直接 `appendLine(content)`，对每条结果**不做 take 截断**（对比 ConversationRecallFormatter.kt:40 有 `MAX_ITEM_CHARS = 800`）。limit 上限 20 条。若会话里有 20 条 5000 字符的长消息命中，一次调用返回 10 万字符。工具结果链路上，`prepareToolOutput` 会在 4KB 后落盘+预览（ToolOrchestrator.kt:2509-2513），但 SMALL_TOOL_RESULT_INLINE_CHARS=4KB 以下的仍全量 inline，且落盘路径给模型的指引是"read_file 分段读取"——模型读取时又一次全量拉取。
- 影响：search_conversation 是 SAFE 工具（ToolPermissionResolver.kt 上游 riskLevel.SAFE，SearchConversationTool.kt:26），无需审批即可在每轮被调用；压缩后的会话（该工具的设计场景，"Use when an older detail is needed after context compression"）往往包含大量长消息，多次调用会把压缩省下的窗口又吃回去，间接放大上下文压力；与去重基线已修项"会话回溯（messages_fts/LIKE，预算内原文注入）"对比，**自动注入路径有 1600 token 预算，显式工具路径没有任何预算**，二者口径不一致。
- 建议：在 SearchConversationTool 输出侧对每条 content 做 take（对齐 ConversationRecallFormatter 的 800 字符），或复用 buildSnippet。

### F7. 每日反思/整合路径的墓碑匹配是子串级，长墓碑会误删编译产物行（P1，可信度：中）

- 位置：memory/src/main/java/io/zer0/memory/fact/FactStore.kt:1557-1575；memory/src/main/java/io/zer0/memory/compile/MemoryCompiler.kt:307-316、866-908
- 证据：`matchesTombstone` 双通道均为 **contains**：

  ```kotlin
  // FactStore.kt:1568-1575
  fun matchesTombstone(text: String, tombstone: String): Boolean {
      ...
      if (normalized.contains(tomb)) return true
      ...
      return strippedTomb.isNotBlank() && stripped.contains(strippedTomb)
  }
  ```

  删除一条长事实（如"用户每周三下午要去医院做康复训练，同时照顾生病的母亲"）产生的墓碑，会匹配掉编译产物中任何**包含该长句作为子串**的行——包括整合后信息更全的新行（如"用户每周三下午要去医院做康复训练，同时照顾生病的母亲，上周三改为周四"）。`MemoryCompiler.reconcileFactsSectionWithStore`（FactStore.kt 注释 line 809 `filterTombstonedLines(normalized, tombstones)`）逐行过滤 FACTS 段时，被墓碑命中的新行会被整行删除。
- 影响：删除一条事实后，后续 LLM 整合产物中合理引用/改写该事实内容的新行被静默清空；FACTS 段内容丢失但无诊断注记（对比 ContextBudget.clamp 的截断注记纪律）。深度路径 DeepMemoryProcessor.kt:184-189 同样过滤摘要文本，误杀面更大（摘要行更长、子串重叠概率更高）。
- 建议：墓碑匹配加最短长度阈值或要求去标点后等值/前缀匹配；被过滤时在产物里保留 `[已删除记忆: …]` 占位注记以便诊断。

### F8. FactDb v15 墓碑"删除即写墓碑"与 clearAll/deleted facts 重添加语义一致，但 deleteInternal 事务内 scheduleReconcile 在事务外执行且失败无重试（P2，可信度：中）

- 位置：memory/src/main/java/io/zer0/memory/fact/FactStore.kt:1202-1242；memory/src/main/java/io/zer0/memory/fact/FactDb.kt:321-336
- 证据：v14→v15 迁移本体正确：`MIGRATION_14_15`（FactDb.kt:322-336）建表 + 唯一索引，与 `FactTombstoneEntity`（FactTombstoneEntity.kt:9-18）的注解声明逐字段一致（列名/类型/NOT NULL/AUTOINCREMENT/索引名）；`FACT_DB_VERSION=15` 与迁移链末端、版本守卫 `archiveUnknownVersionDatabase`（FactDb.kt:378 `version <= FACT_DB_VERSION`）三者一致，历史"写死 13"缺陷已修。但 `deleteInternal` 里墓碑插入与 facts 删除同事务（好），`scheduleReconcile` 在事务提交后（FactStore.kt:1240），若 hook 抛异常仅 `Logger.w`（FactStore.kt:1443-1446），下一轮编译兜底——**兜底是每日一次**，删除后 12-24h 内注入的 FACTS 段仍含已删行（墓碑过滤依赖 hook 成功写回产物）。此为设计取舍与"写后对账失败"的组合窗口，非 v15 迁移缺陷，列为 P2 观察项。
- 影响：删除记忆后至下次每日编译前，system prompt 的 FACTS/相关记忆段仍可能出现该条（相关记忆段 `searchRelevantFacts` 因 DB 行已删不受影响，仅 FACTS 编译段受影响）。
- 建议：将 reconcile 失败标记进 daily state 的 pendingSteps，让下次 tick 优先重试对账而非等整条 daily 链。

### F9. memory 模块每日反思/星座对 v15 实体的兼容性缺口：getByScopeAndSpace 不返回 tombstone，整合器写库用 dao.insert(REPLACE) 不清 FTS 旧行（P2，可信度：中）

- 位置：memory/src/main/java/io/zer0/memory/fact/LlmFactConsolidator.kt（合并写库路径）；memory/src/main/java/io/zer0/memory/fact/FactStore.kt:444-479
- 证据：`mergeSameEntityDuplicates`（FactStore.kt:462-465）用 `dao.insert(mergedEntity.copy(id = keeper.id))`（REPLACE 覆盖）+ `dao.deleteFts(other.id)` + `syncFtsRow(keeper.id, …)`，FTS 同步完整。但**每日 LLM 整合器**（MemoryTicker.kt:786-796 调 `consolidator.consolidateAll(store)`）走 `LlmFactConsolidator`，其合并写库若未同步 `deleteFts`/`insertFts`，会留下指向旧文本的 FTS 行（fact_id 相同、content_ngram 是旧表述），`ensureFtsIndexConsistent` 的 COUNT 对比（FactStore.kt:1588-1603）只比对**行数**，检测不到这种"行数一致但内容过期"。需要复核 LlmFactConsolidator 的具体写库实现（本次未逐行展开该文件，标注为待验证）。
- 影响（若成立）：整合后的 FTS 检索命中旧文本，用户在记忆中心搜索新表述搜不到。验证方法：跑 LlmFactConsolidatorTest 后比对 facts_fts 的 content_ngram 与 facts.fact。
- 建议：统一走 `syncFtsRow` 单一入口。

### F10. 记忆星座（MemoryGraphViewModel）对 v15 墓碑/矛盾实体只读展示，删除动作绕过 tombstone 写入（P2，可信度：中）

- 位置：app/src/main/java/io/zer0/muse/ui/memory/MemoryGraphViewModel.kt（星座数据源）；memory/src/main/java/io/zer0/memory/reflection/MemoryReflectionRunner.kt:39
- 证据：`MemoryReflectionRunner.runReflection` 只做回填/合并/矛盾检测/晋升，**不写墓碑**；星座删除节点若直接调 `dao.deleteById` 而非 `FactStore.delete`（后者才写墓碑），该事实可能从 session 摘要"复活"——这正是 v15 墓碑要防的场景。本次审查确认 `FactStore.delete`（唯一写墓碑入口）与 `clearAll`（清空墓碑）口径一致；**星座 UI 删除是否走 FactStore.delete 需复核 MemoryGraphViewModel 的删除回调**（未逐行确认，标注待验证）。
- 影响（若成立）：从星座图删除的记忆在下一次 daily 编译时从摘要中重新出现。
- 建议：星座删除回调统一走 `FactStore.delete(id, assistantId, scope, spaceId)`。

### F11. knowledge_search include_internal 暴露面：各助手/渠道一致（fail-closed 正确），但 folder 作用域下 include_internal=true 会绕过用户 KB 语义返回 internal 文档（P2，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/tools/SkillSearchToolsImpl.kt:440-458、479-488、522-535
- 证据：include_internal 的过滤在向量路径是 `if (includeInternal) vectorResults else filterNot { it.isInternal }`（SkillSearchToolsImpl.kt:483-488），在 folder 路径 `folderScopeDocIds` 由 `dao.observeAllUser().first()`（SkillSearchToolsImpl.kt:449，**排除 internal**）构建，因此 folder + include_internal=true 的组合实际**不可能命中 internal 文档**（folder 集合里没有它们）——行为安全，但与"include_internal=true 应返回 internal"的参数语义矛盾，模型会被误导（返回空结果并提示"向量检索无命中"）。同时确认：自动 RAG 注入路径在 RagService.kt:1105-1108 显式 `filterNot { it.isInternal }`（正确）；GroupChatScheduler.kt:3170-3179 的群聊 RAG 注入走同一 `buildInjectionContextWithCitations`，同样被过滤（正确）；LIKE 降级路径 `dao.search(query, includeInternal)`（SkillSearchToolsImpl.kt:518-522）的 includeInternal 默认 false、folder 过滤 `let { scope == null ? list : filter { it.id in scope } }`（SkillSearchToolsImpl.kt:532-535）在 includeInternal=true 时也不会串入 internal（scope 为 null 时才会返回 internal）。
- 影响：include_internal=true + folder 组合返回误导性的空诊断；无数据泄漏，属参数语义/诊断口径缺陷。
- 建议：folder 存在时在文档说明或诊断行注明"folder 作用域不包含内置文档"。

### F12. 主知识库导入口 embeddingModel 写入口径不一致（文档漂移，P2，可信度：高）

- 位置：app/src/main/java/io/zer0/muse/ui/knowledge/KnowledgeScreen.kt:481-485、493
- 证据：主页面**解析型/图片文档**导入成功后写 `embeddingModel = modelName`，其中 `modelName = ragConfig.cloudModel.ifBlank { "cloud-default" }`（KnowledgeScreen.kt:481）；而**纯文本流式导入**（同文件 :374）与其余全部入口（KnowledgeArchiveImporter.kt:425/491、SessionAttachmentService.kt:141、BuiltInKnowledgeDocSeeder.kt:131、RagService.reindexDocument:1455/1481）统一写 `RagConfig.embeddingModelKey(ragConfig)`（如 `embed:cloud:provId:model`）。两口径并存导致同一字段语义分裂：一个存裸模型名、一个存组合 key。
- 影响：a) 依赖 embeddingModel 做"是否同模型"比对的逻辑（BuiltInKnowledgeDocSeeder 的 pending 判定、F-33 的 lastEmbedKey 提示）无法统一复用该字段；b) 知识库详情页显示 `doc.embeddingModel`（KnowledgeScreen.kt:2130）时两种格式混排，用户困惑；c) 与全局已知项"主知识库页与 F-31 导入口径不一致、并入流式导入未做"（DEDUPE_MEMORY_RAG.md:12）**不同**——已知项指导入通道/解析器口径，本条是**embeddingModel 字段写入格式**的新证据。
- 影响（补充）：仅信息展示与未来比对逻辑隐患，无检索错误。
- 建议：统一改为 `RagConfig.embeddingModelKey(ragConfig)`。

## 已知项核对（不重报清单）

逐条核对去重基线与全局已知项，以下项已确认在当前代码中处于已修复/已知状态，未列为新发现：

1. **内部文档隔离 fail-closed**：RagService.kt:1058-1072 `applyIsInternal` 查询失败返回空列表（fail-closed），构建注入时 `filterNot { it.isInternal }`（:1108），关键词兜底双重防御（:1205-1206）。✅已修，未重报。
2. **流式索引失败清理 chunk/FTS/HNSW 并清零文档索引状态**：indexDocumentStreamed.kt:758-764 `cleanupPartialIndex` + deleteDocIndex + clearIndexStatus；非流式 :686-711 `cleanupFailedIndexReplacement`。✅已修。
3. **首批 embedding/维度失败保留旧索引**：streamed `destructiveStarted` 标志在首批维度校验后才置位（:790-796）；非流式 embedding 在 `destructiveStarted` 前（:615-641）。RagStreamedIndexTest.kt:258-280 有回归钉。✅已修。
4. **contentHash 全导入入口写入**：KnowledgeScreen :375/:488、KnowledgeArchiveImporter :426/:492、SessionAttachmentService :142、BuiltInKnowledgeDocSeeder :132、RagService :1456/:1482 均写入。✅已修。
5. **关键词兜底走 chunks FTS BM25 + 缺表自愈 + scopeDocIds 范围不扩大**：KnowledgeKeywordFallback.kt:51-110（BM25 优先、scope 过滤、internal 排除、metadataFilter 一致），withFtsSelfHeal 自愈。✅已修。
6. **注入预算=实际 200 字符 snippet**：RagService.kt:1156-1167（snippet 与计量同源）。✅已修。
7. **BuiltInKnowledgeDocSeeder ensureIndexed 落地**：存在且幂等（indexMutex + indexed 标志），本轮报的是其指纹缺口与重试策略（F1/F2），非"未落地"。✅已知修复项不复报。
8. **search_conversation SAFE + sessionId 来自宿主上下文 fail-closed**：SearchConversationTool.kt:36-37 空 sessionId 返回错误。✅已修（本轮 F6 报的是其输出预算，非 SAFE 语义）。
9. **会话回溯 conversationRecallEnabled**：ChatGenerationController.kt:870-902 开关与 1600 token 预算存在。✅已修。
10. **HNSW embedding 指纹与维度切换回归**：指纹写入文件头（HnswVectorIndex VERSION=2）、load 时 mismatch 抛 `HnswEmbeddingFingerprintMismatchException`、`vectorEmbeddingsNeedReindex` 门禁检索、`reindexAllInKbs` allowDimensionChange 计划（RagService.kt:1406-1424）。✅机制已修（F3 报的是 guess 维度时机缺陷，属新证据）。
11. **主知识库页与 F-31 导入口径不一致、并入流式导入未做**：仍为已知未解决，未重报（F12 是不同维度的新证据，已标注区分）。
12. **MMR 缺口只在部分 HNSW 路径 / metadataFilter 回退部分修复**：核实现状——HNSW 路径已带 `metadataFilter == null || metadataFilter.isEmpty()` 守卫（RagService.kt:1006 注释与条件一致），filter 非空时正确落到 vectorSearch；HNSW 路径**不做 MMR**（直接 take(k)+threshold），混合检索路径 MMR 由 vectorSearch 内部应用——确认"MMR 缺口只在 HNSW 路径"仍为现状，属已知未解决，未重报。
13. **MemoryDb 高版本归档无应用内恢复入口**：MemoryDbArchiveRecovery 存在但仅代码入口（MemoryModule.kt:54 注释 "Explicit recovery entry"），UI 入口仍缺——已知，未重报。
14. **ProcessWriteGate / at-least-once / detekt 存量 / 语言包缺翻译 / 模拟器验收缺失**：按全局已知项跳过。
15. **MemoryDbDowngradeGuard**：MemoryModule.kt:35-39 降级归档守卫已接（与去重基线"恢复闸门 fail-closed"一致）。

## 重点探查方向结论对照

| 基线探查方向 | 结论 |
|---|---|
| 1. ensureIndexed 增量索引新风险 | **F1**（指纹缺口）、**F2**（失败重试无退避/静默降级可发现性缺失）为新发现 |
| 2. 三路注入总量控制 | **F5**（RAG+回溯+工具无共享总量）、**F6**（search_conversation 无条目预算）为新发现 |
| 3. FactDb v14→v15 迁移 | 迁移正确（F8 附带对账时序 P2 观察项）；F9/F10 为待验证的周边兼容项 |
| 4. CJK 分块与 FTS 中文分词 | **"BM25 中文支路静默失效"已修**：索引与查询两侧均走 `MessageFtsManager.toNgram` 2-gram（RagService.kt:664/:818、HybridSearchService.buildFtsQuery:210-228、MIGRATION_106_107 全量回填 MuseDb.kt:1007-1012、repair 重建同规范化 MuseDb.kt:296-322），RagSearchTest.kt:56-58 有等价性回归钉。分块侧 CJK：字符模式按句子/段落递归（TextChunker.kt:85-101），markdownAware 巨块保底切分（:257-262 + RagService.splitOversizedPlainChunk），无"无空格文本分块失效"问题 |
| 5. 删除后 HNSW 磁盘回收 | **F4**（软删节点仅 save 时压缩、删 KB N 次全量写盘、save 失败残留）为新发现 |
| 6. include_internal 暴露面一致性 | 自动注入/群聊/工具默认路径全部 fail-closed 正确；**F11**（folder+include_internal 组合语义矛盾）为新发现 |
