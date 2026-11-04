# AI 层体检（`:ai`）

按"依次一个一个查"进入的模块：Provider / Model / 工具与流的抽象层，应用的生成链路全压在它上面。

## 0. 基线（实测）

| 项 | 数值 |
|---|---|
| `src/main` | 71 文件 / 20 248 行（最大 `OpenAIProvider.kt` 3 351 行） |
| `src/test` | 76 文件 / 7 518 行 |
| 测试任务 | 这是 **android 库**：真任务是 `:ai:testDebugUnitTest`（`:ai:test` 会**空跑通过**） |
| 门禁起点 | 485 例 / 0 失败，`:ai:detekt` 0 违规；基线文件里有 **78 条早已不再产出的失效条目** |
| 当前 | **487 例 / 0 失败**，`:ai:detekt` 通过 |

两次差点把假绿当结论：一次用了 `:ai:test` 看到 `tests=0` 就想写"全绿"；
一次 `--rerun-tasks` 时 detekt 先失败、测试**根本没跑**，我看到的 485 是上一轮的旧 XML
—— 靠"9 秒不可能跑完 485 例"发现。**"跑过任务"不等于"跑过测试"。**

## 1. 首事件超时的默认档曾是**已知有害值**（提交 `60ae2aa4`）

同一个概念三个数，其中一个是本文件自己注释里否定的：

- `firstEventTimeoutMs(defaultMs = 45_000L)` —— v2.x 已把 15s 提到 45s；
- `withFirstEventWatchdog(timeoutMs = 15_000L)` —— **还停在 15s**；
- `AiModule` 又抄了第三份 `?: 45_000L`。

注释原话："15s 对慢队列/冷启动模型过短 —— 首 token 稍慢就被误判为死流、取消流式并重发
非流式请求：用户被迫等待双倍时间，且回复整段一次性弹出（用户反馈）"。
把这个值留在默认参数上，等于**给每个省略参数的新调用方发一张已知有坑的档**。
生产调用点当前显式传值，所以现在还没炸 —— 这次是修在炸之前。

收成 `DEFAULT_FIRST_EVENT_TIMEOUT_MS` / `RELAXED_FIRST_EVENT_TIMEOUT_MS` /
`LARGE_CONTEXT_WINDOW` 三个公开常量，三处引用同一真源。测试新增一例：必须用
**一直挂着不吐事件**的流（空流会走"立即回退"快路径，量不到默认档）+ `runTest` 虚拟时间
断言 `waitedMs >= 45s`；并把已有分档测试里**抄下来的 `90_000L/45_000L`** 改成引用常量 ——
抄的那份在有人调档时会悄悄留下旧值。

## 2. 兜底错误句中英混杂（提交 `a6bd4a37`）

`displayMessage` 会**原样进界面**（`VisionBridge`：`视觉分析失败: ${displayMessage}`），
而异常没有可用细节时的兜底句是裸英文：`network error` / `unknown error`(×2) /
`stream failed` / `Anthropic stream error` / `image generation failed`。
同模块别处的兜底早就是中文（"流式响应为空，正在切换请求方式"），`ProviderError`
还在按"余额不足"这类**中文正文**做重试判定 —— **不一致，不是设计**。

过程中自我纠正一处：我原本想换成 `ErrorCode.*.toMessage()`，读实现才发现它返回的是
**稳定键** `ERR_network_error`（app 侧 `ErrorMessages` 按键查本地化，查不到就原样显示
`ERR_…`）。照想法改会让用户看到 `ERR_network_error`。**先把接口读了再写代码。**
:ai 不能反向依赖 app 的 `ErrorMessages`，真正的本地化落点在 UI 层；本处只把库内兜底句
改成与模块一致的中文，不动结构。新增 1 例钉住两条兜底句，动手前确认全仓测试无一处断言这些英文。

## 3. 查过、没问题的地方（不为"有产出"而制造改动）

- **能力判定**（判错=图片被丢、工具看不见）：分层兜底 —— token 规则 → `KnownModels` →
  **上游声明**（注释原话"内置注册表只是对主流模型的猜测，上游声明是实际能力"）→ 默认；
  `stripAggregatorPrefix` 处理中转站前缀。
  `supportsVisionInput`（查表）在 `io.zer0.ai.registry.ModelRegistry`、
  `supportsVision`（带兜底）在 `io.zer0.ai.core.ModelRegistry` —— **两个同名类在不同层**，
  不是同一层的重复口径。结论：设计过，不动。
- 错误体脱敏：`safeErrorDetail` + `[REDACTED]`（凭证与查询参数不外泄），已有测试覆盖。

## 4. 一次操作事故的完整记录（教训值得留着）

`git add` 多路径里**一个路径写错**（`ai/service/ImageService.kt`，实际在 `ai/image/`）→
整条 add 中止、提交并未发生；我随后取 `rev-parse HEAD` 拿到的是**上一笔**的哈希并写进了文档，
紧接着 `git commit --amend` 又把那篇（已被一次脚本崩溃**截成 0 字节**的）文档并进了上一笔提交。

修回步骤：`reset --soft HEAD~1` → 从索引摘出文档 → `commit -C <原提交>` 重建上一笔
（内容、文件数与原来一致）→ 逐条 `Test-Path` 校验后再提交本笔 → 重建文档并填真哈希。

**规矩**：① 多路径 `git add` 前逐条校验存在；② 提交后必须核 `git log -1` 确实是**新**提交，
不能只看 `rev-parse` 有输出；③ 脚本写完文件要**确认它写成功**
（`write_text` 抛异常会把文件留成 0 字节 —— 这次就是这么丢的）。

## 5. 待查清单

| 候选 | 为什么 |
|---|---|
| `OpenAIProvider.kt`（3 351 行） | 取消/中断与 429/402 归因；流式分片重组（历史上多次修，回归风险高） |
| `ModelCatalogStore` + 云端目录 | 目录拉取失败时用户看到什么；缓存过期与能力回落顺序 |
| `SlidingWindowRateLimiter` | 限流窗口与取消路径 |
| `KnownModels.kt`（1 291 行） | 静态表与云端目录的口径分叉点 |
