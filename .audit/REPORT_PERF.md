# 链路 9：性能与稳定性 审查报告（2026-10）

## 摘要

本轮对 Muse（仓库 `E:\1Project\Muse\1muse`）的性能与稳定性链路做穷尽式静态代码审查，覆盖
7 个探查方向：启动路径（MuseApp.onCreate）、ChatViewModel 拆分后的 S1-S5 coordinator、
生成链路热路径叠加开销（GenerationTrace/shadow 事件/压缩水位线）、长会话内存驻留、新代码
线程边界（BroadcastReceiver / A2A / 诊断导出 / webhook）、全局单例初始化失败传播、
WorkManager/Doze API 用法。方法为 Glob/Grep/Read 工具逐文件核对，所有发现均给出文件:行号
与代码证据，并逐条对照 AUDIT_FRAMEWORK.md 全局已知项与 DEDUPE_PERF.md 去重基线。
共确认 **12 条新发现**（P1×5、P2×7），无 P0。总体结论：主链路的节流/守卫/降级设计较完整，
新引入的问题集中在 v2.5.x 新增的诊断/A2A 代码的线程与资源边界，以及长会话路径上"每轮一次
全量 DB 读取 + BPE 估算"的叠加成本。

---

## 发现清单

### F1. 诊断导出 zip 打包在主线程执行（P1，可信度：高）
- 位置：
  - `app/src/main/java/io/zer0/muse/ui/SettingsScreen.kt:704`（`DiagnosticExporter.share(context)` 由 SettingsEntry onClick 同步调用；`SettingsScreen.kt:1131-1135` 的 `onSelect = { entry -> ... entry.onClick() }` 与 `SettingsScreen.kt:1359-1383` 的 `cardGroupScope.item(onClick = onClick)` 均在 Compose 点击回调内同步执行）
  - `app/src/main/java/io/zer0/muse/diagnostic/DiagnosticExporter.kt:38-74`
- 证据：
  ```kotlin
  fun exportZip(context: Context): android.net.Uri? {
      return runCatching {
          val zipFile = File(context.cacheDir, "muse_diagnostic_...zip")
          ZipOutputStream(BufferedOutputStream(zipFile.outputStream())).use { zos ->
              GenerationTrace.traceFileOrNull()?.let { traceFile ->
                  zos.putNextEntry(ZipEntry("generation-trace.jsonl"))
                  traceFile.inputStream().use { it.copyTo(zos) }   // 文件 IO
              }
              ...
              DebugLogStore.exportToFile()?.let { debugFile ->      // 写 debug_export_*.txt
                  zos.putNextEntry(ZipEntry("debug-log.txt"))
                  debugFile.inputStream().use { it.copyTo(zos) }    // 再次文件 IO
  ```
  无任何 `withContext(Dispatchers.IO)` 包裹；`share()` 里 `exportZip` 直接在调用线程跑 zip 压缩、
  读 trace 文件（GenerationTrace 文件上限 2MB，`GenerationTrace.kt:36`）、读/写 debug 日志导出
  （DebugLogStore 缓冲 1000 条，`DebugLogStore.kt:31`）。
- 影响：诊断文件偏大（长会话日志 + 2MB trace）时设置页点击"导出诊断"会卡主线程数十至数百毫秒
  以上，触发掉帧甚至 StrictMode 违规（debug 构建直接 penaltyLog）。
- 建议：`exportZip` 包 `withContext(Dispatchers.IO)`，`share()` 改挂起函数或内部协程。

### F2. TaskerTriggerReceiver：goAsync 的 10 秒广播窗口内跑 120 秒 LLM 调用（P1，可信度：高）
- 位置：`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:50-61, 71-80, 125`
- 证据：
  ```kotlin
  val pendingResult = goAsync()
  CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try { runTrigger(context.applicationContext, prompt) }
      finally { pendingResult.finish() }
  }
  ...
  val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) { chatService.completeText(...) }  // LLM_TIMEOUT_MS = 120_000L
  ```
