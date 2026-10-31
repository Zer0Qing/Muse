# 链路 6：Agent 设备控制 / 自动化 / 内置运行时 — 审查报告

## 摘要

按 DEDUPE_AGENT.md 六个重点探查方向，走读了 `automation/`（AutomationManager 三层、execTiered、capability、appcontrol、vdisplay、agent/UiAgentRunner）、`runtime/MuseRuntime`、`automation_workflow`（AutomationWorkflow + WorkflowJournal）、`tools/` 设备类工具（device_shell、virtual_screen、virtual_screen_input、screen_*、ui_agent、execute_node_script、platform_selfcheck、root 系工具、termux_exec）、A2A（`web/a2a/A2aHandler` + WebServer 路由）、Tasker 入口、以及审批链路（ToolPermissionResolver / ChatToolApprovalCoordinator / ChatToolController / HostWebSocketGateway）与 patchelf 产物构建护栏。总体结论：三层降级、lease 台账、workflow 断点/未知副作用阻断、子代理/渠道/定时任务的 HIGH 过滤等既有防线实现扎实且与去重基线一致；但 **A2A 新入口与全局"本次放行"位的交集**、**workflow 显式 displayId 的 lease 归属校验盲区**、**每条消息在持锁状态下执行无超时 su 探测**、**patchelf 产物仅有口头约定无 CI 护栏** 是本轮新发现的实质缺陷。共 10 条发现：P0 0 条、P1 5 条、P2 5 条。

## 发现清单

### F1. A2A 远程入口继承全局"本次开启期间批准全部"位，远程无人在场即可静默执行 HIGH 设备工具（P1，可信度：高）

