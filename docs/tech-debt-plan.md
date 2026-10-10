# 技术债改造计划（诊断 / 拆分 / 签名 / 测试 / 子代理）

> 2026-10-10 立项。范围：本轮审查后确认的 5 项不足（版本线纪律除外，单独处理）。
> 本文档只定计划，不动手；每个项目动手前在此勾选并记录实际执行情况。

## 总览与排序

| 序 | 项目 | 性质 | 依赖 | 建议里程碑 |
|---|---|---|---|---|
| A | 用户侧诊断日志导出 | 新增功能 | 无 | M1（最先做） |
| E | 子代理超时根因排查 | 调查→修复 | 无 | M2 |
| C | 签名 payload 序列化分家 | 架构小手术 | 无 | M3 |
| B | ChatViewModel 拆分 | 大重构 | A（排障利器先行） | M4-M6 分三阶段 |
| D | 核心链路单测补齐 | 质量补强 | B（拆一片补一片） | 随 B 进行 |

排序理由：A 成本最低收益最高（这次用户报"流截断"全靠读代码盲猜，有诊断导出早定位了）；
E 是调查型任务，先出结论再决定修复量；C 独立且体积小；B 是大工程放最后，D 随 B 走。

---

## 项目 A：用户侧诊断日志导出

### 目标
普通用户遇到"流截断 / 卡住 / 超时"类问题时，能从 App 内导出一份**无敏感内容**的诊断包，
开发者拿到后不再盲猜。

### 现状盘点（已有基础设施，不要重造）
- `io.zer0.common.Logger`：全局日志，当前写 logcat，不落盘。
- `crash/AnrWatcher.kt`：已有"主线程无响应"检测 + 落盘文本（`pw.println`），可复用其写文件路径约定。
- `data/audit/AuditLogger`：已有审计事件通道。
- `experiments.debugMode`：现有开关，诊断导出应**独立于它**（默认就可导出，记录内容本身脱敏）。

### 方案
1. **生成链路事件记录仪**（核心，新类如 `diagnostic/GenerationTrace.kt`）
   每轮生成记录一条结构化轨迹，仅存内存环形缓冲（最近 20 轮，进程级）：
   - sessionId（哈希脱敏）、modelId、providerType、baseUrl host（不含 key）
   - 时间线：request 发出 / 首 delta / reasoning 起止 / content 起止 / Done / Error / 中断
   - finish_reason、contentChars、reasoningChars、elapsedMs
   - 流中断原因分类（readTimeout / onClosed 无 finishReason / 用户取消 / 回退触发及原因）
   - 压缩触发记录（哪条路径、触发时 token 数、双真源各自的判定值——直接验证 9b032546 修复效果）
   - **不记录任何消息正文**（PII 红线），只记统计量与事件
2. **落盘时机**：App 退出/崩溃时 dump 最近缓冲到 files/diagnostics/（复用 crash 目录约定）；
   每轮生成结束即时追加一行 JSONL，单文件上限 2MB 滚动。
3. **导出入口**：设置 → 高级 → 「导出诊断信息」，打包 zip：
   - generation-trace.jsonl
   - 设备/版本信息（Android 版本、ABI、app versionName/versionCode、DB 版本）
   - Provider 配置快照（**key 掩码成 sk-***、脱敏 host 之后的完整 baseUrl）
   - 最近 ANR 记录
   分享走系统 share sheet。
4. **隐私声明**：导出页明示包含哪些字段、不含消息内容与 API key 原文。

### 实施步骤
- A1: `GenerationTrace` 环形缓冲 + 在 ChatGenerationController/ChatStreamCoordinator 的关键节点埋点（5-6 个调用点）
- A2: OpenAIProvider 的流事件分类回调（finish_reason / onClosed 无 finish / readTimeout 冒泡到 Trace）
- A3: 导出 zip + 设置页入口 + 隐私文案（values + values-en 双语）
- A4: 单测（环形缓冲容量、脱敏规则、JSONL 格式）+ 真机演练：构造一次截断（设极小 maxTokens），导出检查记录完整

### 验收标准
用户在真机触发一次"输出被截断"，导出的 trace 能回答：finish_reason 是什么、收到多少字符、
中断发生在哪个环节、压缩是否触发。全程无正文、无 key。

### 风险
埋点侵入生成控制器——控制在"只读状态、不改动逻辑"的纯观察者模式，任何埋点异常不得影响生成。

---

## 项目 E：子代理超时根因排查

