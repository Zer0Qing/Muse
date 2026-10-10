# ChatViewModel 拆分重构方案

> 目标文件：`app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt`（当前 7483 行，233 个函数）
> 编写日期：2026-10-10。本文档为**执行方案**，供执行 Agent 直接照做。
> 铁律：**纯抽取，不改行为**。任何一步出问题必须可单独 revert。

---

## 零、现状事实清单（已核实，作为方案依据）

### 结构
| 事实 | 数值 |
|---|---|
| 文件总行数 | 7483 |
| 顶层函数数 | 233 |
| `companion object` | L1306–L1474（常量 + `shouldAutoCompress` + 本轮新增的两个纯函数） |
| **最大块：runToolLoop** | **L4746–L6139，1393 行（占全文件 18.6%）** |
| 其内嵌匿名 `object : ToolLoopHost` | L4848–L5921，1073 行 |
| 其中 `streamRound` | L4861–L5850，989 行 |
| 其余 5 个 ToolLoopHost 方法 | L5852–L5921，共 71 行 |
| runToolLoop 内部局部函数 | 仅 `computeAdaptiveSlice`（L4821） |

### 已拆出的 Coordinator（现有模式，新类必须同构）
`ChatGenerationController` / `ChatStreamCoordinator` / `ChatMiscCoordinator` /
`ChatMediaController` 等，统一构造形态：`(accessor: ChatStateAccessor, ...deps, appContext)`。

### 关键结论（决定了本方案的策略）
1. **`ToolLoopHost` 是个只有 5 个方法的窄接口**（`ToolOrchestrator.kt:388`），
   `streamRound` 已经是它的契约方法 —— **接口缝天然存在，不需要先造缝再拆**。
2. runToolLoop + 其内匿名对象是一个**高内聚封闭体**（内部仅 1 个局部函数），
   对外的依赖是单向的（它调 VM 的东西，VM 只调它的入口）。
3. 因此最优策略**不是"按函数切片"，而是"按循环体整体提取"** —— 一次搬走
   1393 行，风险远低于把 989 行状态机从循环体里剥离出来。
4. 第 2 大的函数只有 281 行（`updateMessages`），**不存在第二个千行级大块**。

---

## 一、拆分切片清单（按执行顺序，风险递增）

| # | 切片 | 行区间 | 行数 | 目标类 | 风险 |
|---|---|---|---|---|---|
| S1 | 工具循环体（runToolLoop + ToolLoopHost + streamRound） | 4746–6139 | 1393 | `ChatToolLoopRunner` | 中 |
| S2 | 上下文/压缩编排（refreshContextInfo + checkpoint 群 + 自动/手动压缩） | 2857–3544 | ~690 | `ChatCompressionCoordinator` | 中 |
| S3 | 消息列表更新（updateMessages） | 1712–1993 | 281 | `ChatMessageListCoordinator` | 低 |
| S4 | 工具审批（requestToolApprovalForSession） | 4528–4647 | 119 | 并入 S1 产物或 `ChatToolApprovalCoordinator` | 低 |
| S5 | 待恢复工具调用（resumePendingToolCalls） | 6243–6443 | 200 | 并入 S1 产物 | 低 |
| S6 | 会话影子记录（recordConversationShadow） | 1304–1599 | 295 | `ChatShadowRecorder` | 中（涉 DB 异步） |

**预期结果**：7483 → 约 5500 行（S1 单独就能降到 ~6090）。
**不要设"降到 3000 行"的硬目标** —— 剩余部分大多是薄委托与小函数，
强行切只增加间接层。真指标是"能否一句话说清这段状态归谁管"。

---

## 二、S1 详细步骤（最大、最关键的一步）

### 2.1 新建 `ChatToolLoopRunner`
路径：`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt`

构造参数（沿用 coordinator 模式，只收必要依赖）：
```kotlin
class ChatToolLoopRunner(
    private val accessor: ChatStateAccessor,
    private val deps: GenerationDeps,          // 复用现有 GenerationDeps
    private val appContext: Context,
    // runToolLoop 体内直接用到的其余依赖，逐个显式列出（见 2.2）
)
```

### 2.2 搬迁动作（严格顺序）
1. 把 `runToolLoop`（4746–6139）**整块**搬入新类，改名 `run(state: StreamRunState): Boolean`
2. 把其内嵌 `object : ToolLoopHost`（4848–5921）原样搬入，作为新类的私有成员或
   让新类直接 `implements ToolLoopHost`（**优先后者**：接口只有 5 个方法，
   实现在类上比匿名对象更清晰，且 `contextBudgetTokens()` 可直接复用）
3. 局部函数 `computeAdaptiveSlice`（4821）随迁
4. ChatViewModel 原位置留一行委托：
   ```kotlin
   private suspend fun runToolLoop(state: StreamRunState): Boolean = toolLoopRunner.run(state)
   ```
