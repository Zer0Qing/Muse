# AI Provider / 流式 / 工具循环 链路审查报告（2026-10）

## 摘要

审查范围：`ai/` 模块全部 133 个源文件中的核心链路（OpenAIProvider/AnthropicProvider/GeminiProvider、ChatService、FirstEventWatchdog、ProviderCompat、ToolCallSanitizer、ToolCallArgsAccumulator、ChatEventNormalization、StreamFrameDiagnostics、ProviderPayloadNormalizer、ToolTraceProjection），app 侧 ChatViewModel（5141 行）与 2026-10 dfe3bbe3..15341441 拆分产出的 ChatToolLoopRunner（S1）/ChatCompressionCoordinator（S2）/ChatToolApprovalCoordinator（S4）/ChatToolResumeCoordinator（S5）、ChatStreamCoordinator、ChatGenerationController、ToolOrchestrator、A2A（web/a2a）、TaskerTriggerReceiver、插件 onPromptFinalize Hook、GenerationTrace 埋点。方法：逐文件通读 + 关键路径跨文件追写（流式事件流、审批 Deferred 生命周期、outbox/checkpoint 状态机、续传去重）+ git 提交对照。总体结论：拆分重构基本忠实（"逐字照搬"声明在抽查范围内成立），但存在 2 处 P1 级状态/竞态缺陷（续传重写时序、压缩 trace 串扰）、1 处 P1 级安全语义缺口（楼层截断破坏 tool 配对）、以及若干 P2 级可靠性/可观测性问题。A2A 入口是并发缺口最集中的新面。

## 发现清单

> 排序：P0 > P1 > P2；同级按影响面排序。可信度：高=代码直接证据；中=静态推断待复现；低=理论风险。

### F1. 续传重写"替换旧内容"发生在流结束后，且 pendingBuilder 刷出后才判定 —— 时序缺陷导致重写判定基于被截断的 attempt（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:852-878`（流结束 flush → supersede 判定）、`app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:1129-1135`（shouldReplaceOnResumeSupersede 实现）
- 证据：
  ```kotlin
  // L862-878：续传重写兑底在"流已结束、pendingBuilder 已 flush 到 builder"之后判定
  if (streamError == null) {
      resumeOriginalText?.let { original ->
          val attempt = resumeAttemptText?.toString()
          if (host.shouldReplaceOnResumeSupersede(original, attempt, params.builder.toString())) {
              params.builder.setLength(0)
              params.builder.append(attempt)
              lastUiUpdateChars = 0    // ← 置 0
              ...
          }
      }
  }
  ```
  而其上游 delta 阶段的"重写检测"（L602-642）在命中 `shouldReplaceOnResumeRewrite` 时：
  ```kotlin
  params.builder.setLength(0)
  params.builder.append(rewritten)   // rewritten = resumeAttemptText
  lastUiUpdateChars = params.builder.length   // ← 这里同步了
  lastNotifChars = ...; lastPersistChars = ...
  effectiveDelta = ""
  ```
  两处对 `lastUiUpdateChars` 的处理不一致；且 supersede 命中时**只置 `lastUiUpdateChars = 0`**，`lastPersistChars` / `lastNotifChars` / `lastTokenUpdateChars` 保持旧值（即被替换前的旧 builder 长度）。
- 影响：续传场景（网络中断重连 + provider 从头重写）中，若 supersede 兜底命中：① 后续不再有 ContentDelta 时 UI 确实会被最终 updateAssistant 修正，但 `throttledPersist()` 的节流判断 `params.builder.length - lastPersistChars < PERSIST_CHAR_THRESHOLD`（L482）按旧长度计算 —— 新内容比旧内容短时可能跳过落盘，进程被杀后 DB 里仍是"旧文+新文拼接"的重复内容；② 通知字数 `lastNotifChars` 滞后，进度通知显示错误字数。此路径正是 dedupe 基线声明"已加 debug 诊断等真实日志"的丢字/重复区域，属于**新提出的丢/错字代码路径**（非"未复现"问题本身）。
- 建议：supersede 命中后同步重置 `lastPersistChars/lastNotifChars/lastTokenUpdateChars = params.builder.length`（与 L617-619 的 delta 阶段重写路径对齐），并立即补一次强制落盘。