- 影响：`goAsync()` 给予 receiver 约 10 秒的进程优先级窗口；LLM 单轮（读 timeout 300s 的 OkHttp）
  可远超该窗口。超窗后进程失去前台/broadcast 优先级（后台冻结或被杀），`pendingResult.finish()`
  迟到，Tasker 侧可能拿到 `RESULT_FAILED` 或无响应，同时该进程被系统按"后台广播滞留"治理的
  风险升高。线程切到 IO 协程只解决了"onReceive 阻塞主线程"，没有解决 10s 窗口问题。
- 建议：改为拉起前台服务或 `WorkManager.OneTimeWorkRequest` 执行 LLM，receiver 只转发。

### F3. A2aHandler.sendMessage 在"新建会话"路径用 suspendCancellableCoroutine 且不支持取消，取消后 onReady 迟到调用把 cont 二次 resume（P1，可信度：中）
- 位置：`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:76-82`；回调源 `ChatSessionController.kt:199`（`onReady?.invoke()` 在 `accessor.coroutineScope.launch` 内）
- 证据：
  ```kotlin
  kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
      val ready: () -> Unit = { cont.resumeWith(Result.success(Unit)) }
      chatViewModel.createNewSession(onReady = ready)
  }
  ```
  无 `invokeOnCancellation` 释放注册；`createNewSession` 的 onReady 在异步 launch 块内被调用，
  一旦调用方（Ktor 请求协程）被取消，`cont` 已不在挂起，迟到 resume 会被 CancellationException
  吞掉但 onReady 后续逻辑（`refreshContext` / `saveViewedSessionId`）仍执行。
- 影响：a) A2A 请求超时/客户端断开后新建会话仍继续创建并切走 ChatViewModel 的 currentSessionId，
  造成"外部 A2A 调用劫持前台会话"体验问题（用户正在聊的会话被切空）；b) 多并发 SendMessage 会
  互相切会话、互相等待对方的 `isStreaming` 归落，串台语义未隔离。
- 建议：A2A 路径改用独立轻量执行链（如 ScheduledTaskRunner 同源路径），或至少按 taskId 串行化
  并用 `invokeOnCancellation` 释放。

### F4. A2A / Host WebSocket 都直接驱动 ChatViewModel 单例，会话切换竞态没有代际保护（P1，可信度：中）
- 位置：
  - `app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:74, 87-101`
  - `app/src/main/java/io/zer0/muse/web/HostWebSocketGateway.kt:209-241`（`chat.send` 直接 `chatViewModel.updateInput(text); chatViewModel.send()`）
- 证据：A2aHandler 等 `chatViewModel.state.first { !it.isStreaming }`（`A2aHandler.kt:99-101`）判定
  本请求生成结束；但 `state` 是全局单例的 UI 状态流——若用户同时在前台给另一个会话发消息，
  A2A 会把"用户会话的 streaming 结束"误判为自己的任务完成，取回**另一个会话**的 assistant 消息
  （`sessionRepo.observeMessages(sessionId).first().lastOrNull{...}` 在等待期取最后一条 assistant，
  `A2aHandler.kt:104-107`，sessionId 是 A2A 自己的会话所以取值正确，但完成判定被全局流污染）。
- 影响：前台用户与 A2A 并发生成时，A2A 响应可能提前返回空/错内容；反向同理，Web 端 chat.send
  与前台发送互相等待。ChatGenerationManager 是按 sessionId 串行的，但 A2A 完成判定未用
  `activeGenerations[sessionId]`，而是全局 `state.isStreaming`。
- 建议：A2A 完成等待改为 `chatGenerationManager.activeGenerations`（按 sessionId），或监听
  `streamGenerationSerial` + sessionId 绑定的专属信号。

