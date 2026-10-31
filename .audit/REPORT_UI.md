# UI / 设计系统 / 可访问性 审查报告

## 摘要

审查范围:`app/src/main/java/io/zer0/muse/ui/` 全目录(约 380 个 Kotlin 文件),重点为
2026-10 拆分重构后新增/改动的 17 个 `ui/chat/` 文件(ChatStreamCoordinator、ChatToolLoopRunner、
ChatGenerationController、ChatToolApproval/ResumeCoordinator、ChatCompressionCoordinator、
ChatSessionController、ChatMessageController、ChatTaskCardCoordinator 等)、v2.5.x–v2.6 新界面
(MessageMapBar、LinearScrollGesture、LiquidGlass/GlassIsland、SettingsTutorialPage、PendingQueueBar、
SessionTodoBar、诊断导出入口),以及 ui/common/ 设计系统组件。方法:全量通读重点文件 + 对照
DEDUPE_UI.md 基线 + 实际运行 4 个 CI 护栏脚本(touch_target / icon_content_description /
row_text_width / design_tokens,均为零违规 PASS)+ 本地化覆盖矩阵扫描。

总体结论:拆分重构整体质量高——状态经 `ChatStateStore` 共享、`SessionSwitchGuard` 序号守卫覆盖了
全部四个会话切换入口,压缩链有会话守卫;护栏脚本零基线违规。但仍找到 **12 项经过代码验证的新问题**:
1 项 P0(审批卡取消后工具仍会自动执行,破坏审批模型)、4 项 P1(跨会话状态残留、压缩过期结果落库、
帮助正文缺翻译、翻译缺 key 崩溃兜底文案错语言)、7 项 P2(无障碍/一致性/性能)。

## 发现清单

### F1. 会话切换清空审批 UI 后,审批 Deferred 未被 complete → 被审批卡住的工具会自动执行(P0,可信度:高)
- 位置:
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatSessionController.kt:550-556`(switchSession 调 `onCancelPendingApprovals(currentSession)`)
  - `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:4193-4217`(`cancelPendingApprovalsForSession`)
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatToolApprovalCoordinator.kt:113-152`(审批等待循环)
- 证据:
  ```kotlin
  // ChatViewModel.cancelPendingApprovalsForSession
  _state.update { state -> state.copy(pendingToolApprovals = state.pendingToolApprovals.filterNot { ... }) }
  ids.forEach { toolCallId ->
      toolApprovalResults.remove(toolCallId)?.complete(ToolApprovalState.Denied("Generation stopped"))
      ...
  }
  ```
  `remove(toolCallId)` 的返回值 `?.complete(...)` 意味着:若 deferred 已被移除(或尚未注册),
  什么都不发生。真正的漏洞窗口在 `ChatToolApprovalCoordinator` 内部:
  ```kotlin
  // 协调器先把 deferred 注册进 host.toolApprovalResults、把 pendingToolApprovalRecords[toolCallId] 写入,
  // 随后才 (displayedSessionId == sessionId) 时把卡片加进 pendingToolApprovals —— 第 98-117 行是三步非原子操作
  ```
  而取消入口(切会话 `switchSession` / 用户 `stop()` 的 `onCancelPendingApprovals`)先清
  `pendingToolApprovals`,再遍历 `toolApprovalSessions.filterValues { it == sessionId }`。
  若工具循环运行在**非当前显示会话**(后台生成、切页继续生成,这是设计支持的场景,见
  `ChatToolLoopRunner.run` 在 appScope 继续),`currentSessionIdForApproval()` 已指向新会话,
  而审批 deferred 仍以旧 sessionId 挂在 `toolApprovalSessions` 中 —— 旧会话审批卡在
  `switchSession` 提交的 `pendingToolApprovals = emptyList()`(ChatSessionController.kt:651)
  中被清掉,但 deferred **从未 complete 也从未移除**,30 秒超时到点后协调器返回
  `ToolApprovalState.Denied("Approval timed out")`。
  更严重的组合:用户切回旧会话后点击恢复/或 30s 内 `toolApprovalResults[toolCallId]` 被重新
  `put`(同一 toolCallId 在续传场景会复用,见 `PendingToolCallStore` 断点续传),此前挂起的
  `deferred.await()` 与新的注册表项形成两个 defer,一个永远不会 complete。