### F2. GenerationTrace 的 pending 状态用 ThreadLocal 承载，与 noteCompression 的触发协程、record 的调用协程分离 —— 压缩标记概率性丢失/串扰（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/diagnostic/GenerationTrace.kt:131-152`（ThreadLocal pending）、`app/src/main/java/io/zer0/muse/ui/chat/ChatCompressionCoordinator.kt:45`（noteCompression 调用点）、`app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt:1129-1149`（record 调用点）
- 证据：
  ```kotlin
  // GenerationTrace.kt:131-133
  private val pendingStreamEndKind = ThreadLocal.withInitial { "" }
  private val pendingFinishReason = ThreadLocal.withInitial { "" }
  private val pendingCompression = ThreadLocal.withInitial { false }
  // ChatCompressionCoordinator.kt:38-47 —— triggerAutoCompress 在"发起压缩的协程"上记录
  fun triggerAutoCompress(sessionId: String) {
      ...
      runCatching { io.zer0.muse.diagnostic.GenerationTrace.noteCompression() }   // (a)
      host.coroutineScope.launch(AppDispatchers.io) { ... }                        // (b) 压缩实际在新协程
  ```
  而 noteStreamEnd 由 `ChatToolLoopRunner.kt:895` 在 streamRound 收尾（生成协程，appScope 的 IO 线程池）写入，record 由 `ChatGenerationController.recordDebugSummary`（同一生成协程的 finally 链）读取 —— 这一对在多数路径下能命中同一线程（Dispatchers.IO 线程复用），但 `noteCompression` 写在 finalizeResponse 调用方协程、`noteStreamEnd` 写在 streamRound 协程，两者只有**碰巧复用同一个池内线程**时才落进同一条 TraceRecord；否则：(a) 压缩标记丢（下一条记录的 record() 里 takePending 把"别的生成写下的 pending"取走 → 串扰）；(b) 更糟：上一轮生成写下的 pendingStreamEnd/finishReason 未被消费时（cancelled 路径 record 在 withContext(NonCancellable) 内线程可能不同），会**串进下一轮生成的 TraceRecord**。
- 影响：诊断导出的 generation-trace.jsonl 中 `compressionTriggered`、`streamEndKind`、`finishReason` 字段不可靠 —— 交叉污染的 trace 会误导"流截断/卡住"类问题的排查方向，而这正是该功能存在的目的。
- 建议：把 pending 三个字段改为 `record()` 的显式入参（沿调用链从 streamRound/finalizeResponse 传到 recordDebugSummary），或至少按 sessionId/traceId 建 keyed map 而非 ThreadLocal。

### F3. FloorContextLimiterHook 截断保留 SYSTEM 块时丢失 tool 配对与 turn 连续性 —— 生成 400 或语义断裂（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/hook/FloorContextLimiterHook.kt:42-68`
- 证据：
  ```kotlin
  val systemMsgs = history.filter { it.role == MessageRole.SYSTEM }
  val nonSystemMsgs = history.filter { it.role != MessageRole.SYSTEM }
  ...
  val trimmed = systemMsgs + nonSystemMsgs.drop(keepFromIndex)
  ```
  该 hook 是 `PromptFinalizeHook`（`SkillHook.kt:160-164`），在 `ChatStreamCoordinator.applyTransformers` 的管道之后、发给 Provider 之前执行（`ChatStreamCoordinator.kt:1015-1034`）。它按"USER 楼层"截断，但：(1) `nonSystemMsgs` 中保留区起点若落在 ASSISTANT(toolCalls) 与其 TOOL 结果**之间**，或起点是 TOOL 消息，会产生孤儿 tool_result（上游 `splitContextWindow`（UIMessage.kt:271-290）专门做了防孤儿处理，此 hook 没有）；(2) `systemMsgs` 被整体搬到列表头部，但原历史里**压缩摘要 SYSTEM 消息、TRUNCATED_TOOL_CHAIN_MARKER、RAG 注入等 SYSTEM 块与消息有位置语义**（ToolTraceProjection 产出的 SYSTEM 摘要替代的是中间位置的 assistant 轮次），全部前置后模型看到的"旧工具回合摘要"出现在全部对话之前，引用编号/上下文指代错乱；(3) 续传轮 `resumeFromText` 注入的末尾 ASSISTANT 消息（ChatService.kt:168-174）也可能被截掉起点之后保留，但它的前文被截掉，模型续写上下文断裂。
- 影响：开启 floorLimiterEnabled 的用户在长对话 + 工具循环场景发出请求可能 400（孤儿 tool_result 会被 ProviderPayloadNormalizer.stripOrphanToolMessages 兜底删除 —— 但删除后 ASSISTANT(toolCalls) 本身也变孤儿，会连带 stripInvalidToolCalls 删除，造成"历史里有过工具调用的痕迹全被静默抹掉"），更常见的是模型上下文语义错乱。
- 建议：截断后复用 `List<UIMessage>.splitContextWindow` 的孤儿回退逻辑（或直接把 hook 的 cut point 交给现成函数）；SYSTEM 块按原相对位置保留。