### F5. 每轮生成收尾的"记忆管线全量历史"叠加成 O(轮数×全量) 成本（P1，可信度：高）
- 位置：`app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt:1572-1591`（finalizeResponse 内）
- 证据：
  ```kotlin
  val dbConversationMessages =
      if (deps.settings.memoryConfigCache.conversationRecallEnabled) {
          resultOf { deps.sessionRepository.getAllMessagesForWarmup(sessionId) }   // 全量读
              ...
      }
  ...
  deps.memoryTicker.notifyTurn(sessionId, conversationMessages, ...)   // 每 5 轮才真正跑摘要
  ```
  `getAllMessagesForWarmup` 不走分页（`SessionRepository.kt:680-684`，`observeBySession.first()` 全量
  + `toUIMessage()` 映射）。而 `MemoryTicker.notifyTurn` 里只有 `count % 5 == 0` 才启动
  rollingSummary（`MemoryTicker.kt:860-885`），即 **4/5 的轮次里这份全量消息列表只是被构造出来
  就丢弃**（引用在闭包里直到 GC）。长会话（万级消息）单次全量映射几十 MB 字符串是可测的内存
  尖峰 + IO 抖动。
- 影响：长会话每轮回复结束时多一次全表读 + 全量对象构造，即便本轮根本不触发摘要；叠加
  F6 的 token 估算，造成"回复结束后 UI 卡一拍"。
- 建议：`notifyTurn` 改为传 `messagesProvider`（懒取，同 `notifySessionEndFromProvider` 的做法，
  `ChatStreamCoordinator.kt:143-152`），仅在 `shouldRollSummary` 命中时才读全量。

### F6. updateContextTokenCount 流式期间对全 UI 消息列表做 BPE 编码且包含已压缩消息（P1，可信度：高）
- 位置：`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:706-710`（400 字符/1.5s 节流）→
  `ChatViewModel.kt:3162-3165` → `ChatGenerationController.kt:670-686`
- 证据：
  ```kotlin
  suspend fun updateContextTokenCount(excludedMessageIds: Set<String> = emptySet()) {
      val msgsSnapshot = deps.stateStore.messages.value
      ...
      val tokenCount = withContext(Dispatchers.Default) {
          runCatching { TokenEstimator.estimate(counted, sysPromptSnapshot) }
  ```
  - `counted` 只排除 checkpoint 覆盖消息；但 UI 分页窗口外的旧消息每次加载更多后都会进入
    `_messages`（`loadMoreHistory` 前置插入，`ChatMessageController.kt:101-103`），列表越长每次
    估算成本越高。
  - `TokenEstimator.estimate` 对每条消息 content/reasoning/mood/reflection/toolCalls 逐段 BPE
    （`TokenEstimator.kt:63-86`），无任何缓存（消息 id → token 数）。
  - 该调用同时被 `buildSystemPromptForStream`（`ChatGenerationController.kt:927`）与流式节流、
    压缩收尾（`ChatCompressionCoordinator.kt:177`）多处触发。
- 影响：长会话（>200 条消息）流式期间每 1.5s 一次数百 ms 的 CPU 任务，与 UI 渲染争
  Dispatchers.Default 线程；v1.97 修复注释也自述"长历史下单次 50-200ms"（`ChatViewModel.kt:3158-3160`）。
- 建议：给 TokenEstimator 加 (messageId, contentHash) → token 的 LRU 缓存；流式节流只重估
  最近 N 条 + 前缀缓存值。

### F7. Logger 文件日志在所有调用线程同步写盘 + 全局锁（P2，可信度：高）
- 位置：`common/src/main/java/io/zer0/common/Logger.kt:155-189, 201-233`
- 证据：`d/i/w/e` 均直接调 `maybeWriteFile` → `synchronized(lock) { ... file.appendText(line) }`，
  无异步 sink、无批量缓冲；`initFileLog` 在 `MuseApp.onCreate` 第 172 行无条件开启
  （`MuseApp.kt:172`），`fileLogLevel` 默认 DEBUG（`Logger.kt:33`），即**每条 debug 日志一次磁盘
  append + 100KB rolling 判定**，且持全局锁。
- 影响：工具循环/流式回调里的高频日志（如 `updateAssistant` detached 限频日志、ToolOrchestrator
  的每工具 2-3 条日志）在 IO 慢的设备上把所有日志调用方串行化在锁上，可能放大生成热路径延迟。
- 建议：文件写入改单线程 executor 队列 + 内存聚合；或 fileLogLevel 默认 INFO。