### 现象
本轮审查 7 个子代理任务全部失败/超时（0/7 成功），最后人工收口。
自家 SubagentRunner 不可靠，用户场景的 subagent 工具同样踩这条地基。

### 排查步骤（先调查，后定修复量）
- E1: 收集证据：翻本轮失败任务的日志/落盘记录（SubagentRunner 是否有 trace？没有则先借项目 A 的 Trace 思路补最小日志）
- E2: 最小复现：本地构造一个最简单的子代理任务（单工具调用），观察超时发生点
- E3: 按嫌疑排序验证：
  1. 工具审批死等（子代理触发需审批的工具，无人交互 → 挂到超时）
  2. 超时配置不合理（SubagentRunner 的 timeout 值 vs 模型首 token 延迟）
  3. 事件循环死锁（等待 isStreaming 回落之类的条件变量）
  4. 模型/网络层本身失败但错误被吞成"超时"
- E4: 出结论文档（根因 + 修复方案 + 工作量），再动手修
- E5: 修复验收：连续跑 7 个审查型子任务，成功率 ≥ 6/7

### 附带改进（无论根因）
子代理失败必须有**明确的错误回报**（哪一步、什么错），不能静默超时让调用方猜。

---

## 项目 C：签名 payload 序列化分家

### 背景
`AppJson.encodeDefaults=true` 是全局配置：任何新增带默认值字段都会进入序列化输出，
若该字段在 manifest 内，签名 payload 变化 → 存量包验签失败。
本轮 `hooks` 字段靠 `@EncodeDefault(NEVER)` + MarketSamplePackageTest 拦截住，
但这是"人肉记忆型"防御，每加一个字段都要记得贴注解。

### 目标
签名 payload 的字段集从"序列化配置的副产品"变成"显式声明的白名单"，
新增字段**默认不参与签名**，进不进白名单是有意识的决定。

### 方案
1. `PluginSecurityGate.signaturePayload` 不再用 `AppJson.encodeToString(manifest)`，
   改为**显式字段白名单手写构建 JsonObject**（逐字段列出当前参与签名的全部字段，
   顺序与值编码与现状完全一致 → 存量包 payload 字节不变、验签不破）。
2. 白名单函数旁加强注释：新增 manifest 字段默认不进签名；若确需签名保护，
   须同时更新签名工具 `tools/market-signer/sign.py` + 夹具测试。
3. `tools/market-signer/sign.py` 同步改为白名单构建（保持与 Kotlin 端逐字节一致）。
4. 回归测试（新增）：
   - "给 PluginManifest 新增带默认值字段，payload 字符串不变"（编译期无法保证，用反射遍历新字段断言不在 payload 中）
   - 存量夹具 MarketSamplePackageTest 继续全绿（证明字节兼容）

### 关键约束
**不能直接换成 `Json { encodeDefaults=false }`**：那同样会改变存量 payload（现有字段里
有默认值的会被剔除），效果等同于又一次全员验签失败。必须手写白名单保字节一致。

### 验收标准
- MarketSamplePackageTest、MarketSigningFixtureTest 全绿
- 模拟新增一个 `val newField: String = ""` 字段（不加任何注解），payload 不变，验签通过

---

## 项目 B：ChatViewModel 拆分（7031 行 → 目标 <3000 行）

### 原则
1. **纯抽取，不改行为**：每一刀只搬代码，不顺手"优化"。行为变更单独提交。
2. 延续已有的 coordinator 模式（ChatGenerationController / ChatStreamCoordinator /
   ChatMiscCoordinator / ChatMediaController 是现成样板），新切片同构。
3. 每一片独立提交、独立过全量门禁；任何一片出问题可单独 revert。
4. accessor/deps 共享状态不改结构，先搬纯逻辑，状态归属问题留到拆分完成后专项处理。

### 候选切片（风险从低到高，按此顺序动手）

| 阶段 | 切片 | 估计行数 | 风险 |
|---|---|---|---|
| B1 | 搜索/消息 CRUD 残余（归并进 ChatMiscCoordinator） | ~400 | 低 |
| B2 | Token 统计 / 上下文预算展示（新建 ChatContextStatsCoordinator） | ~500 | 低 |
| B3 | 压缩编排（shouldAutoCompress + 触发/手动压缩/forceFallbackHistory，新建 ChatCompressionCoordinator） | ~700 | 中（碰 9b032546 修过的逻辑） |
| B4 | 错误恢复/智能续传（classifyErrorType、StreamInterrupted 30s 续传、retry 循环，归并进 ChatGenerationController 或新建 ChatErrorRecoveryCoordinator） | ~1200 | 中高（本轮用户报障的核心链路，动它必须有 A 的诊断埋点护航） |
| B5 | 流式状态机本体（builder/reasoningBuilder/toolCallAccumulator 编排） | ~2000 | 高，最后动，且需要 B4 完成后观察一两个版本 |