### F4. A2A Handler 并发请求互踩：共享 ChatViewModel 单例 + "等 isStreaming 回落"判定，两个并发 SendMessage 必然互相干扰（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:59-124`；`WebServer.kt:613-618`（JSON-RPC 端点对每个请求调用 `a2aHandler.sendMessage`）
- 证据：
  - `A2aHandler.sendMessage` 无任何互斥/队列：两个并发 `SendMessage` 各自执行 `switchSession(...)` 或 `createNewSession(...)`（L74-82），后到者会把前者的会话切走；前者随后 `chatViewModel.send()`（L88）发出的消息进入的是**被后者切换后的 currentSessionId**（`ChatGenerationController.send()` L217 用 `accessor.snapshot.currentSessionId`）。
  - 完成判定 `withTimeout { chatViewModel.state.first { !it.isStreaming } }`（L99-101）是**全局单一流式标志**：请求 A 在等 `!isStreaming` 时，请求 B 开始生成，A 的等待条件在 B 生成中为 true（isStreaming=true），直到 B 结束 A 才返回，且 A 取回的 `sessionRepo.observeMessages(sessionId).first()` 最后一条 assistant 是**B 的回复**。
  - `cancelTask(taskId)`（L131-139）调用 `chatViewModel.stop()` —— 停的是"当前会话"的生成（`ChatGenerationController.stop()` L118：`effectiveGenerationSessionId(accessor.snapshot)`），与 taskId 无任何映射；外部 agent 取消任务 X 会停掉恰好在当前会话上的任何生成。
- 影响：dedupe 基线重点方向 2（A2A/Tasker 入口的并发、取消传播、generation guard 覆盖）——A2A 是**唯一把外部并发请求直接灌进 UI 生成链路的入口**，且无串行化。generationSerial guard（ChatGenerationState.streamGenerationSerial）能防止旧代清掉新代 UI，但防不了"两个外部请求归属错乱"。
- 建议：A2A 层加 per-handler 串行 Mutex（MVP 语义本就是同步阻塞式，串行不损失能力）；CancelTask 按 taskId→sessionId 映射后调 `chatGenerationManager.stop(sessionId)`；或者改走 `chatService` 直接生成（绕开 UI 单例）。

### F5. ChatToolApprovalCoordinator 审批模式判定用 displayedSessionId 快照，工具循环跨会话后台执行时 TRUSTED 判定可被绕过/误放行（P2，可信度：中）

- 位置：`app/src/main/java/io/zer0/muse/ui/chat/ChatToolApprovalCoordinator.kt:57-70`
- 证据：
  ```kotlin
  val displayedSessionId = host.currentSessionIdForApproval()   // ← 实时读 UI state
  val mode =
      if (host.stateStore.state.value.appRunAllowAllTools) {
          SessionPermissionMode.TRUSTED
      } else if (displayedSessionId == sessionId) {
          host.stateStore.state.value.sessionPermissionMode
      } else {
          host.sessionPermissionStore.getMode(sessionId, host.settings.defaultSessionPermissionModeFlow.first())
      }
  ```
  `appRunAllowAllTools` 是**全局 UI 状态**（不是会话绑定）：用户在会话 A 勾选"本次运行全部放行"后切到会话 B（state 未被 switchSession 重置 —— `ChatSessionController.switchSession` 的 update 列表里没有清 appRunAllowAllTools），会话 B 里挂起生成的工具审批会因 `appRunAllowAllTools=true` 直接 TRUSTED 放行，而 B 会话自身模式是 ASK。对照：`ChatToolResumeCoordinator`/审批模式语义声明"TRUSTED 下 ALWAYS_ALLOW 设计"为已知取舍（AUDIT_FRAMEWORK 纪律 4），但**取舍边界是"本次运行"**，跨会话延续超出了声明的边界。
- 影响：后台生成（切页不取消）中跨会话的审批粒度比用户预期宽一档；属权限放大而非漏洞（仍走统一解析器硬拒绝），故 P2。
- 建议：switchSession/createNewSession 时重置 `appRunAllowAllTools`，或在审批时按 sessionId 记录勾选时刻的会话。

### F6. streamRound 工具轮 UsageDelta 直接覆盖 state.usageTokens —— Anthropic 分次 usage 的输入 token 会被下一轮的"纯输出"覆盖为 0（P2，可信度：中）

- 位置：`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:793-797`；`ai/src/main/java/io/zer0/ai/anthropic/AnthropicProvider.kt:372-386`
- 证据：
  ```kotlin
  // runner L793-796
  is ChatStreamEvent.UsageDelta -> {
      state.usageTokens = event.usage   // 注释称"直接覆盖保证留存最后一次(总量)"
  }
  // AnthropicProvider L381-386 —— UsageDelta 合并了 message_start 的 inputUsage 与 message_delta 输出
  ChatStreamEvent.UsageDelta(
      UsageTokens(
          promptTokens = inputUsage?.input_tokens ?: 0,
          ...
  ```
  单轮内 Anthropic 的合并是自洽的（Provider 侧完成 input+output 合并）。但**多轮工具循环**里每轮是独立 message_start/message_delta：第 N 轮的 UsageDelta 只反映第 N 轮的 prompt；`finalizeResponse`（ChatGenerationController.kt:1516-1543）把"最后一轮 usage"持久化为整条 assistant 消息的 token 元数据 —— 工具循环轮次多时，最终消息记录的 promptTokens 只是最后一轮的输入（且 tool 历史在最后一轮被 ToolTraceProjection 压缩后更小），统计口径系统性偏低。注释里写"多轮工具循环中每轮都可能发，消费方取最后一次（总量）"（Provider.kt:98），该"总量"假设对 Anthropic 多轮不成立。