### F8. DebugLogStore 全量日志同步走 Logger.sink，且每条日志跑 7 个正则（P2，可信度：高）
- 位置：`MuseApp.kt:186-188`（sink 注册）+ `common/.../Logger.kt:142-153`（sanitizePii 7 个 replace）
  + `app/.../debug/DebugLogStore.kt:81-95`（同步 addLast + trim）
- 证据：`Logger.d` 每次：sanitizePii（7 个 Regex.replace）→ Logcat → `maybeWriteFile`（锁+写盘）→
  `dispatchToSink`（DebugLogStore ConcurrentLinkedDeque add）。主线程日志（如 Compose 回调里的
  Logger.i）三份开销都在调用线程。
- 影响：与 F7 叠加，是生成热路径上"隐性日志税"的主要来源；启动期 onCreate 里十几条 Logger.i
  也走全链。
- 建议：sanitize 结果按 (tag,msg hash) 短路缓存；DebugLogStore 限频或仅在 debug 构建注册 sink。

### F9. ScheduledTaskRunner / ProactiveMessageRunner 的 60s 轮询 + GenerationGate(2) 在 Doze 下结构正确但保持进程常驻无守护（P2，可信度：中）
- 位置：
  - `app/.../schedule/ScheduledTaskRunner.kt:150-160`（`while (isActive) { tickOnce(); delay(60_000) }`）
  - `app/.../schedule/ProactiveMessageRunner.kt:127-141`（同构轮询）
  - `app/.../schedule/GenerationGate.kt:16-39`（`MAX_CONCURRENT_LLM = 2`，进程内信号量）
  - 兜底 Worker：`MuseApp.kt:533-597`（三个 15min PeriodicWork，KEEP，无 constraints）
- 证据：轮询 Runner 依赖进程存活；进程被杀后由 WorkManager 15 分钟兜底（注释自述）。
  `ScheduledTaskWorker`/`ProactiveMessageWorker` 均无 `setConstraints(RequiresStorageNotLow())`
  /网络约束，`Constraints.Builder().build()`（`MuseApp.kt:538, 576, 591`）。Doze 下 PeriodicWork
  只在 maintenance window 执行，进程内 60s 轮询在 Doze 中 delay 被冻结对齐——两条路径的
  `lastTriggeredAt`/`nextTriggerAt` 有持久化（B8-01），逻辑正确。
- 影响：**非缺陷而是确认**：Doze 下"准点性"由 WorkManager maintenance window 粒度决定
  （15min 周期 + 系统窗口），进程内 delay 循环不可信但代码没有依赖它做唯一触发；每日总结另有
  `startInProcess` 的 collectLatest delay（`DailySummaryWorker.kt:248-277`）+ 前台补偿
  `enqueueCatchUpIfDue`（:346-375）双保险。`ReminderToolsRegistrar` 用
  `setExactAndAllowWhileIdle`（`ReminderToolsRegistrar.kt:163`）落在"需要准点的闹钟类工具"上，
  API 选型正确；QuickNotes 同款（`QuickNotesViewModel.kt:632`）。
- 结论：不构成新缺陷；记录为"已验证正确"项。唯一小瑕疵是 3 个 15min Worker 全部无
  网络约束，离线设备也会拉起进程做无效扫描（省电小损耗），列 P2 观察。

### F10. MuseApp.onCreate 同步段含 5 处阻塞点，恢复路径额外串行（P2，可信度：高）
- 位置：`app/src/main/java/io/zer0/muse/MuseApp.kt:223, 224-716, 718-748`
- 证据（首帧前主线程同步执行）：
  1. `restoreJournal.incompleteEntry()`（:223）——文件/DB 读取。
  2. `toolRegistrarBootstrapper`（:226）——强制实例化 ~28 个 Registrar（每个 init 注册 3-20 个工具），
     `ToolRegistrarBootstrapper.kt:53-61` 完成后 `assertBuiltInCategoryCoverage()`。
  3. `AutomationInitializer.initialize`（:233）——KoinJavaComponent.get ×3 + VirtualDisplayServerManager
     构建（`AutomationInitializer.kt:79-95`），异常有 catch 但**初始化本身同步**。
  4. `mcpRegistry.startAll()`（:238）——内部 scope.launch 异步连接，方法本身轻（`McpRegistry.kt:226-235`）。
  5. `chatViewModel.toString()` 强制初始化 ChatViewModel（:352）——init 块注册 ~30 个 Flow collector
     + FTS 一致性检查 + outbox 恢复（`ChatViewModel.kt:2165-2669`）。
  6. `notificationManager.ensureChannels()`、`PendingToolCallStore.init`、`OAuthManager.init`、
     `MuseNotificationListenerService.initialize`（:264-272）——各自 SharedPreferences/文件 IO。
  未完成恢复分支（:727-747）在 begin() 30s 自旋等锁后才 `initializeRuntime()`，`_startupReady`
  才置 true——MainActivity 阻塞期间用户看启动屏是设计如此，但 `ProcessWriteGate.begin()` 自旋
  每 100ms 一次（:729-735）是忙等（应改 `mutex`/channel）。
