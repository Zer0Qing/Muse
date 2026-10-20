# Muse v2.6.5

> 第三方深度审计报告的批量修复：检索并行化、块大小可控、索引计数去噪、失败原因可见、设置项可手输。

---

## 中文

### 混合检索终于真的并行了（P1-4）

代码注释写着「并行检索（协程并发）」，实际是两行顺序执行——`resultOf` 只是 try/catch 包装，没有 `async`。

大库时向量检索耗时与 BM25 耗时**全额叠加**。现在用 `coroutineScope + async` 真正并发，总耗时从「向量 + BM25」变成「两者较大值」。

### 分块大小不再是「设定值 × 2」（P1-2）

设 400 可能得到接近 800 的块。原因是 `plainChunkLimit = targetSize * 2` 允许单个片段在缓冲为空时整段塞入。

现在严格按 `targetSize` 切分，设定值真正生效。

### 重建索引不再把全库读进内存（P1-5）

为了算一个「是否覆盖全部文档」的布尔值，旧代码调 `getAllWithEmbedding()` 把全库 chunk 连同 embedding 读进内存——1024 维约 4KB/块，1 万块就是 40MB+，低端机容易 OOM。

现在改用 SQL 计数（`countOutsideDocs`），零内存开销。

### 「相似度」那个数字是假的（P1-6）

混合检索返回的 `rrfScore` 是「相对最高分」的比值，最高的一律显示 1.0，**不是余弦相似度**。UI 上却写成「相似度 0.83」。

现在结果同时保留 `rawScore`（真实余弦值），`rrfScore` 的注释也标明它是相对比值。

### 关键词命中的弱结果不再混进结果（P1-7）

`threshold` 此前只作用于向量路，BM25 命中无论多弱都参与融合——一个恰好含关键词但语义无关的块能一路挤进 top K。

现在 BM25 也按相对强度归一化后卡阈值。

### 首页两个矛盾的时间问候（P1-8）

```
晚上好                      ← 实时计算
10月19日 星期一
中午好，记得吃点东西。        ← 摘要生成时写死的，一直不变
```

摘要不再携带会过期的时段信息，改成不含时间的静态文案。

### 30 篇文档点重建却提示 46 篇（P1-9）

多出来的 16 篇是内建开发文档。它们确实需要一起重建（否则索引不完整），但**不该计入用户看到的数字**。

现在按用户文档 / 内建文档分离：可见文档计入进度和失败列表，内建文档只参与重建。

### 8 个数值项现在能手动输入（P2-1）

此前只能拖滑块，受步长限制：

| 设置项 | 旧限制 | 后果 |
|---|---|---|
| 分块大小 | 步长 100 | **380 设不了** |
| 分块重叠 | 步长 10 | 75 / 85 设不了 |
| 检索数量 | 上限 10 | 想要 64 召回做不到 |
| Token 预算 | 上限 8000 | 大上下文模型浪费 |
| 其余 4 项 | 步长 0.05 / 0.1 | 精确值设不了 |

现在点击数值区即可输入，自动夹到合法区间。

### 新增「测试 Embedding」按钮（P2-4）

RAG 设置页此前没有任何连通性测试。配错（模型名错、余额不足、Provider 不支持）只能等重建索引才发现，而且报错只进 logcat。

现在点一下就能 embed 一句 `ping`，成功显示维度与耗时，失败给出可读原因。

同时在模型名下方加了提示：并非所有供应商都提供 embedding 模型，配置后请先测试。

### 重索引失败终于说人话了（P2-11）

旧提示只说「索引已修复，但 46 篇文档重索引失败」，真正的错误（如 `HTTP 402 余额不足`）只在日志里。

现在聚合去重后展示前两条原因。

### 分块参数提示文案纠错（P2-8）

原文案说「已索引的文档不会自动重新分块，需删除后重新导入」——这是错的。重建索引本身就会重新调用分块器，所以改完参数直接点「重建索引」即可。文案已更正。

### GlobalScope 收敛（P3-2）

自动化模块两处 `GlobalScope.launch` 改为模块级 `CoroutineScope(SupervisorJob + IO)`，可取消、可测试。

---

## English

### Hybrid search is actually parallel now (P1-4)

The comment claimed coroutine concurrency, but `resultOf` is only a try/catch wrapper — the two
lines ran sequentially, so vector and BM25 latency added up. Now uses `coroutineScope + async`;
total time is `max(vector, bm25)` instead of their sum.

### Chunk size no longer doubles (P1-2)

`plainChunkLimit` was `targetSize * 2`, letting a single fragment fill the whole buffer when it was
empty — set 400, get nearly 800. Now splits strictly at `targetSize`.

### Re-index no longer loads the whole library into memory (P1-5)

To compute one boolean, the old code called `getAllWithEmbedding()`, pulling every chunk and its
embedding into memory (1024-d ≈ 4KB/chunk, 10k chunks ≈ 40MB+). Replaced with a SQL count.

### The "similarity" number was fake (P1-6)

`rrfScore` is a ratio against the top score (always 1.0 for the best hit), not cosine similarity,
yet the UI labeled it "similarity 0.83". Results now carry `rawScore` with the real cosine value.

### Weak keyword hits no longer leak through (P1-7)

`threshold` only applied to the vector path; BM25 hits joined the fusion regardless of strength.
Now normalized to relative strength and filtered by the same threshold.

### Two contradictory greetings on home (P1-8)

The daily summary baked a time-of-day greeting into its text, so "Good evening" and "Good afternoon"
could appear together. Summary text no longer contains time-of-day greetings.

### "46 documents" when you have 30 (P1-9)

The extra 16 were built-in dev docs. They do need re-indexing for completeness, but shouldn't be
counted in what the user sees. Now separated by `isInternal`.

### Eight numeric settings accept manual input (P2-1)

Sliders had step limits that made required values unreachable — chunk size 380 (step 100), top-K up
to 10, token budget capped at 8000. Tap the value to type it; input is clamped to the valid range.

### New "Test Embedding" button (P2-4)

Previously a misconfigured embedding provider only surfaced when rebuilding the index, with the
error buried in logcat. One tap now embeds `"ping"` and reports dimension plus latency, or a
readable failure reason.

### Re-index failures explain themselves (P2-11)

The toast only said "N documents failed"; the actual cause (e.g. HTTP 402 insufficient balance) stayed
in logs. Up to two distinct reasons are now shown.

### Corrected chunking hint (P2-8)

The old note claimed indexed documents would never be re-chunked. That was wrong — re-indexing does
re-run the chunker. Text corrected.

### GlobalScope removed (P3-2)

Two `GlobalScope.launch` calls in the automation module now use a module-level
`CoroutineScope(SupervisorJob + IO)`.

---

## 安装

- 覆盖安装即可，**无需卸载**，数据保留
- 数据库版本仍是 **111**（本次未改 schema），**无迁移风险**
- 签名与 2.6.1 / 2.6.2 / 2.6.3 / 2.6.4 相同

## 已知事项

- 审计报告中需要动数据库结构或另立工程的项仍未做：
  per-KB 配置覆盖（P2-2）、云端 rerank provider（P2-6）、ONNX 模型 UI 入口（P2-7）、
  全面屏手势排除（P2-9）、深链页面（P2-10）、Room 迁移 baseline 合并（P3-1）
- #5（reasoning 泄漏到正文）仍未改，与「吐可以，空不行」原则冲突，需单独定夺
- 混合检索的 `threshold` 现在也作用于 BM25 路，弱关键词命中的结果会比以前少——这是预期行为