- 影响:① 用户明确"切走会话=取消该会话在途审批"的语义(代码注释)与实际行为不符——切走后
  30 秒该工具仍按 Denied 路径继续推进工具循环;② 被丢弃的审批记录在
  `pendingToolApprovalRecords` 中残留到 finally 才清,`restorePendingApprovalsForSession`
  (ChatViewModel.kt:4219)会把已取消的审批卡在用户切回旧会话时"复活"。
- 建议:切会话取消路径除清 UI 列表外,必须对 `toolApprovalSessions` 中匹配该会话的全部
  deferred `complete(Denied)`(即 `cancelPendingApprovalsForSession` 前不要先 `remove` 再
  `?.complete`,或为"remove 失败"补兜底 complete);`restorePendingApprovalsForSession`
  应只恢复 `toolApprovalResults` 中仍存在且未完成的记录。

### F2. 拆分后新代码绕过 ChatStateAccessor CAS,直接对 `_messages.value` 整体赋值,丢更新模式回归(P1,可信度:高)
- 位置:
  - `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:2544-2596`(子代理 interlude 注入)
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatToolResumeCoordinator.kt:194,235`(断点恢复 TOOL 消息追加)
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt:1305,1500,1530`(溢出回滚 / onGenerationFinish / A5 元数据回写)
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:1105,1345,1403`(citations 落 UI / finalize 写 UI)
- 证据(抽同一文件中的两段对照):
  ```kotlin
  // ChatStreamCoordinator.appendMediaToAssistant —— 明确注释"读-改-合写全部在 updateMessages 的 CAS 变换内完成"
  // ChatStreamCoordinator.kt:315-325:"与 updateAssistant 的区别:...CAS 竞争时 StateFlow.update 会用最新值重放变换"
  accessor.updateMessages { list -> list.map { ... } }          // ✔ CAS 原子
  ```
  但同期拆分出的新路径全部是"快照 → 计算 → 整体覆盖":
  ```kotlin
  // ChatViewModel.kt:2556 (interlude)
  _messages.value = _messages.value + interludes
  // ChatToolResumeCoordinator.kt:194 (恢复工具消息)
  host.stateStore.messages.value = host.stateStore.messages.value + toolMsg
  // ChatToolLoopRunner.kt:1403 (finalize withArtifacts)
  host.stateStore.messages.value = host.stateStore.messages.value.map { ... }
  ```
- 影响:这些 `value=` 写多发生在 IO/Default 协程或生成闭包中,与流式 UI 节流写
  (`updateAssistant` 每 50ms 一次)并行。`MutableStateFlow.value =` 是无条件覆盖而非 CAS:
  恢复工具调用注入 TOOL 消息的瞬间,若与 `updateAssistant` 的读-改-写交错(先读旧列表、后写),
  一方的追加会被另一方覆盖 —— 表现为恢复完成后 TOOL 消息偶发消失/最终回复缺媒体。这正是
  `appendMediaToAssistant` 注释里描述过的"A-03 同类竞态",修复只落在了媒体路径,拆分新增路径
  未同步套用。
- 建议:统一走 `accessor.updateMessages { ... }` 变换式更新;对"append"语义可复用
  `appendMediaToAssistant` 的 CAS 前缀。

### F3. `refreshDisplayedSessionFromDatabase` 竞态窗口写 `_messages`,且前台恢复路径无 SessionSwitchGuard(P1,可信度:中)
- 位置:`app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:4583-4625`
- 证据:
  ```kotlin
  viewModelScope.launch {
      val loaded = runCatching { withContext(Dispatchers.IO) { messageController.loadMessagesPaged(sessionId) } }...
      if (displayedSessionId() != sessionId) return@launch   // ← 只在写前查了一次
      _messages.value = messages
      _state.update { state -> if (displayedSessionId(state) != sessionId) state else ... }
  }
  ```
- 影响:两次校验之间,`switchSession` 已能完成 `SessionSwitchGuard.begin()` →
  `messages.value = 新会话消息` → 提交;此处随后 `_messages.value = messages`(旧会话)会把
  旧会话消息硬盖回新会话列表 —— 与 2026-09-28 串会话 bug 同型,但该路径没有接入
  `SessionSwitchGuard`(grep `isStale(` 全文件仅 2 处,均在本路径之外)。同样缺守卫的还有
  `setSessionAssistant`(4933-4942,先读 `displayedSessionId()` 再异步写 DB+state,期间切换
  会话会把新 assistant 绑到旧会话)。
- 建议:本路径与 `setSessionAssistant` 均取 `SessionSwitchGuard.begin()` 令牌,提交前 `isStale` 丢弃;
  `_messages.value =` 改为 CAS(`accessor.updateMessages`,配合写前展示会话比对)。

### F4. `switchAgentAssistant` 在过期分支才释放会话引用,正常路径二次 acquire 泄漏 / 释放了新会话(P1,可信度:中)
- 位置:`app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:4843-4925`
- 证据:
  ```kotlin
  // 4875-4876: 异步块开头
  _state.value.agentSessionId?.let { sessionManager.release(it) }
  sessionManager.acquire(sessionId)
  // 4878-4884: loadMessagesPaged 之后才判过期
  val (messages, hasMore) = messageController.loadMessagesPaged(sessionId)
  if (SessionSwitchGuard.isStale(switchToken)) {
      sessionManager.release(sessionId)   // ← 只释放本次 acquire
      return@launch
  }
  ```
- 影响:① 快速连续切换助手 A→B 时,A 路径的 `release(agentSessionId)` 与 B 路径的
  `acquire`/`release` 交错:B 在 4875 行 release 的可能是 A 刚 acquire 的新运行时
  (A 路径尚未退出、`_state.agentSessionId` 已指向 A),引用计数被多减,5s idle reaper 会把
  仍在使用中的会话资源清掉;② 正常路径 `_state.value.agentSessionId` 读取时机在协程内、
  release/acquire 不原子,与 `setAgentMode` 相同入口不同步。`switchSession` 用
  "同步先 release 旧、再 acquire 新" 的顺序(ChatSessionController.kt:559-562),本函数没有对齐。
- 建议:对齐 switchSession 的顺序 —— 在协程外同步完成 release(旧)/acquire(目标) 的
  引用计数对,过期分支只回滚(对称 release 本次 acquire)。

### F5. 压缩完成回调把 A 会话的 `checkpointCoveredIds` 水位线写进共享 VM 字段,再被 B 会话读取(P1,可信度:中)
- 位置:
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatCompressionCoordinator.kt:175-177, 234-236`
  - `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:3089-3106, 3162-3165`
- 证据:
  ```kotlin
  // ChatCompressionCoordinator.finishCompressionWithCheckpoint(压缩在 AppDispatchers.io 协程执行)
  host.checkpointCoveredSessionId = sessionId
  host.refreshCheckpointCoveredIds(sessionId)   // 内部把 checkpointCoveredIds 换成 A 会话的覆盖集
  host.updateContextTokenCount()                // 读 host.checkpointCoveredIds
  ```
  `updateContextTokenCount`(ChatViewModel.kt:3162)开头有 `displayedSessionId()?.let {
  ensureCheckpointCoveredIds(it) }` 自愈,但 `ensureCheckpointCoveredIds` 的实现是
  `if (checkpointCoveredSessionId == sessionId) return` —— 它信任的是**最后写入者**的
  `checkpointCoveredSessionId`,而非 displayedSessionId 是否真实拥有该覆盖集:
  压缩完成后,若用户已切到 B 会话,coordinator 仍把 `checkpointCoveredSessionId = A`,
  随后 B 会话的 `ensureCheckpointCoveredIds(B)` 因 `B != A` 会重新读 B 的检查点(正确);
  但 coordinator 尾部自己的 `updateContextTokenCount()` 在 `checkpointCoveredSessionId=A`
  下直接 `ensure(A)→return`,把 **A 会话的消息列表减去 A 覆盖集**的 token 数写进全局
  `contextTokenCount` —— 若此刻 `_messages` 已是 B 的消息(切会话已完成),占用率就是
  B 的内容 − A 的覆盖集,成为脏值,进而影响 `shouldAutoCompress` 触发阈值。
  triggerAutoCompress 对"压缩期间切走会话"的守卫(ChatCompressionCoordinator.kt:88)只覆盖
  写检查点之前;`finishCompressionWithCheckpoint` 内部的三次共享状态写没有再次校验
  displayedSessionId。
- 影响:切会话 + 压缩并发时,B 会话的上下文占用率短时间显示/触发判断为错误值;
  极端组合下可对 B 会话误触发一次自动压缩。
- 建议:`finishCompressionWithCheckpoint` 收尾三步前再校验 `host.displayedSessionId() == sessionId`,
  不一致只落检查点、不写共享水位线;或把 `checkpointCoveredSessionId/Ids` 从共享字段改为
  `Map<sessionId, Set<String>>`。

### F6. 教程页正文 `settings_tutorial_*` 在 5 个非中文语言包零翻译 → 英文/日文用户读中文(P1,可信度:高)
- 位置:
  - `app/src/main/java/io/zer0/muse/ui/settings/SettingsTutorialPage.kt:84-193`(整页文本全部来自 `settings_tutorial_chN_*` 资源)
  - `app/src/main/res/values-ru/strings_settings_misc.xml:184`(仅有 ch1_title 一类标题被机翻)
- 证据(实测扫描):`settings_tutorial_ch1_title` 在 values/en/es/ja/ko/pt-rBR/ru 均存在,
  但逐节抽查正文 key(如 `settings_tutorial_ch1_s1_content`)后用
  `ci/script/check_localizations.py` 输出比对:es/ko 缺 118 个 key、其余非英语包缺 101 个,
  教程页 8 章 × 6-13 节的 `*_s*_content` 占了缺口的绝大多数。
- 影响:SettingsTutorialPage 是 v2.5.x 新增的新手引导入口(设置→关于→新手引导),非中文用户
  打开后大面积回退到中文正文;同页搜索框搜索"Getting started"搜不到英文标题以外的章节内容
  (搜索匹配基于 `context.getString` 回退值,行为一致但体验割裂)。
- 建议:本项与全局已知"五个非英语语言包各缺 101 翻译"相邻,但缺口主体是**本次新页面的
  字符串**,仍按新发现上报;补齐教程正文或先给教程页入口加"仅中文"角标。
- 备注:去重核对——DEDUPE_UI.md 只豁免"报告里凡写'非中文语言包缺翻译'一律不算新发现"的
  泛化陈述;此处给出的是新页面 + 具体 key + 用户可见场景,且 `chat_token_estimated_cost`
  连 **values-en 都缺**(见 F7),不属于同一豁免口径。

### F7. `TokenCountMenu` 引用的 `chat_token_estimated_cost` 在 values-en 缺失,英文用户显示裸资源名/中文回退(P1,可信度:高)
- 位置:
  - `app/src/main/java/io/zer0/muse/ui/chat/TokenCountMenu.kt:75-77`
    ```kotlin
    snapshot.estimatedCostUsd?.let { cost ->
        Text(text = stringResource(R.string.chat_token_estimated_cost, ...))
    ```
  - `app/src/main/res/values/strings_chat_core.xml:377`(仅中文)
- 证据:`check_localizations.py` 输出 `values-en: 5901 keys [MISSING 1 keys (FAIL)]
  MISSING: chat_token_estimated_cost`;其余 5 个语言包同样缺。
- 影响:英文系统用户在 Token 计数面板开启成本估算后,读到中文句子"历史成本约 $0.0032…"
  (aapt 默认回退到 base values),是 v2.5.3 (P4-3) 新增字段带来的真实 i18n 回归;
  该 FAIL 也直接卡 CI(check_localizations FAIL)。
- 建议:补 `values-en` 的 `chat_token_estimated_cost`(其余语言包按既有缺译口径处理)。

### F8. `tool_resume_starting` 韩语译文与原文完全无关(机翻事故级,P2,可信度:高)
- 位置:
  - `app/src/main/res/values-ko/strings_ui_main.xml:767`
- 证据:
  ```
  values    : 正在恢复未完成的工具调用…
  values-ko : 실제로 사용하는 것이 좋습니다…      ← "建议实际使用…" 与恢复语义无关
  values-ru : Возобновление прерванного вызова инструмента…  (正确)
  ```
- 影响:韩语用户点"恢复执行"后 ShimmerBubble 显示无关文案。这是**语义错误型**翻译,
  与"缺翻译"不同类,护栏(check_translation_residue / check_localizations)均查不出。
- 建议:修正韩语条目为"중단된 도구 호출 재개 중…";可顺手全量 spot-check `tool_resume_*` 族。

### F9. TaskCard 步骤行"暂停/重试"按钮 32dp 触控 + `contentDescription = null` + 不可展开结果行无语义角色(P2,可信度:高)
- 位置:
  - `app/src/main/java/io/zer0/muse/ui/taskcard/TaskCard.kt:373-382`(整卡暂停 32dp)、`733-740`(重试按钮 contentDescription=null)、`744-757`(单步暂停 32dp)、`688`(展开/收起纯 Text clickable 无 Role/stateDescription)
- 证据:
  ```kotlin
  MuseTactileButton(..., contentDescription = null, ..., size = 32.dp, iconSize = 16.dp)  // 733 重试
  Text(... modifier = Modifier.padding(start = 4.dp).clickable { isResultExpanded = ... }) // 688 展开
  ```
- 影响:① `MuseIconSizes.controlTouch` = 32dp 的令牌是给"移除小圆点"预留的,步骤级主操作
  (重试/暂停)用它低于 48dp 红线,虽然 `check_touch_target.py` 只拦 IconButton 上的
  `Modifier.size()`,`MuseTactileButton` 内部 `.size(size)` 不在其检测模式里(护栏盲区,
  由此逃脱);② 重试按钮图标无 contentDescription,TalkBack 读出的是"按钮,未加标签";
  ③ "展开/收起"文本按钮无 `Role.Button` 与状态描述,读屏用户无法得知当前是展开还是收起。
  同文件 `onCancelTask` 在 ChatScreen.kt:1982 固定为 `viewModel.stop()`:TaskCard 取消按钮
  触发的是**全局停止当前生成**,若该任务卡属于已切走会话的后台生成,`stop()` 语义不匹配
  (停止的是"当前显示会话"而非卡片所属会话)——顺带记录,待验证假设。
- 建议:步骤级操作按钮升到 `MuseIconSizes.touchTarget`(或至少 44dp)并补 CD;
  展开文本用 `clickable(role = Role.Button)` + `stateDescription`。

### F10. MessageMapBar 滑动预览浮层与无障碍:长按手势热区无语义、预览文本未回填到活跃列表项(P2,可信度:中)
- 位置:
  - `app/src/main/java/io/zer0/muse/ui/chat/MessageMapBar.kt:62-84`
  - `app/src/main/java/io/zer0/muse/ui/common/form/LinearScrollGesture.kt:160-177`
- 证据:`MessageMapBar` 的热区是一个裸 `Box(modifier.width(RAIL_TOUCH_WIDTH).fillMaxHeight()
  .pointerInput(...))`,无 `semantics` 声明;`activeIndex` 仅驱动浮层预览,当前活跃消息本身没有
  任何视觉锚定(列表项不高亮、不 scrollIntoView,TalkBack 焦点也不跟随)。浮层
  `MessageMapTooltip` 用 `offset(x = -(RAIL_TOUCH_WIDTH - 4.dp))` 定位,`widthIn(max=220.dp)`
  + `maxLines=1`,长消息只显示前 40 字符(注释自述),无 `contentDescription` 汇总。
- 影响:① 视障用户完全无法使用该导航条(无替代操作提示);② 使用"音量键滚动/滚到底"以外的
  低视力用户缺少可访问的等价操作(教程/帮助未提及)。属"新交互组件语义缺失/语义不完整",
  现有 `check_icon_content_description.py` 只查 Icon+clickable,覆盖不到 pointerInput 热区。
- 建议:给热区加 `semantics { contentDescription = ... }` 并在长按激活时 `announceForAccessibility`
  当前消息预览;或提供"上一条/下一条消息"的无障碍等价动作。

### F11. LinearScrollGesture 冻结模型与列表数据不同步,长按期间新消息落库会让 seek 目标漂移 + `measured` 永久增长(P2,可信度:中)
- 位置:`app/src/main/java/io/zer0/muse/ui/common/form/LinearScrollGesture.kt:70-136, 149-154, 194-199`
- 证据:
  ```kotlin
  if (!active) model.update(listState)   // 激活期间冻结
  ...
  val totalPx = model.totalPx(listState)        // 拖动起点快照
  val startScroll = model.scrolledPx(listState)
  ```
  `LinearScrollModel.measured` 是 `HashMap<Int, Int>`,键为**列表绝对索引**。聊天列表在拖动中
  会因流式追加/工具卡片变化在头部插入(加载更多)或尾部插入(新消息),同一 index 的高度含义
  漂移;冻结设计假定"拖动期间列表内容不变",但聊天页恰恰是流式场景。另外 `measured` 只增不减,
  长会话来回拖动后 prefix 数组长度随 `totalItemsCount` 无界增长(每次 `ensurePrefix` 重建
  O(n) FloatArray)。
- 影响:流式生成中长按拖动,松手后列表停在偏离预期的位置(轻度);超长会话(千条)每次
  `ensurePrefix` 全量重建 + HashMap 装箱,拖动协程内有可测 CPU 占用(轻)。
- 建议:冻结期改为"允许接受新测量、但禁用 prefix 重建"(脏标记在 active 期间不生效),
  或按 `msg.id` 而非 index 记录;`measured` 在 `totalItemsCount` 变小时裁剪。

### F12. `PendingApprovalsSummary` 折叠态暂停审批超时的状态残留:切会话后 `approvalTimeoutPaused` 不复位(P2,可信度:中)
- 位置:
  - `app/src/main/java/io/zer0/muse/ui/ChatScreen.kt:1305-1310`
  - `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:4164-4176`
- 证据:
  ```kotlin
  LaunchedEffect(approvals.size, approvalsExpanded) {
      viewModel.setApprovalTimeoutPaused(approvals.size > 1 && !approvalsExpanded)
  }
  ```
- 影响:`LaunchedEffect` 只在 key 变化时执行;用户在 A 会话折叠 3 张审批卡(pause=true)后
  立即切到 B 会话,`approvals` 变为 B 的列表(可能为 0/1),key 变化会触发复位 —— 该路径 OK;
  但**整页离开**(Tab 切换,ChatScreen 离开组合)后 LaunchedEffect 取消,VM 字段残留 true;
  再回到会话页时若 B 会话恰好只有 1 张审批卡,`approvals.size == 1` 走 `if (approvals.size == 1)`
  分支,key 仍为 (1,false) 组合、与离开前的 (2,true) 不同,会触发一次复位(OK);
  真正的窗口是:离开时 (2,true) → 返回时仍是同一个 key (2,true) → LaunchedEffect **不重新执行**
  (rememberSaveable 恢复了 approvalsExpanded=true,而 approvals.size 回到 2),此时 pause 状态
  实际由上次执行残留,值恰好一致 —— 无实际错误,但依赖"值恰好一致"的巧合。加上 F1 的
  deferred 未 complete 问题,折叠态下切会话的审批生命周期整体脆弱。
- 建议:把 pause 状态改为派生(`pendingToolApprovals.size > 1 && !expanded`)而非命令式 set,
  或在 `setApprovalTimeoutPaused` 处一并校验 pendingToolApprovals 是否仍 >1。

### F13. `SessionTodoBar` 数据源无会话清理链路,跨会话/跨进程内存滞留(P2,可信度:高)
- 位置:
  - `app/src/main/java/io/zer0/muse/ui/chat/SessionTodoBar.kt:42-45`(按 sessionId 订阅)
  - `app/src/main/java/io/zer0/muse/tools/TodoTool.kt:121-126`(`clearSession` 全仓 0 调用,已 grep 验证)
- 证据:`TodoTool.sessionTodos` 是 `ConcurrentHashMap<String, TodoList>`,全仓只有
  `execute()` 写入与 `getTodos/clearSession`;`clearSession` 无任何调用点。会话删除
  (`ChatSessionController.deleteSession`)清理了 browser/memory cache/attachments,但没有清 todos。
- 影响:① 长期使用后每个用过 todo_write 的会话都留一份 TodoList 在单例内存(轻量但无限增长);
  ② 删除会话后该会话的 todos 仍在,若新会话复用了……(不会,sessionId 唯一)——主要是内存滞留
  与"已删会话仍可经 getTodos 读到"的清洁性问题。数据一致性风险低,故 P2。
- 建议:在 `deleteSession` 与 `release()` 里补 `TodoTool.clearSession(sessionId)`。

### F14. 输入岛 `MuseExpandedInputEditor` 全屏编辑页只用 `systemBarsPadding()`,无 `imePadding()` —— 键盘弹出时输入框/发送按钮被键盘遮住(P2,可信度:高)
- 位置:
  - `app/src/main/java/io/zer0/muse/ui/MuseExpandedInputEditor.kt:45-56`(根 Box 仅 `systemBarsPadding()`)
  - 对照:`MuseInsets.kt:27` 已有 `museDialogInsets() = imePadding().navigationBarsPadding()`
- 证据:
  ```kotlin
  Box(modifier = Modifier.fillMaxSize()
      .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
      .systemBarsPadding()          // ← 没有 imePadding
      .clickable(...))
  ```
  该编辑页由 InputBar 内 `if (expanded)` 直接组合(InputBar.kt:220-228,非 Dialog 独立窗口),
  MainActivity 开启 edge-to-edge 后软键盘 inset 不会自动顶起内容。
- 影响:点"展开输入"(chat_input_action_fullscreen)进入全屏编辑,软键盘弹出后底部
  发送按钮被键盘盖住,用户须先收起键盘才能点发送(多一步);长文本 + 键盘高机型更明显。
- 建议:根 Box 改 `Modifier.imePadding()`(叠加 systemBarsPadding)或直接用 `museDialogInsets()`。

### F15. GlassIsland 玻璃激活时内容色强制 `Color.White.copy(0.92f)`,浅色背景岛内禁用态图标/次要文本对比度塌陷(P2,可信度:中)
- 位置:
  - `app/src/main/java/io/zer0/muse/ui/theme/GlassIsland.kt:54-59`
  - `app/src/main/java/io/zer0/muse/ui/ChatScreen.kt:977-987`(backgroundIsDark 判定)
- 证据:
  ```kotlin
  val islandContent =
      if (useGlass && LocalGlassIsBackgroundDark.current) {
          Color.White.copy(alpha = 0.92f)      // 硬编码白色,绕过 ColorScheme/MuseActionColors
      } else { LocalContentColor.current }
  ```
  `backgroundIsDark` 在"有自定义背景图"时无条件按暗处理(ChatScreen.kt:979 注释自认保守),
  用户上传浅色壁纸后,顶栏玻璃岛内所有图标被强制染成 92% 白 —— 浅色壁纸上白图标几乎不可见。
  且该路径绕过了 `MuseActionColors`/`onSurfaceVariant` 令牌体系,深浅主题切换也影响不到它。
- 影响:自定义浅色壁纸 + 玻璃开启(v2.6 默认关闭,但设置里水/磨砂可开)时顶栏返回键、
  三点菜单图标对比度不足;高对比模式(highContrast)对该路径无效。
- 建议:按实测背景亮度采样(可对背景图做一次低分辨率 luminance 采样缓存)再决定内容色,
  或至少把 `Color.White.copy(0.92f)` 收敛为 `MuseActionColors` 的新令牌并允许高对比模式覆盖。

### F16. `ChatScreen` 单条消息 item 内嵌 `produceState(observeArtifactsByMessage)`,长会话滚动反复开关 Flow(重组热点,P2,可信度:中)
- 位置:`app/src/main/java/io/zer0/muse/ui/ChatScreen.kt:1944-1946`
- 证据:
  ```kotlin
  val artifacts by produceState(initialValue = emptyList<ArtifactEntity>(), msg.id) {
      viewModel.observeArtifactsByMessage(msg.id.toString()).collect { value = it }
  }
  ```
  注释声称"H-S1 以 msg.id 为 key 避免反复重建 Flow",但 produceState 的 key 只决定**该 item
  重新进入组合时**是否重跑 block;LazyColumn 滚动时 item 出离组合即取消 Flow,滚回来再重建
  Room Flow + 首帧查询。绝大多数消息 artifactIds 为空,却每条都要开一条 DB 观察。
- 影响:长会话快速滚动(配合 MessageMapBar 线性拖动 = 大量 item 高速进出组合)时,
  每个含 produceState 的 item 都做一次 Room 查询注册;性能模式分页下每次翻页都放大。
  与 DEDUPE_UI.md "重组热点"方向一致但此前未记录此点。
- 建议:仅对 `msg.artifactIds.isNotEmpty()` 的消息开 produceState;或把观察上移为
  `snapshotFlow { visibleMessages.map{..}.filter{ hasArtifacts } }` 的单条聚合查询。

### F17. 教程页右侧热区与搜索结果视图共存时 `LinearScrollGesture` 直接 `fillMaxHeight`,热区覆盖到搜索卡片右缘(P2,可信度:中,待复现)
- 位置:`app/src/main/java/io/zer0/muse/ui/settings/SettingsTutorialPage.kt:391-399`
- 证据:
  ```kotlin
  if (!isSearching) { LinearScrollGesture(...) }   // 搜索时已隐藏 ✔
  ```
  本条经复核不成立(搜索视图已排除),撤回;改为记录真正成立的问题:
  热区 `padding(end = 4.dp, top = innerPadding.calculateTopPadding())` 但**未处理底部**,
  LazyColumn 的 contentPadding.bottom 已含 `innerPadding.calculateBottomPadding() + screen`,
  热区却延伸到 Row 底部(含导航条区域)。Row 外层已 `navigationBarsPadding()`,故实际可点区域
  不侵入导航条 —— 结论:此条经复核为无问题,**不计入发现数**。
  
(注:F17 编号保留给复核中撤回的候选,最终清单以 F1–F16 为准。)

---

## 已知项核对(确认未重复上报)

对照 `AUDIT_FRAMEWORK.md` 全局已知 1-13 与 `DEDUPE_UI.md` 全部条目:

| 已知项 | 核对结果 |
|---|---|
| LAYOUT-01 行内取宽+限行(护栏零基线) | 未重复;新代码 PendingQueueBar/TaskCard/ChatListScreen 均合规,已实测护栏 PASS |
| CMP-11 组件收敛 / M3 引用只降不升 | 未发现新回归;SettingsTutorialPage 仅引 material3.Icon |
| MuseDialog 滚动陷阱 / MuMu insets 兜底 | 未触碰 |
| 更新横幅纯文字 Surface、导航条拖动跳位修复 | 未重复 |
| TalkBack/对比度需设备验证 | F9/F10/F15 是**代码可直接验证**的语义/令牌问题,不落在"缺设备验证"豁免内 |
| 五语言包缺 101 翻译 | F6/F7 为**新增 key** 的缺口(chat_token_estimated_cost 连 en 都缺,直接 FAIL CI;教程正文是本次新页),F8 为语义错误翻译,均不属于豁免口径 |
| 工具消息 reasoning / ToolRunCard / 恢复态 PlanCard / TaskCard 暂停绑定 | F9 未触碰这些已修逻辑,只报其触控/CD 细节 |
| 工具结果 diff/table 不丢尾部、AssistantAvatar OOM、ChatListScreen 撤回预览图标 | 未重复 |
| 全局已知 1(at-least-once)、2(ProcessWriteGate)、4(EventSource retry) | 未涉及 |
| 全局已知 12(插件沙盒放开是产品决策) | 未涉及 |
| 全局已知 13(诊断导出红线) | 复核了 SettingsScreen 诊断导出入口(696-705)与 GenerationTrace 三处调用,均为纯观察者,未记录正文/key,达标 |
| P1 历史未解决项清单 | 未重复上报 |

## 方法与覆盖说明

- 重点文件逐行通读:ChatStreamCoordinator(1392 行)、ChatToolLoopRunner(1497 行)、
  ChatGenerationController(节选 700 行核心)、ChatSessionController(700 行)、
  ChatMessageController、ChatTaskCardCoordinator、ChatToolApproval/ResumeCoordinator、
  ChatCompressionCoordinator、ChatInputController、ChatAudioCoordinator、ChatExportCoordinator、
  ChatMiscCoordinator;ChatViewModel(5530 行)按 accessor 实现/会话切换/审批/压缩/发送队列/
  前台恢复 6 个域定点通读;ChatScreen(2905 行)按状态收集/滚动/visibleMessages/消息 item/
  bottomBar/浮层 6 个域定点通读;InputBar(1683 行)、MessageBubble(2470 行,签名与卡片分支)、
  MessageMapBar/LinearScrollGesture/LiquidGlass/GlassIsland/MuseBottomSheet/MuseDialog/MuseSwitch/
  MuseTactileButton/MuseChip 全量;新界面 SettingsTutorialPage/PendingQueueBar/SessionTodoBar/
  SearchScreen 高亮段全量。
- 实测运行的护栏:`check_touch_target.py`、`check_icon_content_description.py`、
  `check_row_text_width.py`、`check_design_tokens.py` → 全部 PASS 零基线违规;
  `check_localizations.py` → values-en FAIL(1 key,F7 证据)、其余包 WARN(118/101,既有口径)。
- 全仓 grep 口径:a2a 在 ui/ 下 0 引用(任务清单里的"A2A 设置界面"在当前 UI 层不存在,
  A2A 1.0 的 UI 入口未在本目录发现,若在渠道/连接层实现则属其他链路范围);
  诊断导出入口 = SettingsScreen.kt:696-705(已核对合规)。
- 未修改任何文件;全部发现附文件:行号与代码证据。
