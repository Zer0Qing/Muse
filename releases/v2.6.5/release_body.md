# Muse v2.6.5

> 第三方深度审计报告的完整修复（27 条中可落地的 18 条）+ 用户反馈的三个聊天问题。

---

## 中文

### 用户反馈修复

#### 删掉的聊天又自己回来了，还自动开始生成

生成被中断时会留下一条 checkpoint（恢复锚点）。删除消息时按 id 精确清理，但流式中断时预生成的 id 可能与实际落盘行**对不上**，于是残留。

下次进会话，恢复逻辑把残留 checkpoint 重建成一条新消息，按 checkpoint 的时间戳排序，所以跑到列表末尾——看起来就是"删了又出现"。

现在删消息时会额外按时间兜底清理该会话的残留 checkpoint。比被删消息更早的保留。

#### 已经生成的回复，重进就消失

消息落库是 REPLACE 语义。如果已有完整回复，而后又来了一次**内容为空**的写入（流式状态重置、恢复路径传空、工具轮重建漏字段），完整回复就被冲成空。

现在加了内容回归保护：旧内容非空而新值为空时，保留旧内容。

#### 侧栏长按导航"没了"

**功能一直在，但看不见。**

10-09 的改动为了修"可视滑块在长会话里到处飘"，把轨道整个删掉，改成右侧长按 + 上下滑动的隐形热区。理由是真的：LazyColumn 不暴露总高度，只能按平均高外推，遇到图片/代码块这类长条目误差极大，滑块就会乱跳。

但完全不可见等于没有。现在加了一条**仅滑动时浮现**的提示线：

- 平时完全隐藏
- 长按右侧边缘 → 轻震动 + 淡入（120ms）
- 滑动中保持可见，同时浮出消息预览
- 松手淡出（220ms）

不做位置映射，所以不会重现乱飘。

同时把显示门槛从 **25 条消息降到 5 条**。

---

### 审计报告修复

#### 混合检索终于真的并行了
注释写着"协程并发"，实际是两行顺序执行（`resultOf` 只是 try/catch 包装，没有 `async`）。大库时向量检索与 BM25 耗时全额叠加。改用 `coroutineScope + async`，总耗时从「两者之和」变成「两者较大值」。

#### 分块大小不再是设定值 × 2
`plainChunkLimit = targetSize * 2` 允许单个片段在缓冲为空时整段塞入。设 400 可能得到近 800。现在严格按 `targetSize` 切分。

#### 分块重叠终于生效
`markdownAware` 默认开启，而该分支里写反了顺序：先 `flush()` 清空 buffer，再取尾部取重叠——永远拿到空串。**所有用户的重叠设置从未生效过**。

#### 每块注入上限从 200 字提到 1200
旧实现硬编码 `take(200)`，你把 chunkSize 调到 500 时后 60% 永远进不了上下文——多花 embedding 钱，少拿信息。

#### RAG 检索不再可能卡 5 分钟
query embedding 没有任何超时，且复用了聊天用的 OkHttpClient（`readTimeout = 300s`）。端点「接受连接但不响应」时用户要等 5 分钟。现在加 10 秒硬超时，超时即熔断降级到本地关键词检索。

#### 重建索引不再把全库读进内存
为算一个布尔值调 `getAllWithEmbedding()`，把全库 chunk 连同 embedding 读进内存（1024 维约 4KB/块，1 万块 40MB+）。改用 SQL 计数。

#### 重建索引不再静默清空
中途失败时旧索引已删、新索引不完整，文档还在列表里看起来正常，但检索永远召不到。现在标记为「索引已丢失」，文档卡下方显示红字提示。

#### 「相似度 0.83」那个数字是假的
混合检索返回的是「相对最高分」的比值（最高一律 1.0），不是余弦相似度。现在结果同时保留真实的 `rawScore`。

#### 关键词弱命中不再混进结果
`threshold` 此前只作用于向量路，BM25 命中无论多弱都参与融合。现在也按相对强度归一化后卡阈值。

#### 首页两个矛盾的时间问候
摘要把时段问候写死进文本，首页问候是实时的，晚上会同时出现「晚上好」和「中午好」。摘要不再携带时段信息。

#### 30 篇文档点重建却提示 46 篇
多出来的是内建开发文档。它们确实需要一起重建，但不该计入用户看到的数字。现在按内建/用户文档分离。

#### 8 个数值项现在能手动输入
此前只能拖滑块，受步长限制：分块大小步长 100（**380 设不了**）、检索数量上限 10、Token 预算上限 8000。现在点数值区即可输入，自动夹到合法区间。

#### 新增「测试 Embedding」按钮
配错（模型名错、余额不足、Provider 不支持）此前只能等重建索引才发现，报错只在日志里。现在一键 embed 一句 `ping`，回显维度与耗时，或给出可读的失败原因。

#### 重索引失败终于说人话
旧提示只说「46 篇失败」，真实错误（如 HTTP 402 余额不足）只在 logcat。现在展示前两条具体原因。