- 位置：
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatToolApprovalCoordinator.kt:58-62`
  - `app/src/main/java/io/zer0/muse/ui/chat/ChatToolController.kt:57-71`
  - `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt:639-646`
  - `app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:86-101`
  - `app/src/main/java/io/zer0/muse/tools/ToolPermissionResolver.kt:145-149`
- 证据：
  ```kotlin
  // ChatToolApprovalCoordinator.kt:58-62
  val mode =
      if (host.stateStore.state.value.appRunAllowAllTools) {
          // "本次运行全部放行"只跳过逐次审批；仍走统一解析器，保留参数硬拒绝
          SessionPermissionMode.TRUSTED
  ```
  `appRunAllowAllTools` 是**进程级全局** ChatUiState 字段，由任意一张审批卡的复选框写入（ChatToolController.kt:58-71），注释自述"本次应用开启期间批准全部工具调用（内存态，不持久化）"（ChatViewModel.kt:639-646）。A2A 的 `sendMessage` 直接 `chatViewModel.updateInput(text); chatViewModel.send()` 走标准生成链（A2aHandler.kt:87-88），复用同一 ChatViewModel/同一审批链。`resolve()` 在 TRUSTED 下对一切工具返回 Auto（ToolPermissionResolver.kt:145-149），`device_shell`/`automation_workflow`/`ui_agent`/`app_clear_data` 等 HIGH 工具随即无卡执行。
- 影响：用户曾在任一会话勾选"本次开启期间批准全部"后，外部 A2A 调用方（持 24h JWT 即可，`WebServer.kt:613-618` 仅 JWT 鉴权、无 Host 通道的焦点令牌校验）可在**无人在屏幕前**时连续触发 tap / pm clear / ui_agent 等设备副作用，30 秒审批超时兜底完全不生效（TRUSTED 分支根本不走 Pending）。A2A 的 `sessionId` 参数还允许外部指定任意既有会话并继承其权限模式。该复选框的授权范围声明（"本次开启期间批准全部工具"）从未涵盖"远程无人值守设备控制"，属放权位的作用域与新 A2A 入口的未声明交集。
- 建议：A2A 驱动的会话在审批解析时禁用 `appRunAllowAllTools`（例如给 `requestToolApprovalForSession` 增加"来源=远程"参数，远程来源一律降级为会话模式判定），或在勾选 UI 上明确声明该位对 A2A/远程触发同样生效。

### F2. automation_workflow 首个显式 displayId 动作跳过 lease 归属校验，可劫持并销毁其他 run 的专用虚拟屏（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/automation/tools/AutomationWorkflow.kt:319-338` 与 `:348-366`
- 证据：
  ```kotlin
  val leasedDisplayId = displayLeases.displayFor(runId)
  if (step.displayId != null && leasedDisplayId != null && !displayLeases.isKnownDisplayId(runId, step.displayId)) {
      return failure(index, action, "displayId=${step.displayId} 不属于当前 run_id 的虚拟屏 lease")
  }
  ...
  val displayId = leasedDisplayId ?: step.displayId ?: displayManager.lastDisplayId
  ...
  if (leasedDisplayId == null && action != "virtual_close") {
      displayLeases.acquire(runId, displayId)
  }
  ```
  归属校验以 `leasedDisplayId != null` 为前置条件：**全新 runId 的第一个 `virtual_tap/virtual_text/virtual_close` 若直接携带 `displayId`（未经 virtual_ensure），`leasedDisplayId == null`，整段校验被跳过**，随后 `displayLeases.acquire(runId, displayId)` 把一个陌生 displayId 登记为当前 run 的 lease。`virtual_close` 路径（:348-366）`release()` 返回 true（该 displayId 在进程内 lease 表中没有其他 owner —— 例如它是 `UiAgentRunner.createDedicatedDisplay` 创建的**不登记在本 lease 表**的专用屏，见 `UiAgentRunner.kt:238-254`）后直接 `client.destroy(displayId)`。
- 影响：并发场景（Agent + 手动工具 + A2A 触发，正是探查方向 3 的场景）下，一个 workflow 可对另一个 run 正在使用的 GUI-Agent 专用虚拟屏注入输入，甚至将其销毁——Agent 侧随即报"无法读取虚拟屏截图"或进入刷新分支，动作被静默打乱。`displayLeases` 的 owner 语义只保护"同表登记者"，对表外 display 无任何防护。
- 建议：显式 `displayId` 无论 `leasedDisplayId` 是否为空都要求 `isKnownDisplayId(runId, displayId)`；对未知 displayId 一律拒绝（或要求先 `virtual_ensure` 由服务端建立归属）。

### F3. 每条消息组装提示词时持锁执行无超时的 `su -c id` 探测，root 弹窗/挂起将冻结构建链与全部设备工具（P1，可信度：中——代码事实为高，真机触发频率取决于 ROM su 策略）

- 位置：
  - `app/src/main/java/io/zer0/muse/automation/capability/DeviceCapabilityHook.kt:71-79`（每次 afterComposeSystemPrompt 调 `refreshPermissions()`）
  - `app/src/main/java/io/zer0/muse/automation/core/AutomationManager.kt:61-90`（`refreshPermissions` 持 `mutex`，`probe("root") { root.isAvailable() }`）
  - `app/src/main/java/io/zer0/muse/automation/executors/RootExecutor.kt:33-37`（`isAvailable` → `exec("id")`）
  - `app/src/main/java/io/zer0/muse/automation/executors/ShellExecutor.kt:224-235`（本地 `sh/su` 路径：`ProcessBuilder(...).start()` 后 `readText()` + `waitFor()`，**均无超时**）
- 证据：
  ```kotlin
  // ShellExecutor.kt:224-235（RootExecutor 继承此路径，shellPrefix=["su"]）
  runCatching {
      val proc = ProcessBuilder(shellPrefix + listOf("-c", command))
          .redirectErrorStream(true)
          .start()
      val output = proc.inputStream.bufferedReader().use { it.readText() }   // 等子进程 EOF
      val exit = proc.waitFor()                                              // 无超时参数
  ```
  与有超时的 `RootRequest.requestRootAccess`（`RootExecutor.kt:73-102`，30s destroyForcibly）不同，能力探测走的是无超时路径。`refreshPermissions` 持有 `AutomationManager.mutex`，而 `DeviceCapabilityHook`（priority 80，每轮 system prompt 组装都跑一次）会同步等待它。
- 影响：设备装有 su 但 root 管理器对该调用弹确认框（首次、被撤销后、或"每次询问"配置）时：聊天发送被卡在 system prompt 组装（`detectProfile` → `refreshPermissions`），且所有设备类工具（readScreen/tap/execTiered 缓存失效重探、`VirtualDisplayServerManager.ensureStarted` 内的 `shell.refreshPermissions()`）都在同一把 mutex 上排队——单次 su 挂起即冻结整条设备控制链，直到用户处理弹窗。另外即使不挂起，每条消息一次 `su -c id` 子进程也是可感知延迟。
- 建议：root/shell 探测改走带超时的探针（复用 `requestRootAccess` 的 waitFor(timeout)+destroyForcibly 模式或缓存短 TTL 结果），能力探测不与工具执行争同一把锁。
- 验证方法：在 `su` 配置为"每次询问"的 Magisk 设备上发一条消息，观察 prompt 组装线程是否被 `su -c id` 阻塞；或单测中把 `RootExecutor.isAvailable` 替换为永不返回的 stub 验证 mutex 等待。

### F4. patchelf 产物完整性只有口头约定：CI 无 keepDebugSymbols / md5 护栏（P1，可信度：高）

- 位置：
  - `tools/runtime-pack/README.md:52-55`（md5 核对被写成手工验证步骤："验证：stripped_native_libs/.../lib*.so 与 jniLibs/.../lib*.so md5 必须一致"）
  - `app/build.gradle.kts:248-268`（`keepDebugSymbols += listOf("**/libmuse_node.so", "**/libssl.so", ...)` 手工清单，注释自述"已实测复现 SIGSEGV"）
  - `.github/workflows/ci.yml` 与 `ci/` 目录：grep `keepDebugSymbols|stripped_native_libs|md5|patchelf|runtime-pack` 均无匹配（本轮已验证）
- 证据：README 明确该 md5 一致性检查是"一次性本地操作"性质，构建脚本 `build-runtime-pack.sh` 的对应说明（:21-29）同样只停留在文档；CI 的 build lane（`./ci/run_ci_checks.ps1 -Lane debug` + `assembleRelease`）没有任何步骤比对 AGP strip 前后产物，也没有校验 `keepDebugSymbols` 清单与 `tools/runtime-pack` 实际产出的库文件集合一致。
- 影响：新增一个运行时依赖 `.so`（如升级 Node 带出新依赖）而忘记同步 `keepDebugSymbols` 时，AGP 的 llvm-strip 会破坏 patchelf 重写过的段布局，产出的 APK 在运行 `execute_node_script`/MCP stdio 时 SIGSEGV——且该故障只会在真机执行时暴露，CI 全绿放行。这正是去重基线里"strip 会破坏 patchelf 段布局"已知事故缺的护栏。
- 建议：加一个轻量 CI 步骤：构建后解包 APK，比对 `lib/*/` 下运行时库集合与 `tools/runtime-pack/FILEMAP`（及 jniLibs 源文件）的 md5，不一致即 fail；或加单测断言 `keepDebugSymbols` 覆盖 runtime-pack 清单中的全部库名。

### F5. A2aHandler 无并发/归属防护：静默吞掉会话切换超时、结果误归属、CancelTask 全局停止、并打断前台会话的待审批（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:59-124`、`:131-139`、`:142-146`；`app/src/main/java/io/zer0/muse/ui/chat/ChatSessionController.kt:553-556`
- 证据：
  ```kotlin
  // A2aHandler.kt:142-146 —— 切换会话 5s 超时被 runCatching 静默吞掉
  private suspend fun awaitCurrentSession(sessionId: String) {
      runCatching {
          withTimeout(5_000L) { chatViewModel.state.first { it.currentSessionId == sessionId } }
      }
  }
  ```
  随后 `chatViewModel.state.value.currentSessionId` 被当作目标会话使用（:83-84）——切换失败时消息会发进**上一个前台会话**。`sendMessage` 无互斥：并发两个 A2A 请求时第二个 `send()` 被 `canStartGeneration` 的 isStreaming 守卫丢弃（ChatViewModel.kt:1055-1056），但两个请求都等到 `!isStreaming` 后把"最后一条 assistant 消息"当各自回复返回（:104-114），无 generation/turn id 关联。`cancelTask` 直接 `chatViewModel.stop()`（:133），停止的是**全局当前生成**而非该任务。另外 `switchSession(requested)` 会触发 `sessionDeps.onCancelPendingApprovals(currentSession)`（ChatSessionController.kt:553-556）——远程 A2A 请求可把用户正在看的那张审批卡强制置为 Denied("Generation stopped")。
- 影响：多 A2A 调用方或"A2A + 本机同时聊天"场景下：回复串会话/错归属、一个取消命令杀掉无关生成、远程请求悄悄拒绝本地待审批卡。均为功能正确性问题而非安全越权（写命令有 JWT），但在 v2.5.8 A2A 1.0 新入口语境下属高频可达缺陷。
- 建议：`sendMessage` 加 per-handler 互斥；`awaitCurrentSession` 失败返回明确 FAILED；响应与 `generationId`/turnId 绑定；`cancelTask` 按任务对应会话停止；会话切换的审批取消仅在切换确已生效时执行。

### F6. Tasker 接收器 exported=true 且无权限/频控，与注释声明矛盾（P2，可信度：高）

- 位置：`app/src/main/AndroidManifest.xml:260-269`；`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:36-49`
- 证据：
  ```xml
  <receiver android:name=".intent.TaskerTriggerReceiver" android:exported="true">
      <intent-filter><action android:name="io.zer0.muse.action.TASKER_TRIGGER" /></intent-filter>
  </receiver>
  ```
  注释（TaskerTriggerReceiver.kt:37-38）称"仅响应本包显式动作（不导出任意 Intent 接收）"，但清单实际 exported=true 且未声明 `android:permission`，设备上任意应用都能发送该 action 携带任意 `prompt`。
- 影响：好的一面确认成立——该链路走 `chatService.completeText` 不带工具（TaskerTriggerReceiver.kt:70-80），设备工具不可达。坏的一面：任意应用可无限触发 LLM 补全（烧用户 API 配额/费用）、向"Tasker 触发"会话写入任意内容、并以 "always" 策略弹通知（:103-110），无任何频控。属"文档漂移 + 未鉴权外部触发面"。
- 建议：为 receiver 增加 signature 级自定义 permission，或改为 exported=false + Tasker 走 `setPackage` 的显式广播在部分 ROM 仍不可达时至少加分钟级频控与开关；同步修正注释。

### F7. MuseRuntime.ensureData 无互斥存在并发解压竞态；且生产链路没有任何解压触发点（P2，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/runtime/MuseRuntime.kt:85-121`；`app/src/main/java/io/zer0/muse/tools/PlatformSelfCheckTool.kt:61`；`app/src/main/java/io/zer0/muse/tools/RuntimeSelfCheckTool.kt:51`；`app/src/main/java/io/zer0/muse/MuseApp.kt:444`
- 证据：`ensureData` 全程无 Mutex/synchronized：`mark.readText()` 读取版本 → 未命中则 `npmDir.deleteRecursively()` + 逐条目解压。全仓 grep `ensureData(` 仅 3 处（定义 + 两个自检工具）；`MuseApp` 启动只调 `MuseRuntime.isReady`（:444，纯文件存在性检查）；`ProcessSkillEngine.eval` 与 `NodeScriptTool.evaluate` 都不 ensureData，MCP stdio（McpStdioCommand.kt:53-60）直接拼 `npm-cli.js` 路径。
- 影响：① 两个自检类工具并发调用（或自检与未来任何调用方并发）时，两个线程同时对同一目标文件做"删除+解压+写"，交错写会产生损坏文件——下一轮版本标记已写、`npmCli.isFile` 为真即静默沿用损坏数据（版本标记在解压成功后才写，但两线程都成功写 mark 后无人再校验内容）。② 新装设备上 npm 依赖的脚本（`require('npm')`）/npm 型 MCP server 首次使用前没有自动解压触发点：`isReady`（= nodeBinary && npmCli）为 false，能力档案显示 Node 不可用，直到用户手动跑一次 `runtime_selfcheck`/`platform_selfcheck`。
- 建议：`ensureData` 加 Mutex 并在解压完成后做一次 npmCli 存在性断言；在 MuseApp 启动（或首次 execute_node_script/MCP stdio 前）挂一个惰性 ensureData。

### F8. uiautomator dump XML 落共享存储 /sdcard，清理失败时残留整棵 UI 树（P2，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/automation/executors/ShellExecutor.kt:104-125`（readScreenOnDisplay）、`:282-294`（dumpUiAutomator）
- 证据：
  ```kotlin
  val dumpPath = "/sdcard/muse_window_dump_${System.currentTimeMillis()}_$displayId.xml"
  ...
  } finally {
      exec("rm $dumpPath")   // 失败仅被吞，无兜底
  }
  ```
- 影响：dump XML 包含前台应用的完整控件树文本（通知预览、聊天摘要、输入框内容等）。写入点在共享外部存储根目录；若 `rm` 因通道中断/命令失败未执行（finally 内 exec 失败被吞、Shizuku 断连时 execDetailed 返回 -1 但文件已在 shell 侧生成），敏感 UI 快照长期残留于 /sdcard，可被任何持存储权限的组件读取。与无障碍链路"转储滤密码节点"的修复口径（全局已知 11）相比，shell dump 是绕过该过滤的旁路残留面。
- 建议：改写到应用私有目录（`context.cacheDir`，shell 侧不可直接读时可落 `/data/local/tmp` 并在 finally 强校验删除结果），或至少对 rm 失败记 error 级日志并重试一次。

### F9. WorkflowJournal.cleanup 全仓无调用方：automation_workflow 断点日志无限累积（P2，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/tools/WorkflowJournal.kt:253-263`（`cleanup(maxAgeMs = DEFAULT_MAX_AGE_MS)`）；全仓 grep `\.cleanup\(` 仅命中 ChannelMediaCleaner 与 BackupService 的 restoreStagingStore
- 证据：`AutomationTools` 每次 workflow 调用以 `automation-${UUID}` 生成新 runId → `filesDir/workflow_journals/<runId>.jsonl`；`MuseApp` 启动清理只覆盖 `cleanupOldToolOutputs`（MuseApp.kt:688）与 channel_media（:315），不触碰 workflow_journals。非 read 步骤的工具结果（含 node_script 的加密结果、screen 摘要等）全部落盘。
- 影响：长期使用下断点日志体积无界增长；7 天保留策略（`DEFAULT_MAX_AGE_MS`）是死代码。历史数据里 node_script 结果虽已 Keystore 加密，但陈旧密文仍扩大密钥泄露后的暴露面。
- 建议：在 MuseApp 启动清理序列中追加 `workflowJournal.cleanup()`（或以 WorkManager 周期执行）。

### F10. automation_workflow 单调用最长可持有全局设备互斥锁 100 分钟（P2，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/tools/ToolRegistry.kt:378-391` + `:644-659`（automation_workflow ∈ DEVICE_INTERACTION_TOOL_NAMES，执行全程持 `deviceInteractionMutex`）；`app/src/main/java/io/zer0/muse/automation/tools/AutomationWorkflow.kt:230`（每步 node_script `timeoutMs` coerce 至 300_000）+ `:537-547`（20 步上限）
- 证据：单条 `automation_workflow` 调用的最坏路径 = 20 × 300s = 100 分钟串行执行，期间 `withDeviceInteractionLock` 使其它会话的 `screen_read`/`ui_agent`/`virtual_screen` 等全部排队（跨会话串行化本是有意设计，但时长无总预算）。
- 影响：一次被批准的 workflow 内嵌连续长 Node 脚本时，其它会话（含 A2A、子代理）的设备工具长时间不可用，外部观察为"工具永久挂起"而非失败。
- 建议：为 workflow 增加总时长预算（如 10 分钟）或对 node_script 步骤数 × timeout 求和做上限校验，超限在解析期拒绝。

## 已知项核对

对照 DEDUPE_AGENT.md 与 AUDIT_FRAMEWORK.md，以下历史项已核对、未重复上报：

1. 三层通道分层降级 / execTiered 唯一入口 / Shizuku 失败 Root 兜底（`AutomationManager.kt:122-141` 现实现与基线一致）——不复报。
2. DeviceCapabilityProfile 唯一事实来源 + Hook(priority 80)、子代理不注入、探测失败降级（`DeviceCapabilityHook.kt:48-64`）——不复报；探查方向 5 的"档案时效"结论：档案每轮组装即刷新（`DeviceCapabilityHook.kt:71-79`），撤销后 staleness ≤ 1 轮，真正的缺陷是该刷新可挂起（已列为 F3）。
3. AppControlCore 包名白名单/系统应用禁清数据卸载/禁 Muse 自身/名称四级匹配（`AppControlCore.kt:120-197`）——不复报。
4. 虚拟屏服务重启自愈、virtual_close 幂等、≤3 并发 display、语义读屏失败不回退主屏（`VirtualDisplayClient.kt:92-161`、`ServerService.java:34` MAX_ACTIVE_DISPLAYS=3、`AutomationWorkflow.kt:354-365` stale 幂等）——不复报；探查方向 3 的 lease 并发缺陷以 F2 新证据上报。
5. virtual_screen_input / virtual_tap_text 的 DeviceCommandPolicy 构造校验、≤5 次滚动、点击验证、结果未知阻断重放（`VirtualDisplayTool.kt:164-197`、`VirtualDisplaySemanticActions.kt`）——不复报。
6. Android 14+ takeScreenshot 接入与 13 及以下降级（`AccessibilityExecutor.kt:46-64`、`MuseAccessibilityService.kt:98-114`）——不复报。
7. screen_tap_text 结构化 ToolOutcome / tap_text/tap_id 评分坐标兜底 / 结果未知禁重放（`AutomationManager.kt:301-390`、`UiAgentRunner.kt:258-333`）——不复报。
8. automation_workflow：node_script HIGH 审批、结果 Keystore 加密（`NODE_KIND_SENSITIVE_TOOL`，`WorkflowJournal.kt:141-145`）、同 run_id 追加、未知副作用阻断（`AutomationWorkflow.kt:74-87, 151-192`；单测 `AutomationWorkflowResumeTest` 覆盖）——不复报；探查方向 2 的输入校验结论：MAX_SCRIPT_CHARS=128k、单步超时 1-300s、≤20 步、输出经 ToolOutputCapture 落盘分流，中间结果有界，无新的校验缺口（唯总量问题列为 F10）。
9. 内置 Node 运行时 jniLibs 分发 + nativeLibraryDir exec 绕 W^X、RUNTIME_DATA_VERSION 重解压、exec 垫片 LD_PRELOAD、npm assets 解压（`MuseRuntime.kt`）——不复报；npm 解压状态机的新缺陷（并发竞态 + 无触发点）列为 F7。
10. MCP stdio 命令仅允许 node/npm/npx 与运行时内绝对路径（`McpStdioCommand.kt:53-67`）——不复报。
11. platform_selfcheck 通道级自检只读 STANDARD（`PlatformSelfCheckTool.kt`）——不复报。
12. 无 Shizuku 且无 Root 的能力边界、auto 策略简单确定性、workflow 无任意条件分支、Node 全能力执行器不受 DeviceCommandPolicy 约束（设计如此）——均为设计取舍，未列为发现。
13. 渠道外部发送 at-least-once / ProcessWriteGate 语义 / 缺真机矩阵验收 / 语言包翻译 / detekt 存量 / AI 能力目录缺省语义——全局已知，未触碰。
14. 渠道（ChannelAutoReply）与定时任务（ScheduledTaskRunner.executeCallTool）对 HIGH 工具的 fail-closed 过滤（`ChannelToolPolicy.kt:21-23`、`ScheduledTaskRunner.kt:540-548`）与子代理 HIGH 过滤（`SubagentRunner.kt:703-717`）验证一致，未发现旁路。
15. HostWebSocketGateway 的 `tool.approval.resolve` 焦点令牌 + 参数覆盖禁用（`HostWebSocketGateway.kt:242-266`）验证有效，未列为发现；A2A 入口无等价焦点机制的缺口并入 F1/F5。

## 附：审查覆盖说明

- 审查方式：Glob/Grep/Read 工具纯静态走读，未修改任何仓库文件。
- 触达文件（关键）：`automation/core/{AutomationManager,DeviceCommandPolicy}.kt`、`automation/capability/*.kt`、`automation/tools/{AutomationTools,AutomationWorkflow}.kt`、`automation/vdisplay/*.kt`、`automation/agent/*.kt`、`automation/appcontrol/*.kt`、`automation/executors/{ShellExecutor,RootExecutor,AccessibilityExecutor}.kt`、`accessibility/.../MuseAccessibilityService.kt`、`runtime/MuseRuntime.kt`、`tools/script/ProcessSkillEngine.kt`、`tools/{NodeScriptTool,WorkflowJournal,ToolRegistry,ToolPermissionResolver,ToolApprovalRouter,RootToolsRegistrar,PlatformSelfCheckTool,RuntimeSelfCheckTool,FindToolsTool,SubagentRunner,TermuxExecTool,ToolCategories,ToolExposurePolicy}.kt`、`tools/system/{ShizukuAuthorizer,ShellService}.kt`、`ui/chat/{ChatToolApprovalCoordinator,ChatToolController,ChatToolResumeCoordinator,ChatSessionController,ChatGenerationController,SessionDeps}.kt`、`ui/ChatViewModel.kt`（审批相关段）、`chat/PendingToolCallStore.kt`、`web/{WebServer,HostWebSocketGateway,HostWebSocketProtocol}.kt`、`web/a2a/*.kt`、`intent/TaskerTriggerReceiver.kt`、`terminal/TermuxChannel.kt`、`schedule/ScheduledTaskRunner.kt`、`channel/{ChannelAutoReply,ChannelToolPolicy,ChannelMediaCleaner}.kt`、`virtual-display-server/src/.../ServerService.java`、`app/build.gradle.kts`、`.github/workflows/ci.yml`、`ci/*`、`tools/runtime-pack/*`、`app/src/main/AndroidManifest.xml`。
- 单测现状：DeviceCommandPolicy/VirtualDisplayLeaseRegistry/AutomationWorkflow(resume+lease)/UiAgentProtocol 均有单测覆盖；A2aHandler、TaskerTriggerReceiver、MuseRuntime.ensureData 无任何单测。