- 影响：A5 token 用量展示/统计失真（不参与预算判定，AgentTokenBudget 已跳过 null，但此处非 null 且偏小），属统计/展示缺陷。
- 建议：runner 侧按轮累加 promptTokens（或只对"无工具轮次"覆盖），或 finalize 时标注"usage 为最后一轮口径"。

### F7. streamRound 的 pendingBuilder 末尾 flush 在 pendingFlushJob cancelAndJoin 之后无锁直读 —— 与独立刷新协程存在窗口竞态（P2，可信度：中）

- 位置：`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:450-475`（flushPendingToUi 持 pendingFlushMutex）、`527-533`（pendingFlushJob）、`844-860`（join 后无锁 flush）
- 证据：
  ```kotlin
  // L844-853: collect 正常返回后
  } finally {
      pendingFlushJob.cancelAndJoin()
  }
  ...
  if (pendingBuilder.isNotEmpty()) {
      params.builder.append(pendingBuilder)     // ← 无锁读 pendingBuilder
  ```
  `cancelAndJoin` 保证了刷新协程终止，因此**稳态**无竞态 —— 但存在一个例外路径：`takeWhile` 的兼容重试循环（L535-843 do-while）里 `collect` 抛 CancellationException（用户停止）时同样走 finally join 后退出正常；真正的窗口在于 `Done` 事件分支（L801-811）注释明确写 "The flush coroutine may still own pendingBuilder until collect returns. Defer all content mutations..." —— 该延迟已做。剩余风险集中在 **Error/StreamInterrupted 事件后 collect 才返回**的间隙：刷新协程的 `flushPendingToUi()` 可能在 `streamError` 已赋值之后、join 之前又把 pendingBuilder 的一部分切进 builder 并调 `updateAssistant(isStreaming=true)` —— 顺序无害。综合判断稳态安全，但 `chunkIntervals`（ArrayDeque）在 ContentDelta 的 `pendingFlushMutex.withLock` 内写（L665-672）、在 flush 协程锁内读（computeAdaptiveSlice L143-149），两处锁一致 —— **唯一未入锁的是 L989-991 与 L1054-1055 的 `chunkIntervals.clear()` / `pendingBuilder.clear()`**（重试/补轮复位路径，此刻刷新协程已 join，安全）。结论：暂无可达竞态，但该正确性依赖"三处复位点都在 join 之后"这一隐含约定，无注释/无断言保护，标记为 P2 观察项。
- 建议：把 pendingBuilder/chunkIntervals 的全部写操作统一收敛到 mutex 内（复位路径包一层 `withLock`），消除隐含约定。

### F8. ChatToolLoopRunner 补轮回滚（A-01）保留 toolModel 答复时，未清理本代 toolMediaMessages 登记与 pendingToolCall 残留（P2，可信度：中）

- 位置：`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:1044-1092`（补轮回滚）、`1300-1313`（收尾兜底）
- 证据：补轮回滚 `return replayed`（L1092）直接返回 StreamRoundResult.Error，外层 `runToolLoop` 继续走 L1315 的 `if (!toolLoopResult.success)` 失败分支 → L1303-1313 的媒体兜底仍会执行（该兜底在 run() 层，非 streamRound 层），此处无问题。真正的残留是：补轮请求是 `params.copy(forceMainModel = true)` 的**递归 streamRound**，其 `duplicateRemaining`（L1056）等本轮局部状态已复位，但上游 `ToolOrchestrator.runLoop` 在进入本轮前已执行 `savePendingToolCalls`（ToolOrchestrator.kt:1060-1062）——本轮是"无 toolCalls 的最终答复轮"，本无 pending，安全。抽查后此条降级为**核对通过**，不作为缺陷上报；保留说明以示已验证。

