# 安全与权限链路（链路 2）审查报告

审查对象：E:\1Project\Muse\1muse（v2.5.8 线）
方法：Glob/Grep/Read 全静态审查，全部发现均经文件:行号 + 代码证据验证；未修改任何源码。
重点：v2.5.8 新外部入口（A2A / Tasker / 插件 Hook / 市场签名白名单化 / 诊断导出 / MCP stdio / 更新链路 / SecureKeyStore）是否引入未设防信任边界。

## 摘要

本轮对 v2.5.8 新开放的外部入口做了穷尽式验证。总体结论：**核心信任链（JWT 鉴权、webhook 验签 fail-closed、市场目录/发行者双层签名、SSRF 出口、MCP stdio 命令限制、SecureKeyStore、审批链 TRUSTED/STRICT 语义）实现扎实，未发现可绕过鉴权或静默提权的 P0**。但 v2.5.8 新增的三个入口各留有一处未闭环的信任边界：① Tasker 广播接收器 exported=true 且无频控/长度上限，任意应用可无交互触发付费 LLM 补全（F1）；② A2A 端点鉴权正确，但 GetTask/CancelTask 误用 JSON-RPC id 当 taskId、CancelTask 无视 taskId 一律停掉当前生成，且 SendMessage 绕过 Host 模式焦点仲裁可劫持用户前台会话（F2/F3）；③ 插件 prompt_finalize Hook 在安装预览中不可见，受信插件可借此在每轮静默拿到完整对话历史（F4）。另有诊断导出 Throwable 堆栈未脱敏（F5）、更新链路下载源未绑定官方仓库（F6）、Agent Card 泄漏版本指纹（F7）两处中低危。

发现分布：P0 × 0，P1 × 1，P2 × 5，P3 × 2。

## 发现清单

### F1. Tasker 广播入口无鉴权、无频控、无长度上限 —— 任意应用可无交互消耗付费模型额度（P1，可信度：高）

- 位置：
  - app/src/main/AndroidManifest.xml:263-269
  - app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:42-61, 63-111, 125
- 证据：
  ```xml
  <receiver android:name=".intent.TaskerTriggerReceiver" android:exported="true">
      <intent-filter><action android:name="io.zer0.muse.action.TASKER_TRIGGER" /></intent-filter>
  </receiver>
  ```
  ```kotlin
  val prompt = intent.getStringExtra(EXTRA_PROMPT)?.trim().orEmpty()   // 无长度上限
  ...
  val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) {                  // 无任何调用方校验/频控
      chatService.completeText(messages = messages, temperature = ..., maxTokens = assistant?.maxTokens)
  }
  ...
  policy = "always",                                                    // 通知策略硬编码 always
  ```
  receiver 声明无 `android:permission`，`exported="true"`；onReceive 仅校验 action 名与 prompt 非空。
- 影响场景：设备上任意应用（无需任何权限）可循环发送 `setPackage("io.zer0.muse")` + action `io.zer0.muse.action.TASKER_TRIGGER` 的显式广播，每次都触发一次真实 `ChatService.completeText`（用户当前激活 Provider 的计费模型），prompt extra 长度不限（输入 token 计费），无每源/全局频控、无总次数上限、无开关可关闭该入口。同时每次结果以 `policy="always"` 写通知，并把内容追加进"Tasker 触发"会话（可被刷垃圾会话）。
- 边界核对（manifest 注释声称的缓解已验证成立）：`completeText` 未传 `tools`（默认 null → `shouldSendTools` 判 false），外部广播**不能**间接触发工具执行；提示词注入只能拿到模型对攻击者文本的回答，completion 不携带 system prompt / 记忆 / 工具，无用户数据外泄面。因此问题收敛为：**无鉴权的成本滥用 + 通知/会话垃圾注入**。
- 建议：为 receiver 增加 `android:permission`（signature 或自定义权限）或设置内提供"外部自动化触发"开关（enabled 状态控制 receiver）；对 prompt extra 设长度上限并对单位时间触发次数做环形限频。

### F2. A2A tasks/get 与 tasks/cancel 误用 JSON-RPC id 作 taskId；CancelTask 忽略 taskId 一律停止当前全局生成（P2，可信度：高）

- 位置：
  - app/src/main/java/io/zer0/muse/web/WebServer.kt:662-671
  - app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:126-139