- 影响：v2.5.x 叠加后 onCreate 同步段（2/3/5 三项最重）估计 300-800ms（中端机）；冷启动到首帧
  延迟进一步增大。已有 Safe Mode 兜底与 system prompt 预热 3s 延后（:699-715），方向正确但
  工具 bootstrap 与 ChatViewModel 强制初始化仍在前帧前。
- 建议：把 2/3/5 移到 `_startupReady` 之后的 IO 协程（工具注册有 revision StateFlow，UI 可等）；
  begin() 自旋改挂起等待。

### F11. ChatViewModel 拆分（S1-S5）总体等价，但 shadow 事件引入每轮 2-6 次额外 DB 事务（P2，可信度：中）
- 位置：`app/.../ui/chat/ChatToolLoopRunner.kt:207, 1210, 1233, 1317, 1457`（TOOL_CALLED/TOOL_RESULT/
  turn 开始/结束）+ `ChatGenerationController.kt:1366-1377, 1463-1475`
- 证据：`shadowEventsEnabled` 默认 **true**（`ConversationRebuildFlags.kt:6`），每个工具调用产生
  TOOL_CALLED + TOOL_RESULT 两条 shadow 事件；`recordConversationShadow` 经
  `ConversationShadowEventSequencer.enqueue` 串行执行 `sessionRepository.recordConversationEvent`
  （`SessionRepository.kt:906-917`，**每个事件一个 `withTransaction`**：select + getByEventId +
  nextEventSeq + insert）。
- 影响：单轮 3 个工具 = 6 个独立事务 + turn upsert + finish；工具密集 agent 会话的 DB 写放大
  明显（Room 事务各自 fsync）。Sequencer 按 `sessionId/turnId/generationSerial` 分桶，无跨会话
  串行问题，但每桶锁存活的 Deferred 链会随轮数增长（`tails` 只在 finally remove，链越长等待越深）。
- 建议：shadow 事件批量 flush（同 turn 一次事务）；或 turn 级 eventSeq 内存分配 + 插入批处理。

### F12. CompressionSummaryStore / recoverInterruptedGenerations / ensureIndexed 的启动叠加与并发边界（P2，可信度：中）
- 位置：
  - `app/.../transformer/CompressionSummaryStore.kt:25-37`（MAX_ENTRIES=32 的 LinkedHashMap）
  - `MuseApp.kt:462-467`（seedDevDocs + indexDevDocs 同一协程串行，ensureIndexed 对每篇未索引文档
    逐个 `rag.indexDocument`，`BuiltInKnowledgeDocSeeder.kt:119-141`）
  - `MuseApp.kt:289-299`（recoverInterruptedGenerations）与 :342-344（群聊账本恢复）并发启动
- 证据：a) `CompressionSummaryStore` 水位线在 `prepareHistory.applySessionCheckpoint` 每轮写入
  （`ChatStreamCoordinator.kt:681-686, 701-707`），32 条 LRU 对多会话用户会反复驱逐，导致
  `reusableSummary` 失效重新花 LLM 压缩（`ContextCompressTransformer.kt:370-374`）；b)
  `ensureIndexed` 在启动 IO 协程对内置文档逐篇 embed，与 HNSW 索引加载（`MuseApp.kt:481-484`）、
  memory backfill（:278-282）竞争 IO 池——v1.0.52 注释已承认预热竞争问题并延迟 3s（:696-698），
  但 indexDevDocs 未延迟；c) 恢复类任务无互斥，`recoverInterruptedGenerations` 与用户立即发消息
  可同时对同一 session 写消息（有 checkpoint 幂等，风险低）。