### F9. TaskerTriggerReceiver 单轮对话每次重建完整默认上下文：messages 只含一条 USER，但 completeText 默认走全局模型与激活 Provider —— Tasker 广播可触发计费请求且无频控（P2，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:63-100`
- 证据：
  ```kotlin
  val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) {
      chatService.completeText(
          messages = messages,          // ← 仅一条 USER
          temperature = assistant?.temperature,
          maxTokens = assistant?.maxTokens,
      )
  }
  ```
  - 无任何防抖/频控：Tasker 侧配置高频触发（如每分钟）会连续打满计费请求（120s 超时内 goAsync 的 pendingResult 挂起，BroadcastReceiver 窗口耗尽后系统可能提前回收，但协程在自建 `CoroutineScope(SupervisorJob()+Dispatchers.IO)` 中继续跑完 LLM 调用并落库 —— scope 与 receiver 生命周期解耦，调用不会被取消）。
  - `KoinJavaComponent.get<ChatService>` 在 receiver 中解析单例 —— OK；但 `sessionRepo.observeSessions().first()` 用标题匹配建/复用会话，并发两次触发时可能建出两个同名 "Tasker 触发" 会话（observeSessions().first() 与 createSession 之间无原子性）。
- 影响：外部自动化入口缺少频控与并发防护，误配置会产生持续计费与垃圾会话。
- 建议：入口加简单节流（如 10s 内去重）；会话创建走 title 唯一化或先查后建加互斥。

### F10. PluginPromptFinalizeHook 把完整 preparedHistory 序列化进 JS 脚本字符串经 WebView 执行 —— 每轮生成主链路上做一次主线程 WebView eval（P2，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/data/plugin/PluginPromptFinalizeHook.kt:48-72, 75-89`；`app/src/main/java/io/zer0/muse/tools/JsSandbox.kt:199-308`
- 证据：
  - `buildCallScript` 把整个历史（含 PII 遮蔽后的占位正文、工具结果全文）拼成 JS 源码字符串（L76-88），每条消息一次 `AppJson.encodeToString`，长对话（数百条 × 数 KB）生成 MB 级脚本，再经 `webView.evaluateJavascript` 整段解析执行，且 `JsSandbox.execute` 固定 `withContext(Dispatchers.Main)`（L204）并持有全局 `executionMutex`（L208）—— 一次 10s 超时的插件会**阻塞主线程互斥**（锁内是挂起等待不阻塞主线程，但 WebView eval 本身在主线程做 V8 编译执行，长脚本会卡 UI 帧）。
  - 事件对象里只有 `{role, content}`（L78），**不含 reasoning/toolCalls/id** —— 插件返回的新历史 `parseHistory`（L92-98）构造的 UIMessage 只回填 role/content，**thinkingSignature / thinkingEncryptedContent / toolCalls / variant 字段全部丢失**。若插件原样返回 event（约定 "return event"），所有消息被降级重造：① 工具循环历史里的 ASSISTANT(toolCalls) 变成纯文本 assistant —— 下一轮请求 ProviderPayloadNormalizer.stripOrphanToolMessages 会把配对的 TOOL 消息当孤儿删掉（工具历史整段蒸发，模型"忘记"自己调用过工具）；② Anthropic thinking 签名丢失 → 多轮 thinking 请求 400（AnthropicProvider.kt:718-725 要求签名回放）。
  - fail-open 声明核对：异常/超时/空返回路径都 fallback(event)（L51-69），fail-open 成立；但**"成功返回"路径反而破坏历史保真** —— 这不是异常路径缺口，是正常路径缺陷。
- 影响：启用任何带 prompt_finalize hook 的插件后，带工具循环/思考回放的对话从第二轮起行为退化（工具历史丢失、Anthropic thinking 400）。
- 建议：hook 只对"允许修改"的字段做 diff（返回 patch 或允许返回 null 表示不改）；或至少在 parseHistory 时按 id/位置把原消息的 toolCalls/thinking 字段合并回新建 UIMessage。

### F11. ChatToolApprovalCoordinator 超时循环在 approvalTimeoutPaused=true 时以 200ms 步长空转，remainingMs 不减但 deferred.await 每步重建 withTimeoutOrNull —— 长期折叠时产生高频挂起唤醒（P2，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/ui/chat/ChatToolApprovalCoordinator.kt:120-145`
- 证据：
  ```kotlin
  while (approvalResult == null) {
      val stepMs = if (host.approvalTimeoutPaused) 200L else minOf(200L, remainingMs)
      val finished = withTimeoutOrNull(stepMs) { deferred.await() }
      if (finished != null) { approvalResult = finished }
      else if (!host.approvalTimeoutPaused) { remainingMs -= stepMs; ... }
  }
  ```
  用户折叠审批卡（approvalTimeoutPaused=true）期间：每 200ms 轮询一次 deferred（CompletableDeferred.await 在 withTimeoutOrNull 内），折叠 10 分钟 = 3000 次超时唤醒。功能正确（超时冻结语义符合 CHAT-08 注释），但实现是忙轮询；折叠期间用户离开 app，生成协程持续以 5Hz 唤醒。