- 证据：
  ```kotlin
  "GetTask", "tasks/get" -> {
      val taskId = req.id?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }  // ← 用的是 JSON-RPC request id
          ?: return a2aError(idJson, ..., "missing task id")
      val task = a2aHandler.getTask(taskId) ...
  }
  "CancelTask", "tasks/cancel" -> {
      val taskId = req.id?.let { ... }.orEmpty()
      a2aResult(idJson, a2aHandler.cancelTask(taskId))     // taskId 未参与任何校验
  }
  ```
  ```kotlin
  fun cancelTask(taskId: String): A2aTask {
      return try {
          chatViewModel.stop()                              // 无视 taskId，停止当前正在生成的任何会话
  ```
- 影响：
  1. 协议缺陷：A2A 规范中 `tasks/get` 的任务 id 在 `params.id`，此处却读 JSON-RPC correlation id。调用方只有在把 request id 恰好设为 SendMessage 时的 `message.messageId` 才能取回任务，正常 A2A 客户端（request id 独立自增）永远拿不到任务结果，且并发 GetTask 无法区分。
  2. 越权面：`CancelTask` 对任意/空 taskId 都执行 `chatViewModel.stop()`——任何持 JWT 的 A2A 调用方（或拿到 JWT 的 Web 客户端）可随时打断用户正在前台进行的生成（单用户设备内信任边界，但与 /ws 的 F-39 焦点隔离目标相悖）。
- 建议：`tasks/get` 改读 `params` 中的 task id（MessageSendParams 增加可空 taskId 字段或专用参数对象）；`CancelTask` 校验 taskId 存在于 `recentTasks` 再执行 stop，否则返回 TASK_NOT_FOUND。

### F3. A2A SendMessage 绕过 Host 模式焦点仲裁 —— 可强切用户前台会话、覆盖输入框草稿、并发交叉（P2，可信度：高）

- 位置：
  - app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:69-88
  - app/src/main/java/io/zer0/muse/web/HostWebSocketGateway.kt:209-241（对照：chat.send 的 focus.holds 检查）
- 证据：
  ```kotlin
  val requested = params.message.sessionId?.takeIf { it.isNotBlank() }
  if (requested != null) {
      sessionRepo.getSessionById(requested) ?: ...
      chatViewModel.switchSession(requested)          // 强切用户正在查看的会话
      awaitCurrentSession(requested)
  }
  ...
  chatViewModel.updateInput(text)                     // 覆盖输入框（含用户未发送草稿）
  chatViewModel.send()
  ```
  Host 模式写命令必须 `focus.holds(context.id, command.sessionId)`，而 A2A 处理器直接操作同一 ChatViewModel，无焦点令牌、无"用户正在生成/正在输入"检查。
- 影响场景：同一设备只有一个 JWT（密码/PIN 签发）。持 JWT 的外部 A2A agent 在用户正在打字或正在生成时调用 `SendMessage(sessionId=...)`：① 用户界面被强切到目标会话；② 输入框草稿被 A2A 文本覆盖；③ 若用户正在流式生成，`send()` 因 `canStartGeneration` 的 isStreaming 检查而静默丢弃（A2A 等待 300s 后超时）；④ 两个并发 A2A SendMessage 交叉切换会话，可能把 A 的文本发进 B 的会话（`sessionId` 在 `send()` 前由 `state.currentSessionId` 决定，存在 TOCTOU 窗口）。
- 建议：A2A SendMessage 接入与 /ws 相同的焦点仲裁（获取不到焦点时返回明确错误）；或 A2A 走独立注入路径（不依赖 updateInput/send 的 UI 状态），先校验当前是否正在生成。

### F4. 插件安装预览不展示 hooks 声明 —— prompt_finalize Hook 让受信插件每轮静默获得完整对话历史（P2，可信度：高）

- 位置：
  - app/src/main/java/io/zer0/muse/data/plugin/PluginSecurityGate.kt:337-370（summarize/InstallPreview 字段无 hooks）
  - app/src/main/java/io/zer0/muse/data/plugin/PluginHookBridge.kt:51-68
  - app/src/main/java/io/zer0/muse/data/plugin/PluginPromptFinalizeHook.kt:48-89
  - app/src/main/java/io/zer0/muse/hook/SkillHook.kt:135-147
  - app/src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt:1017-1034