### 每片标准动作
1. 先在 ChatViewModel 里给待搬区域画边界（grep 引用方，确认只有 VM 内部使用）
2. 整块搬入新 coordinator，构造参数收 accessor/deps（对齐现有样板签名）
3. VM 原位置留一行委托调用
4. 全量门禁 + 单测 + 真机冒烟（发一条消息走完流式）
5. 提交，commit message 注明"纯抽取无行为变更"

### 验收标准
- ChatViewModel.kt < 3000 行
- 全部现有单测不修改即通过（修改测试 = 行为变更信号，需单独说明）
- 流式/压缩/重试/续传四类场景真机回归通过（借项目 A 的 trace 验证行为等价）

---

## 项目 D：核心链路单测补齐（随 B 进行）

### 原则
拆一片、补一片。新 coordinator 构造参数化（accessor/deps 注入）天然可测，不留"拆了还是测不了"的尾巴。

### 补齐清单（按切片对应）
| 切片 | 必补测试 |
|---|---|
| B2 | contextMaxTokens 刷新、token 计数估算边界（空历史/超大历史） |
| B3 | shouldAutoCompress 各阈值分支（借 CompressionPolicy 单一真源后的语义）、forceFallbackHistory prefix 保留 |
| B4 | classifyErrorType 全分支、StreamInterrupted 续传的保留部分内容逻辑、30s 超时降级 |
| B5 | （拆分完成后）流式状态机的 builder/reasoning 拼接顺序 |

### 存量问题顺带修
- `AssistantCardExporterRoundTripTest` flaky（Robolectric 共享 JVM 污染，单独跑过、全量跑挂）：
  排查共享状态（静态缓存/系统属性），加隔离或改 @LooperMode，消除全量套件随机失败。

---

## 执行记录（2026-10-10 全量推进）

- **A 完成**（d60aed71）：GenerationTrace + 导出入口 + 测试，计划项全落地。
- **E 完成**（14012eb0）：根因=120s 超时对多轮工具任务必炸 + 超时无进展信息；
  修复=默认 240s + timeout_ms 参数（30s~900s）+ error 附进展。附带发现：
  本轮审查 7/7 全灭的子代理跑在 Hana 平台侧，非 Muse SubagentRunner（排查对象修正）。
- **C 完成**（5b28c3c9）：白名单方案 = 全量序列化后剔除非白名单顶层字段（保序），
  弃用"手写嵌套结构"方案（与 kotlinx 行为偏差风险高）。附带修复 sign.py 缺 hooks
  字段（存量 bug：带 hooks 的 manifest 会被签名工具以未知字段拒绝）。
- **B1/B2 勘察结论**：搜索已拆入 ChatMiscCoordinator、Token 统计已拆入
  ChatGenerationController——"低垂果实"早被之前重构摘过，原计划这两片无剩余工作。
- **B3 策略调整**：压缩函数群强耦合 VM 内部（viewModelScope/checkpointReader/
  displayedSessionId/直改 _messages 等 10+ 依赖），整体迁出需 10+ 参数构造器，
  搬迁收益低于风险。改为：newlyCoveredCount 提 internal（纯函数）+ 补
  NewlyCoveredCountTest（边界切分/回退/未覆盖混合）。行为零变更。
- **B5 结论（不动）**：streamRound 单函数 989 行确实是最大的债，但它直接读写
  StreamRunState 可变 builder + 直改 UI 状态 + 直调 20+ 依赖，且内部互调多个
  VM 私有方法。强行迁出=构造器爆炸+竞态回归风险（丢 delta/重复内容），远大于
  可读性收益。真正的解法是未来"新一代流式编排"重构（新功能级项目，不在本次
  技术债范围）。它在唯一调用方控制下工作正常，有用户实测回归。
- **D 部分完成**：新增 NewlyCoveredCountTest / ClassifyErrorTypeTest；
  flaky 验证见最终全量门禁结果。

## 执行纪律（所有项目通用）
- 每个项目开工前更新本文档勾选状态；完成后记录实际改动点与偏差
- 每片提交独立、门禁全绿、真机冒烟
- 引用参考项目不提名字；改源码用 edit 工具；ktlint baseline 行号敏感（删了重跑）
- 不自动打包发布，等明确指令