- 影响：电量/调度开销小但真实；更实际的缺陷是**超时冻结无上限** —— 用户忘记展开且进程存活时，工具循环永久挂起在审批等待（连 stop() 都能取消它，但 idle 挂起无兜底），前台服务通知一直显示。可接受性存疑但与"30s 语义冻结"的声明一致，故 P2。
- 建议：paused 时改用 `host.approvalTimeoutPaused` 的 Flow 等待恢复（事件驱动）而非 200ms 轮询。

### F12. ChatToolResumeCoordinator 恢复链把全部工具结果以 TOOL 消息回填后直接 launchStream —— 若恢复的 pending 含 assistant(toolCalls) 对应之外的孤儿（崩溃窗口产生的悬空 pending），下一轮请求可能 400（P2，可信度：中）

- 位置：`app/src/main/java/io/zer0/muse/ui/chat/ChatToolResumeCoordinator.kt:47-246`
- 证据：恢复流程从 `PendingToolCallStore.getForChat` 逐条执行并 `upsertMessage(chatId, toolMsg)`（L196）。TOOL 消息入库后，若其对应的 ASSISTANT(toolCalls) 消息在崩溃窗口中**未落库**（ToolOrchestrator.kt:1047-1055 落库条件是 content/reasoning/media 非空 —— 纯工具调用轮 content 为空时被跳过！），则 DB 里有孤儿 TOOL 无配对 assistant。下一次 launchStream 的 prepareHistory 防御清理（ChatStreamCoordinator.kt:607-614）只删"ASSISTANT 带 toolCalls 但下一条不是 TOOL"，**不删孤儿 TOOL**；最终由 Provider 层 ProviderPayloadNormalizer.stripOrphanToolMessages 兜底删除 —— 不 400，但该工具结果对模型不可见（模型不知道工具已执行）。同时 ToolOrchestrator.kt:1048-1056 的落库跳过条件使**纯工具调用轮的 toolCalls 消息不落库**，重启后 buildCommitParts/toolRounds 恢复与 prepareHistory 的孤儿判定都失去锚点。
- 影响：恢复的工具结果可能对模型不可见，模型重新调用同一工具（幂等工具无害，副作用工具会重复执行 —— 与全局已知项 1"at-least-once"边界接近，但此处是**代码可修的配对丢失**，不是进程崩溃本质问题）。
- 建议：ToolOrchestrator 落库条件对"带 toolCalls 的 assistant 消息"无条件落库（toolCalls 非空即落，与 content 是否为空解耦）。

### F13. GeminiProvider usage-only 终帧判定补上了 hasEmittedContent 守卫，但 StreamInterrupted 路径会把"无内容的正常安全截断"当网络中断触发 UI 续传重试（P2，可信度：中）

- 位置：`ai/src/main/java/io/zer0/ai/gemini/GeminiProvider.kt:56-60, 318-337, 1223-1249`
- 证据：dedupe 基线列"Gemini usage-only terminal"为已知未闭环项。现况：`shouldCompleteUsageOnlyFrame(hasEmittedContent, candidateCount, finished)` 已实现（L331），有内容时 usage-only 帧发 Done("STOP") —— **该缺口已闭环，本报告按 dedupe 要求标注"已闭环"**。剩余边界：无内容 + finishReason=STOP 时发 `StreamInterrupted`（L1244），runner 侧 L920-927 判 `isNetworkError = streamInterruptedThrowable is IOException || (streamInterrupted && retryType == ChatErrorType.NETWORK)` —— StreamInterrupted 无 throwable 且消息是 "STREAM_INTERRUPTED" 分类，classifyErrorTypeStatic 大概率落 UNKNOWN，不满足 NETWORK → 走手动重试降级。行为可接受但错误提示"网络中断"误导（实际是模型空回复）。低危，P2。
- 建议：无内容 finishReason 时改发带明确文案的 Error（"模型未输出内容"）而非复用 StreamInterrupted。

### F14. ChatGenerationController.launchStream 在 chatGenerationManager.launchGeneration 外层先递增 streamGenerationSerial，但 A2A/continueGeneration/regenerateLastAssistant 三个入口在 isStreaming=true 时仍可进入（canStart 只挡 send）—— 两代生成串行化靠 previousJob.join，而 state.builder 等共享 StreamRunState 是每代新建，核对无共享；真正缺口是 attach 状态（P2，可信度：中）

- 位置：`app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt:229-244`（continueGeneration）、`248-302`（regenerateLastAssistant）
- 证据：`continueGeneration` 只检查 `accessor.snapshot.isStreaming`（UI 标志，切页时被 detachStreaming 置 false！ChatStreamCoordinator.kt:121-123），**后台生成仍在跑**（chatGenerationManager.isStreaming(sessionId)==true）时用户点"继续生成"：`launchStream` → `launchGeneration` → `previousJob.cancel()`（ChatGenerationManager.kt:117-118）取消旧代 → join 后新代启动。行为链正确（旧代被取消、[已中断] 落盘、新代续写），但 UI 的 isStreaming 曾被 detach 置 false，用户在"以为没有生成"时点继续，实际触发了对后台生成的一次隐式取消。对 A2A（F4）这是竞态乘数。
- 影响：detachStreaming 的"切页不停后台生成"承诺与 continueGeneration 的可用性判定冲突：切页回来看到 [已中断] 点继续，后台若仍在生成会被静默打断。P2（行为可辩解为用户显式操作）。
- 建议：continueGeneration 前置检查 `chatGenerationManager.isStreaming(sessionId)`，为 true 时先 stop 或直接拒绝。