- 证据：
  ```kotlin
  // InstallPreview 字段：id/name/version/author/description/entry/trust/capabilities/permissions/tools/
  // entrySha256/contentSha256/entryBytes/extraFileCount/totalCodeBytes/signatureStatus/publisherId/
  // publisherKeyFingerprint/requiresPublisherTrust —— 没有 hooks
  ```
  ```kotlin
  if (HOOK_PROMPT_FINALIZE !in verified.manifest.hooks) return@forEach   // 只要 manifest 声明即挂接
  val hook = PluginPromptFinalizeHook(pluginId, verified.entryCode, configJson)
  hookRegistry.register(hook)
  ```
  ```kotlin
  val historyJson = event.preparedHistory.joinToString(",") { msg -> ... }   // 完整 preparedHistory（经 transformer/记忆/worldbook 注入后、发送前的全部消息）序列化后进入插件 JS
  ```
  ```kotlin
  // SkillHook.kt: PromptFinalizeEvent(preparedHistory, assistantId, sessionId, transformContext)
  ```
- 影响场景：`hooks` 已纳入签名白名单（SIGNED_TOP_LEVEL_FIELDS 含 "hooks"，完整性有保障），但安装确认对话框**不展示**该字段——用户在"信任发行者并安装"时看不到"此插件将注册 prompt_finalize、每轮读取全部上下文"。挂接后每轮对话（含 memory/worldbook 注入、用户消息、system prompt）都会完整进入插件 JS；插件网络已放开（fetch/XHR 直连），一个受信但恶意的插件即拥有**无感知、每轮、全上下文**的外泄通道，且无需任何工具调用审批（hook 不走工具链路）。
- 边界核对：能力声明仅校验格式、不做硬性拒绝是 v2.5.8 产品决策（不重复上报）；本发现针对的是**知情同意缺口**（预览未呈现 hooks）+ hook 事件对象的数据范围（全量历史，超出任何工具参数所能接触的范围），属放开"实现里"的越权面而非放开本身。
- 建议：InstallPreview 增加 `hooks` 字段并在确认对话框明确展示"该插件将在每轮发送前读取完整对话历史"；考虑为 hook 注册增加独立的启用开关（默认关闭，启用时二次确认）。

### F5. 诊断导出中 debug-log.txt 未脱敏 Throwable 堆栈 —— 红线"不含 API key"存在旁路（P2，可信度：中）

- 位置：
  - common/src/main/java/io/zer0/common/Logger.kt:155-189（仅 sanitize msg，throwable 原样下发 sink）
  - app/src/main/java/io/zer0/muse/debug/DebugLogStore.kt:81-95, 118-136, 169-180
  - app/src/main/java/io/zer0/muse/diagnostic/DiagnosticExporter.kt:65-70
- 证据：
  ```kotlin
  // Logger.kt
  fun w(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
      if (enabled) {
          val safeMsg = sanitizePii(msg)          // 只有 msg 脱敏
          ...
          dispatchToSink(Level.WARN, tag, safeMsg, t)   // t 原样
  ```
  ```kotlin
  // DebugLogStore.log
  throwable = throwable?.let { stackTraceToString(it) },     // 堆栈原文入库
  // formatLine(): return if (throwable.isNullOrEmpty()) base else "$base\n$throwable"
  ```
  DiagnosticExporter 把 `DebugLogStore.exportToFile()` 原样打进 zip（红线声明见 DiagnosticExporter.kt:20-28 "不含消息正文、prompt、API key 原文"）。
- 影响场景：任何带 Throwable 的 Logger.w/e，其异常 message/stack（可能包含请求 URL、`key=`/`Bearer` 等凭据片段——sanitizePii 的三组正则只作用于 msg，不作用于堆栈）会未经脱敏进入诊断导出包。`MuseCrashHandler.redactSensitive` 只覆盖崩溃日志文件，DebugLogStore 链路无同等处理。
- 建议：DebugLogStore.log 对 throwable 文本复用 Logger.sanitizePii（或 MuseCrashHandler.redactSensitive）后再入库；导出前对 debug-log.txt 做一次整体脱敏兜底。