#### 新增云端 Rerank
报告称这是与同类工具最大的能力差距。新增 OpenAI 兼容的 `POST {baseUrl}/rerank`，SiliconFlow / Jina / Cohere 都能复用。本地 rerank 对中文基本空转（把整句中文当一个 token），现在有了云端方案。

#### 按知识点库覆盖检索参数（DB 111→112）
旧实现 RAG 配置全局一份，不同库不能有不同 chunk/topK/阈值。现在每个库可以覆盖 `chunkSize` / `chunkOverlap` / `topK` / `threshold` / `embeddingModel`，留空则继承全局。

#### 本地 ONNX 模型有入口了
`localModelPath` 在 UI 层此前零命中，普通用户永远配不了 ONNX 模型，LOCAL 模式恒降级到关键词检索。现在设置页可以选 .onnx 文件，自动复制到应用私有目录。

#### 全面屏返回手势误触
全仓此前 0 处 `systemGestureExclusionRects`，而内容距屏幕边缘仅 16dp，从边缘起手的横滑会先触发系统返回。已给首页横向翻页、对话列表左滑删除加上手势排除。

#### 深链页面输入不生效
`onNewIntent` 未调用 `setIntent()`，导致后续 `getIntent()` 一直返回启动时的旧 intent。

#### 其它
- 群聊把英文思考草稿发进群的问题（v2.6.4 回归）已修：不再从 reasoning 抢救发言内容
- 主动消息 OOC：人设不再截断 500 字，补上人格定义与语言约束，上下文 5 条 → 12 条
- 中转站多模态模型不再被内置能力表错杀
- 供应商「测试」按钮误报修掉（maxTokens 16 → 256）
- 错误详情不再截断 80 字
- Termux 授权引导补上 `termux-reload-settings`
- 新增「快速回复模式」开关，跳过每轮约 500ms 的相关记忆检索
- 4 处 `!!` 收敛，`GlobalScope` 收敛为模块级作用域

---

## English

### User-reported fixes

**Deleted chats came back and auto-generated.** Interrupted generations leave a checkpoint. Deletion cleaned by exact id, but the pre-generated id from a stream interruption often doesn't match the persisted row, so checkpoints survived. On next open they were rebuilt as new messages and sorted by checkpoint timestamp — landing at the bottom of the list. Deletion now also sweeps leftover checkpoints by timestamp.

**Generated replies vanished on re-entry.** Message upserts use REPLACE semantics. A later empty-content write (stream reset, recovery path, tool-round rebuild missing a field) wiped the full reply. Added content-regression protection.

**Side navigation "disappeared".** It never did — a 10-09 change removed the visible track (the slider drifted in long sessions because LazyColumn doesn't expose total height) and kept only an invisible long-press hot zone. A hint line now fades in only while navigating, and the threshold dropped from 25 messages to 5.

### Audit report fixes

- Hybrid search is actually parallel now (`coroutineScope + async`, was sequential)
- Chunk size no longer doubles (strict `targetSize` split)
- Chunk overlap finally works (was clearing before reading the tail)
- Per-chunk injection cap 200 → 1200 characters
- RAG query embedding has a 10s timeout (was unbounded, up to 5 minutes)
- Re-index no longer loads the whole library into memory (SQL count instead)
- Failed re-index is marked broken with a visible hint instead of silently wiping
- Results carry the real `rawScore`; the "similarity" shown before was a relative ratio
- BM25 hits now honor `threshold`
- Home no longer shows two contradictory time greetings
- Re-index counts exclude built-in docs (30 docs no longer reported as 46)
- Eight numeric settings accept typed input (chunk size 380 was unreachable)
- New "Test Embedding" button with dimension and latency
- Re-index failures report actual reasons
- **New cloud rerank** (OpenAI-compatible `/rerank`; SiliconFlow / Jina / Cohere)
- **Per-knowledge-base retrieval overrides** (DB 111 → 112)
- Local ONNX model can finally be selected in settings
- System gesture exclusion for horizontal surfaces
- Deep-link `setIntent()` fix
- Group chat no longer leaks English reasoning drafts (#10 regression)
- Proactive messages no longer out of character
- Relay multimodal models no longer misjudged as text-only
- Provider test false negatives fixed
- Error detail no longer truncated
- Termux permission guidance includes `termux-reload-settings`
- New "fast reply mode" skips ~500ms relevant-memory retrieval per turn

---

## 安装

- 覆盖安装即可，**无需卸载**，数据保留
- **数据库 111 → 112**：新增知识库级检索参数覆盖列（全部可空，留空即继承全局）
- 签名与 2.6.1 ~ 2.6.4 相同

> ⚠️ 本版含数据库迁移。从 2.6.x 任何版本升级都会走一次 5 条 `ALTER TABLE ADD COLUMN`，均为可空且有默认值，风险低，但建议首次升级前先备份。

## 已知事项

- 「消息框右边缘溢出」未能复现和定位，需用户提供具体会话/设置后再排查
- #5 reasoning 泄漏按方案 C 处理（首次重试、再失败才兜底），与「吐可以空不行」的既有原则折中
- 历史迁移 baseline 未合并（会让很老版本的用户升级路径失效，风险大于收益）