### F15. GenerationTrace.appendToFile 滚动截断读整个文件到内存（2MB）并按字节中点切割 —— 切割点可能落在 UTF-8 多字节字符中间，产生坏行；且 rolling 在 lock 内做磁盘 IO（P2，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/diagnostic/GenerationTrace.kt:177-188`
- 证据：
  ```kotlin
  if (file.exists() && file.length() > MAX_FILE_BYTES) {
      val bytes = file.readBytes()
      val half = bytes.copyOfRange(bytes.size / 2, bytes.size)
      file.writeBytes(half)
  }
  file.appendText(AppJson.encodeToString(rec) + "\n")
  ```
  - `bytes.size / 2` 是字节中点，UTF-8 中文字符 3 字节，从中间切割后首行大概率是损坏 JSON；DiagnosticExporter 逐行 encode（DiagnosticExporter.kt:49-53）遇坏行无容错说明。
  - 每次 record 都持 `synchronized(lock)` 做磁盘 IO（appendText），生成终态时主链路协程在该锁上等待磁盘写入 —— record 本身已 runCatching 包裹不影响正确性，但"纯观察者不在热路径引入等待"的声明（GenerationTrace.kt:25）在滚动阈值达到后的**每次追加**都触发 readBytes(2MB)+writeBytes —— 2MB 级重复读写直到文件再次超限。
- 影响：导出的 trace 文件可能含坏行；达到 2MB 后每轮生成多付一次 4MB IO。
- 建议：滚动按行对齐（找最近 \n），滚动后重置阈值判断（当前是每次 append 都检查超限 → 达到上限后每次都滚动，直到下一行把它推过限再滚一次 —— 实际是每次滚动后文件回到 ~1MB，随后正常；但坏行问题仍在）。

### F16. OpenAIProvider stream-guard 二次校验窗口：`streamSourceClosed` 与 fallback 协程的检查间隔 500ms+jitter，SSE 已关闭但 streamProducedMore=true 的组合会永久挂起 Flow（P2，可信度：中）

- 位置：`ai/src/main/java/io/zer0/ai/openai/OpenAIProvider.kt:430-447`
- 证据：
  ```kotlin
  val streamProducedMore = currentContent > totalChars
  if (!streamSourceClosed.get() && streamProducedMore) { ...放弃回退,重置 streamGuardDone... return@launch }
  ```
  v1.0.51 注释自述了该修复（"如果连接其实已经 onClosed,这会让回退协程直接返回并永久挂起"），已加 `streamSourceClosed` 守卫 —— 核对后该已知竞态**已修复**。残留子情形：`streamSourceClosed` 在 fallback 等待期间由 onClosed 置 true，但 onClosed 分支 L920-923 见 pendingFallback=true 时 return 不 close —— fallback 协程随后检查 streamSourceClosed=true → 继续执行非流式回退 → 正确。**核对通过，不作为缺陷**；仅标注该守卫依赖 `streamSourceClosed` 的 happens-before（EventSource 回调线程 vs scope.launch 协程）：`streamSourceClosed` 是 AtomicBoolean，可见性 OK。保留此条为"已验证不重复上报"记录。

### F17. ChatToolLoopRunner runToolLoop 收尾把 `generationState.toolAssistantId/activeToolSessionId` 置 null 并递增令牌 —— 但 finalizeResponse（成功路径）在 run() 返回 true 后才执行，MediaGenHost 的 in-flight 视频生成（可能数十秒）在收尾后仍以旧令牌判定 isActive —— 核对（P2，可信度：中）

- 位置：`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:1310-1313`；`app/src/main/java/io/zer0/muse/ui/chat/ChatMediaGenHost.kt:78`
- 证据：A-13 注释明确"收尾递增令牌,使任何仍在途的工具执行媒体写入失效"——这是**声明的设计取舍**（媒体工具未完成即收尾=放弃），非缺陷。但边界核对：视频工具由 ToolOrchestrator 的 withTimeoutOrNull 等待完成才返回（TOOL_TIMEOUT_MS=120s），因此"仍在途"只发生在用户停止/异常取消路径（cancel 已在 ChatGenerationController.kt:1382-1384 兜底）。**核对通过**。唯一边角：成功路径 run() 尾部置空发生在 finalizeResponse 之前，而 finalizeResponse 里 triggerAutoCompress 与记忆写入不依赖媒体 —— 无影响。