### F6. 更新链路下载源信任域未绑定官方仓库、digest 可选 —— 官网镜像被篡改时可导向任意 GitHub 仓库 APK（P2，可信度：高[代码路径] / 中[实际可达性依赖 museai.ltd 被攻破]）

- 位置：
  - app/src/main/java/io/zer0/muse/update/UpdateChecker.kt:169-191, 242-261
- 证据：
  ```kotlin
  private val TRUSTED_DOWNLOAD_HOSTS = setOf("github.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
  ...
  val validDigest = digest == null || (sha256 != null && SHA256_REGEX.matches(sha256))   // digest 缺失 → 直接跳过校验（兼容旧 Release）
  ```
  `isTrustedDownloadUrl` 仅校验 scheme/端口/域名后缀/.apk，不校验仓库归属；`isTrustedApkAsset` 同样允许 `sha256 == null`。
- 影响场景：`https://museai.ltd/api/latest.json` 为首选端点（UpdateChecker.kt:30-36, 240）。若该镜像被篡改，攻击者可放入指向自己 GitHub 仓库（同属受信域名）的 APK 资产且不带 digest——应用照常提示"发现新版本"，下载入口指向攻击者 APK。缓解：Android 安装期同签名校验会使覆盖安装直接失败；但"卸载后重装"的社会工程路径仍敞开，且没有 digest 意味着下载完整性无从校验。`htmlUrl` 未被任何消费方用于跳转（仅 HomeScreen 使用硬编码官方下载页），无开放跳转问题。
- 建议：受信域名收窄到 `github.com/Zer0Qing/Muse/releases/download/**`（或按 release 上游 owner 校验）；digest 缺失时在 UI 明示"无法校验完整性"，发布端保证新 Release 必带 digest。

### F7. Agent Card 公开端点暴露 appVersion —— 与 C-18 收敛 /api/health 版本指纹的口径不一致（P3，可信度：高）

- 位置：
  - app/src/main/java/io/zer0/muse/web/WebServer.kt:606-611
  - app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:35-50
- 证据：
  ```kotlin
  // WebServer.kt:607 注释："Agent Card：公开元数据，不敏感，无需鉴权（外部 agent 发现用）。"
  get(io.zer0.muse.web.a2a.AGENT_CARD_PATH) { call.respond(a2aHandler.agentCard(base)) }
  // A2aHandler.agentCard: version = appVersion
  ```
  对照 C-18（WebServer.kt:432-447）：allowLan 时 /api/health 刻意收敛 version/uptime，理由是"版本号/运行时长属于精确指纹信息，可能被局域网内第三方探测"。Agent Card 把同一指纹重新公开。
- 影响：局域网探测者可无鉴权获取精确 app 版本，用于定向匹配已知漏洞。危害低但与既有安全决策自相矛盾。
- 建议：Agent Card 的 version 在 allowLan 时返回粗化值（如 "2.x"）或与 /api/health 同口径收敛。

### F8. McpStdioCommand 绝对路径前缀校验缺分隔符边界 —— 与 WorkspaceManager.resolveSafe 口径不一致（P3，可信度：高[缺陷成立] / 实际无提权）

- 位置：app/src/main/java/io/zer0/muse/mcp/McpStdioCommand.kt:62-74
- 证据：
  ```kotlin
  head.startsWith("/") -> {
      val allowed = runCatching {
          f.canonicalPath.startsWith(MuseRuntime.nativeLibDir(context).canonicalPath) ||
              f.canonicalPath.startsWith(MuseRuntime.runtimeDir(context).canonicalPath)
      }.getOrDefault(false)
  ```
  无 `+ File.separator` 边界：`/data/user/0/io.zer0.muse/files/muse-runtime2/evil` 会命中 `runtimeDir` 前缀（`muse-runtime` 是 `muse-runtime2` 的前缀）。对照 WorkspaceManager.resolveSafe（workspace/WorkspaceManager.kt:379-398）已用 `rootCanonical + File.separator` 正确处理。
- 影响：逃逸范围是**应用自身 filesDir 内**的任意文件——与 App 同 uid，无提权（App 本就可用 ProcessBuilder 执行自己目录内的任何文件），故仅作加固项。真正的高危面（执行 /system/bin 等）仍被正确拦截。
- 建议：改为 `canonicalPath == root || canonicalPath.startsWith(root + File.separator)`，与 WorkspaceManager 对齐。