5. 搬迁过程中**不允许**顺手优化、不允许改逻辑、不允许改异常语义

### 2.3 依赖处理原则
- runToolLoop 内调用的 VM 私有方法（如 `persistInterruptedAssistant`，全局 4 处引用）
  → 通过构造参数传入 `() -> Unit` / 具名回调，或提为 internal 方法后由新类调用
- 直接读写的 VM 可变状态（`_state` / `_messages` / `checkpointCoveredIds` 等）
  → 一律经 `ChatStateAccessor` 读写，新类**不得**直接持有 StateFlow 以外的可变引用
- 若某依赖无法干净传入（说明该依赖本就属于循环体）→ 连同该依赖一起搬

### 2.4 S1 验收（缺一不可）
- `./gradlew :app:compileDebugKotlin` 通过
- ktlint + detekt 通过
- 全量 `:app:testDebugUnitTest` 通过（含工具循环、审批、压缩、恢复相关）
- **行为等价核对**（执行 Agent 必须逐条人工比对）：
  - [ ] `streamRound` 的 delta 累积顺序（content / reasoning / toolCall）未变
  - [ ] 自动重试（NETWORK / RATE_LIMIT）触发条件未变
  - [ ] StreamInterrupted 智能续传（30s 等待 + preservePartialContent）未变
  - [ ] 工具审批挂起/超时（APPROVAL_TIMEOUT_MS）语义未变
  - [ ] 中断落盘 `persistInterruptedAssistant` 调用时机未变
- 真机冒烟：普通对话 / 带工具调用 / 断网续传 / 中途停止 四种场景

---

## 三、S2 详细步骤（压缩编排）

已确认事实：本轮已把 `newlyCoveredCount` / `classifyErrorType` 下沉到 companion
为纯函数（`newlyCoveredCountStatic` / `classifyErrorTypeStatic`）。本次搬的是**编排**。

### 3.1 搬迁范围
- `refreshContextInfo`（2857–2958）
- `ensureCheckpointCoveredIds` / `refreshCheckpointCoveredIds`（2934–2958）
- `triggerAutoCompress`（3103–3205）
- `finishCompressionWithCheckpoint` / `persistLocalCheckpointFallback` / `persistContextCheckpoint`
- `manualCompress` / `manualCompressContext` / `announceManualCompressDone` / `runManualCompress`（3360–3544）

### 3.2 依赖（已知，需显式传入）
`checkpointReader`、`contextCompressTransformer`、`sessionRepository`、`memoryTicker`、
`settings`、`displayedSessionId()`、`updateContextTokenCount()`、`refreshContextInfo()`、
`reportError`、字符串资源、`GenerationTrace.noteCompression()`

### 3.3 风险与对策
- **风险**：`checkpointCoveredIds` / `checkpointCoveredSessionId` 是两个进程内可变水位线，
  被多处读写。
- **对策**：**不拆状态，只搬代码**。新 coordinator 通过 accessor 的 getter/setter
  读写这两个字段，字段本身仍归 VM。状态归属的彻底解决留给后续（见第五节）。

---

## 四、执行纪律（执行 Agent 必须遵守）

1. **一次一个切片**，每片独立提交（commit message 注明"纯抽取无行为变更"）
2. 每片独立过门禁 + 全量单测；任一片挂了只 revert 那一片
3. `ktlint-baseline.xml` 行号敏感：改动后删除重跑生成新 baseline，并 diff 确认
   **未新增豁免**
4. 改源码一律用 `edit` 工具，不用 shell 重定向
5. gradlew 判成败：先重定向到文件再取 `$LASTEXITCODE`，**不要接管道**
6. 不自动打包发布
7. 每片完成后更新本文档的执行记录区

---

## 五、本次**不做**的事（明确边界）

- **不做** streamRound 内部的状态机重写（决策/副作用分离）——那是"新一代流式编排"
  新功能级项目，不在重构范围。本次只把它整块搬走，让它的边界变清晰。
- **不做** 状态所有权重新设计（`_state` / `_messages` / 水位线的归属）——
  那需要引入单向数据流，影响面跨整个 UI 层。
- **不做** 行数达标运动。剩余部分若切出来只增间接层，就留在原地。

完成 S1–S6 后，ChatViewModel 应是一个"薄编排层"：持有状态、持有各 coordinator、
对外暴露意图方法。那时再讨论状态机重写，才是站在可验证的地基上。

---

## 六、执行记录（执行 Agent 填写）

| 切片 | 状态 | 提交 | 行数变化 | 备注 |
|---|---|---|---|---|
| S1 工具循环体 | 待执行 | — | 7483 → ? | 最关键，务必逐条验收 |
| S2 压缩编排 | 待执行 | — | | |
| S3 消息列表更新 | 待执行 | — | | |
| S4 工具审批 | 待执行 | — | | |
| S5 待恢复工具调用 | 待执行 | — | | |
| S6 会话影子记录 | 待执行 | — | | |