- 影响：多会话重度用户下"摘要缓存命中率"下降 → 重复压缩调用；首次升级启动时 RAG 索引与
  backfill 抢线程池，冷启动 IO 饥饿窗口。
- 建议：ensureIndexed 延迟 5s 或并入 system prompt 预热协程；CompressionSummaryStore 按
  sessionId LRU 权重提升。

---

## 已知项核对（确认不重复上报）

1. ANR 看门狗误报已修（ticket 计数 + 冻结 overshoot + 强引用 Handler + elapsedRealtime）——本轮
   复核 `AnrWatcher.kt:124-167` 与 `AnrDetector` 口径注释（:256-263），未发现回归，不报。
2. AssistantAvatar OOM（按显示尺寸解码）已修——本轮未发现消息图片缓存的新增 OOM 路径
   （Coil ImageLoader 限 25% 内存 + 256MB 磁盘，`MuseApp.kt:951-960`；图片落盘
   `MessageImageStore` 只存路径，`AppKoinModule.kt:131-135`）。"未覆盖的其他可能性"未找到
   具体新路径，按纪律不算新证据。
3. 文档索引 CPU/磁盘操作移出主线程；44MB zip 导入闪退（HNSW 落盘节流）已修——`ensureIndexed`
   在 appScope(IO)，本报告 F12 只报**启动时序叠加**，不报"移出主线程"本身。
4. 会话切换过期守卫（SessionSwitchGuard）已修——`ChatSessionController.kt:542, 585-589` 复核在位。
5. 压缩预热轮跳过已修——`ChatStreamCoordinator.kt:994-997` `"compress_enabled" to !warmupActive` 在位。
6. Compose 长列表重组成本 / MuseDialog 滚动陷阱——属 PROJECT_GUIDE §5 已知提醒，不报。
7. SQLiteConnection.close warning / DataStore flake / TransformerPipelineTest——已知观察项，不报。
8. 渠道 at-least-once、ProcessWriteGate volatile 写前检查、STRICT 下 ALWAYS_ALLOW——设计取舍
   （AUDIT_FRAMEWORK §4），不报；F10 中仅报 begin() 自旋实现细节。
9. detekt 结构性债务（LargeClass 等）与 ktlint 存量——不报。
10. 模型能力目录缺省语义已修——未发现新目录解析缺口。
11. 插件沙盒放开是产品决策——本轮未触及沙盒实现。
12. 诊断导出不记正文/API key、sessionId 哈希脱敏——复核 `GenerationTrace.TraceRecord`（仅统计量）、
    `DiagnosticExporter`（不含 prompt/正文）达标；F1 只报主线程性能，不报"缺日志/越权"。
13. HANDOFF §11 历史未解决 P1（预算、SSE、prompt cache 等）——不重复。

## 方向 7（WorkManager/Doze）专项结论

- `setExactAndAllowWhileIdle` 仅用于用户显式创建的提醒/便签闹钟（`ReminderToolsRegistrar.kt:163`、
  `QuickNotesViewModel.kt:632`），配 `canScheduleExactAlarms` 分支，用法正确。
- 定时任务/主动消息/朋友圈/云备份：进程内 60s/10min 轮询 + 15min PeriodicWork 兜底（KEEP）+
  持久化 nextTriggerAt/lastTriggeredAt + 前台补触发（`enqueueCatchUpIfDue`），Doze 下行为为
  "maintenance window 粒度的准点"，代码无单一路径依赖 delay 精度——**API 选型与降级链正确**。
- 每日总结：OneTimeWorkRequest 自续期 + `startInProcess` collectLatest + 首页补偿三层，唯一 Work 名
  按 (hour,minute) 拆分并用 `claimDailySummarySlot` 幂等抢占——正确。
- 唯一 P2：三个兜底 Worker 与 AutoBackup/StatsCache Worker 均无网络/电量约束（见 F9）。