### F18. A2aHandler.recentTasks 用 LinkedHashMap + synchronized 但 GetTask 读也 synchronized —— 无并发缺陷；CancelTask 后原 sendMessage 的 withTimeout 等待不会立即返回（P2，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:99-101, 127-139`
- 证据：`cancelTask` 调 `chatViewModel.stop()` 取消生成，但 sendMessage 等待方在 `chatViewModel.state.first { !it.isStreaming }` —— stop() 置 isStreaming=false 后等待返回，随后 observeMessages 取"最后一条 assistant"可能取到**被中断的半截回复**并标记 COMPLETED（L108-114 无条件 COMPLETED）。取消语义未传导到任务状态。
- 影响：外部 agent 发起 CancelTask 后收到 state=COMPLETED 的成功回执（含半截内容），违反 A2A 语义。
- 建议：cancelTask 记录 taskId→cancelled 集合，sendMessage 收尾时检查并返回 CANCELED。

## 已知项核对

对照 `AUDIT_FRAMEWORK.md` 全局已知 1-13 与 `DEDUPE_AI_PROVIDER.md` 已修/已知清单，以下均**未作为新发现上报**：

1. OpenAI stream-guard 回退失败发 Error、FirstEventWatchdog 单次 fallback、Done/pendingBuilder 收尾竞态、callback generation guard —— 抽查 `OpenAIProvider.kt:313-553`、`FirstEventWatchdog.kt`，机制在位（F16 记录为"核对通过"）。
2. 工具循环思考等级 ToolRoundPresentationPolicy / thinkingInstruction / 不伪造 reasoning —— runner L186、L276-292 与 `ToolRoundPresentationPolicy` 一致。
3. resumeOverlapToDrop 保守裁剪 / "首字被吞"未复现 —— 未重复上报该问题本身，F1 是其相邻的**新时序缺陷**（lastPersistChars 不同步）。
4. 能力目录 toolUse 缺省语义、catalog 三态、SUSPICIOUS 标记 —— `AiModule.shouldSendTools`（L111-135）与基线一致。
5. debugInfo 统一出口 —— `recordDebugSummary` 在 completed/failed/cancelled/context_overflow 四路径均调用（L1304/1326/1380/1426/1554），核对通过。
6. Gemini usage-only terminal —— **F13 标注"已闭环"**（shouldCompleteUsageOnlyFrame 已实现，GeminiProvider.kt:56-60, 331）。
7. Anthropic/Gemini stream abort listener —— **已实现**（AnthropicProvider.kt:157-161、GeminiProvider.kt:183-186 均注册 addAbortListener 并 cancel call/eventSource），按基线要求标注"已闭环"。
8. 旧 EventSource retry race 待真实网络验证 —— 未复现条件，未上报。
9. 全局 1/2/3（at-least-once、ProcessWriteGate 语义、设备验收缺失）—— 未触碰。
10. detekt 结构性债务、语言包翻译 —— 未上报。
11. 插件沙盒放开决策本身 —— 未上报；F10 报的是 hook 实现里的历史保真缺陷（属"放开实现里的越权/缺陷"范畴）。
12. 子代理 timeout_ms 30s~900s 解析与超时附进展 —— `SubagentRunSkill.kt:86-88`（coerceIn(30_000, 900_000)）与 `SubagentRunner.kt:545-559`（error 附 rounds/toolCalls/lastText）核对通过，无缺陷。

## 方法与覆盖声明

- 逐文件通读：ai/ 核心 16 文件（Provider 3 + ChatService + watchdog + compat + sanitizer + accumulator + normalization + frame-diagnostics + payload-normalizer + trace-projection + Provider/UIMessage/HttpSupport/HttpDefaults + ToolTraceProjection）；app/ 生成链路 20 文件（ChatViewModel 关键段、4 个新 coordinator、2 个 host bridge、StreamCoordinator、GenerationController、StreamRunState、GenerationState、StateStore、ToolController、ToolOrchestrator、ToolExecutionPolicy、SubagentRunSkill/Runner 关键段、MediaGenHost、A2A×2、Tasker、GenerationTrace、Hook×3、PluginHookBridge、PluginPromptFinalizeHook、JsSandbox）。
- git 对照：dfe3bbe3（S1）/a75b6176（S2）/15341441（S4+S5）提交信息与 diff stat 核对，抽查桥接口转发完整性（toolLoopHostBridge 16 项 + toolApprovalHostBridge 全项）。
- 未覆盖/待验证：okhttp3/sse 内部实现、ChatMessageController/ChatTaskCardCoordinator 全文、群聊与渠道链路（另行审查范围）、memory 模块 LLM 客户端、tests 目录逐个用例。