## 已知项核对（本轮核对过、确认不重复上报的历史项）

1. **webhook 默认关闭 / 飞书 Verification Token 缺失 fail-closed / QQ 签名缺失 fail-closed / 飞书加密 webhook 明确不支持**：核对 WebhookAuthPolicy.kt:13-26、56-72、81-97 与 WebhookDispatch.kt:34-40（encrypt → NotImplemented），实现与基线一致，未发现回归。
2. **TRUSTED 下 ui_* 审批、无障碍转储滤密码节点**：核对 ToolPermissionResolver.kt:237（ui_ → HIGH）与 AccessibilityPathUtils.kt:20-21（isPassword → null），成立。
3. **渠道工具风险真源 fail-closed（P1 #17）**：ScheduledTaskRunner.kt:540-548 走 `ToolPermissionResolver.riskLevelFor` 单一真源并拦 HIGH；GroupChatToolPolicy.kt:48-49 群聊直执行通道同样按解析器过滤 HIGH；SubagentRunner.kt:708-717 呈现与执行闸门共用 `allowedToolNamesForSubagent()`，三处口径一致。
4. **插件沙盒放开（fetch/XHR/桥接全放行、能力名只校验格式）**：JsSandbox.kt:88-127、SkillBridge.kt:66-78、PluginSecurityGate.kt:29-47。放开本身不报；本轮只在放开实现之上报 F4（hooks 预览缺失/全历史事件）。
5. **manifest 签名字段白名单是产品决策**：核对 PluginSecurityGate.kt:394-426（SIGNED_TOP_LEVEL_FIELDS 与 PluginManifest 字段集一致、"hooks"/"toolCards" @EncodeDefault(NEVER) 配套），白名单实现本身未发现可操纵验签结果的口子（canonical Base64 双向校验 :214-246、P-256 限定 :501-507、信任根不可被同 id 换钥 PluginTrustStore.kt:60-75、目录信任根内置项不可覆盖 PluginMarketSettings.kt:65-95）。本轮新增发现 F4 属"预览可见性"，非签名实现缺陷。
6. **诊断导出不记录正文与 API key、sessionId 哈希脱敏是红线，检查达标可以**：GenerationTrace.kt 全字段核对为统计量 + sessionId SHA-256 前 8 位（session id 为随机 UUID，未加盐不可穷举，达标）；**但** debug-log.txt 链路发现 F5 旁路，属红线达标性缺陷而非"缺日志"。
7. **STRICT 下 ALWAYS_ALLOW 穿透、at-least-once、ProcessWriteGate、虚拟屏 Binder token、render_data/Root 截断**：逐一核对（ToolPermissionResolver.kt:156-176、VirtualDisplayBinderRegistry.kt:31-37 token 校验成立、WorkflowJournal.kt:141-145 node_script 结果加密落盘），与基线声明一致，不重复上报。
8. **web 审批 answered 参数注入（B-12）**：复核 HostWebSocketGateway.kt:242-259 —— answered 与 approved 等价、argOverrides 合并路径确已 void；ChatToolController.approveToolCall 的 argOverrides 仅限 reference_image 白名单工具（ChatToolController.kt:86-100），未发现回归。
9. **A2A /a2a JSON-RPC 端点鉴权**：核对 WebServer.kt:612-618，`authenticate(AUTH_JWT_NAME)` 包裹成立；Agent Card 公开属设计（版本指纹问题单列为 F7）。
10. **OAuth state/PKCE**：OAuthManager.kt:382-400 state 校验、McpClient.kt:510-536 MCP 侧 state+PKCE、SSE postEndpoint 同源校验 McpClient.kt:629-637，均成立。
11. **非英语语言包缺翻译 / detekt 存量 / 设备验收缺失**：未涉及。

## 结论

v2.5.8 三个新外部入口中：**A2A 鉴权正确但协议实现有缺陷且缺焦点仲裁（F2/F3）；Tasker 入口的"不授予工具执行"缓解成立，但无鉴权成本滥用未设防（F1）；插件 Hook 的信任门槛成立但知情同意缺失（F4）**。建议优先修复 F1（补频控/开关），随后 F3/F2（A2A 接入焦点仲裁并修正 task id 语义），F4（安装预览展示 hooks）可随下一版 UI 一并处理。
