# 渠道与后台可靠性 审查报告（链路 4）

## 摘要

审查范围:`app/src/main/java/io/zer0/muse/channel/` 全部 24 个文件(五平台接收器/客户端/发送器/媒体),以及集成点 ChannelInbox 派发(ChannelAutoReply)、webhook 路由(web 包)、A2A/Tasker 入站、通知回传、channel_media 生命周期、MuseApp 启动/ON_STOP 观察者、ToolRegistry 渠道工具。方法:全文件通读 + 交叉 grep 验证调用方/生命周期/并发边界,所有发现均给出代码证据;DEDUPE_CHANNEL.md 末节 6 个探查方向与两条"先核实再引用"旧账(ON_STOP 停接收器、指数退避抖动)已逐条打开代码核实。

总体结论:可靠派发主链(PENDING 落盘→PROCESSING 恢复→BLOCKED/退避)实现扎实,webhook 鉴权 fail-closed、媒体清理保护、压缩条件回写均与基线一致。但发现 **接收器循环存在多个"永久死亡且无自愈"路径**(P1)、**A2A 复用全局 ChatViewModel 与人机/并发 A2A 互相干扰**(P1);DEDUPE 重点问的两个问题有了确定性答案:①单渠道故障**不会永久饿死**其他渠道(队列会排空),但单消费者串行 + 100 槽跨平台共享收件箱会带来**分钟级延迟与 503 拒收**;②A2A/Tasker 入站**不共享** ChannelConversationStore(走 SessionRepository/ChatViewModel),无并发写冲突,但 A2A 自身有会话劫持问题。ON_STOP 停接收器未实现,退避抖动未实现(详见 F5)。

发现分布:P0 × 0,P1 × 2,P2 × 9。

## 发现清单

### F1. A2A 复用全局 ChatViewModel:与人机聊天及并发 A2A 互相干扰(结果错配/会话劫持)(P1,可信度:高)

- 位置:`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:70-107`、`app/src/main/java/io/zer0/muse/web/WebServer.kt:614-660`
- 证据:
  ```kotlin
  // A2aHandler.sendMessage —— chatViewModel 是 Koin single(全局唯一,与 UI 同实例)
  chatViewModel.switchSession(requested)          // 直接切换用户当前会话
  ...
  chatViewModel.updateInput(text)
  chatViewModel.send()                            // 把文本送进全局输入框再发送
  ...
  withTimeout(GENERATION_TIMEOUT_MS) { chatViewModel.state.first { !it.isStreaming } }
  // 完成判定是"全局 isStreaming 回落",不是本任务维度的完成信号
  val reply = sessionRepo.observeMessages(sessionId).first()
      .lastOrNull { it.role == ASSISTANT && it.content.isNotBlank() }?.content ?: ""
  ```
  WebServer 的 `/a2a` 路由对每个请求开独立协程调用 `a2aHandler.sendMessage(params)`(WebServer.kt:659),无互斥、无队列。
- 影响(三个可复现场景):
  1. **人机干扰**:用户正在前台聊天时收到 A2A 调用 → `switchSession` 把全局当前会话切走,用户界面跳到另一个会话;A2A 文本还会经由全局 `updateInput` 写入共享输入状态。
  2. **并发 A2A 错配**:两个 A2A SendMessage 并发到达,第二个的 `switchSession/createNewSession/send` 与第一个交错;两者的 `state.first { !it.isStreaming }` 都会在**任意一次**生成结束时被满足,第一个调用可能读到错误的(或空的)assistant 消息,却以 `COMPLETED` 返回。
  3. **流式中误判**:用户正在流式生成时 A2A 进入,`streamingBefore=true` 跳过"等开始"直接等"结束",若 A2A 的 `send()` 实际未生效,会以用户那轮的尾部消息冒充本任务回复返回。
  另外 `cancelTask(taskId)` 无任务维度校验,任何 taskId 都会 `chatViewModel.stop()` 停掉当前正在跑的(可能是用户自己的)生成(A2aHandler.kt:131-139)。
- 建议:A2A 走独立无 UI 生成路径(与 TaskerTriggerReceiver 的 ChatService 单轮同构,或为 A2A 增加会话维度互斥 + 按 sessionId 订阅完成),`cancelTask` 只取消对应会话的任务;至少先加"A2A 串行互斥 + 有活跃 UI 生成时拒绝/排队"。

### F2. 接收器循环存在"永久死亡"路径,且进程内无任何重启兜底(P1,可信度:高)

- 位置:
  - `app/src/main/java/io/zer0/muse/channel/TelegramReceiver.kt:53-59, 93-98`(退出与 loadOffset 抛错)
  - `app/src/main/java/io/zer0/muse/channel/ChannelStore.kt:22-30, 47-55`(load 失败/解密失败 → emptyList/空 secret)
  - `app/src/main/java/io/zer0/muse/MuseApp.kt:153, 516-528`(appScope 携带 GlobalCoroutineExceptionHandler;restart 仅启动时一次)
  - `app/src/main/java/io/zer0/muse/ui/settings/ChannelSettingsPage.kt:85-91`(唯一另一处 restart)
- 证据:
  ```kotlin
  // TelegramReceiver.pollLoop —— 拿不到启用配置就 return,循环退出
  if (config == null) { Logger.i(TAG, "无启用中的 Telegram 渠道,接收循环退出"); return }
  // cursor.channelId != config.id 时:
  cursor = TelegramCursor(config.id, loadOffset(config.id))
  private fun loadOffset(channelId: String): Long? {
      ...
      return target.readText().trim().toLongOrNull()
          ?: error("Telegram offset 文件格式无效")     // 在任何 try 之外抛出 → 整个 job 死亡
  }
  ```
  五个接收器完全同构:`connectLoop/pollLoop` 中 `config == null → return`。`config` 来自 `channelManager.refresh()` → `ChannelStore.load()`,后者 `runCatching { ... decrypt(it) }` 任何失败(文件读失败、`SecureKeyStore.decryptOrNull` 返回 null → decrypt 回退 "")都收敛为 `emptyList()`。job 由 `appScope`(SupervisorJob + GlobalCoroutineExceptionHandler)承载,**未捕获异常只记日志、job 终结、无人重启**;QQ/飞书/钉钉/WeClaw 循环体内虽对单次连接失败有 catch,但 `connectOnce` 里 `loadBuffer→restoreWeClawCursor→error("无法解密 WeClaw 同步游标")`(WeClawReceiver.kt:32-35, 125-129)等抛错点每次重试都复现。
- 影响:
  1. Telegram offset 文件一旦出现非数字内容(异常写入/人为破坏),接收循环**每次启动即死**,且永不自愈。
  2. Keystore 失效类故障(部分机型清除锁屏凭据后 KeyStore 不可用)会让 `decrypt` 静默回退空串 → 所有平台 `appSecret.isBlank()` → **五条循环全部安静退出**,渠道整体失联;除非用户手动进设置页保存一次(但解密仍失败时同样退出)或杀进程重启(结果相同)。
  3. 与群聊/定时任务不同,渠道接收器**没有 WorkManager 兜底**,也没有对 `config==null` 的重试/退避等待——进程被 ROM 杀掉后,渠道可用性只寄希望于下一次 WorkManager 拉起进程时的 Application.onCreate 副作用。
- 建议:`config == null` 时不要 return,改为低频退避(如 30–60s)继续轮询,使"临时空列表/密钥暂不可用"可自愈;`loadOffset` 的格式错误走隔离+重置游标(丢一次游标好过死循环);job 死亡时由父级监督重启。

### F3. QQ WebSocket 无 RESUME 恢复:断连窗口内的事件静默丢失(P2,可信度:中)

- 位置:`app/src/main/java/io/zer0/muse/channel/QqReceiver.kt:99-112, 145-171`
- 证据:`connectOnce` 建立裸 WS 后只实现 op=10(Hello)/11(心跳 ACK)/0(dispatch)/7(重连)/9(无效会话);`lastSeq` 被采集(`obj["s"]... lastSeq.set(seq)`,:168-170)但**从未用于 op=6 RESUME**;断开后 `connectLoop` 固定 5s 后全新 Identify(QqReceiver.kt:79-83)。对比:Telegram 有持久化 offset(TelegramReceiver.kt:80-83)、WeClaw 有加密 buffer 持久化(WeClawReceiver.kt:96, 115)。
- 影响:移动网络切换/服务端踢线等断连窗口内投递的消息,在无 RESUME 的全新会话里不会被 QQ 网关重放(平台语义),消息静默丢失;其他四平台在同等故障下都能靠游标/服务端重投 + inbox 去重找回。
- 建议:实现 op=6 RESUME(lastSeq 已在手,缺 payload 与 session_id 保存),或至少在文档/基线里把"QQ 断连丢事件"标成已知边界。

### F4. 渠道三存储恢复失败即静默清零,无隔离备份(违反项目自家 quarantine 惯例)(P2,可信度:高)

- 位置:`app/src/main/java/io/zer0/muse/channel/ChannelInbox.kt:168-196`、`app/src/main/java/io/zer0/muse/channel/ChannelConversationStore.kt:70-84`、`app/src/main/java/io/zer0/muse/channel/WebhookEventDeduplicator.kt:45-50`
- 证据:
  ```kotlin
  // ChannelInboxJournal.attach —— 解码失败仅记日志,继续用空列表;file 已置位,后续 record 会用新内容覆盖坏文件
  }.onFailure { error -> Logger.w(TAG, "收件箱恢复失败: ${error.message}") }
  // ChannelConversationStore.attach 同款
  .onFailure { e -> Logger.w(TAG, "对话历史恢复失败: ${e.message}") }
  // WebhookEventDeduplicator.loadEntries —— 解码失败/超容量直接 throw → attach 标记失败
  require(loaded.size <= MAX_ENTRIES) { "Webhook event ledger exceeds its capacity" }
  ```
  项目内已有 `AtomicFileStore.quarantine()`(AtomicFileStore.kt:47-65)与 DataStore 损坏备份(MuseSettingsDataStore)先例,这三处未使用。
- 影响:JSON 真损坏(非撕裂写,如文件系统级错误/schema 不可逆漂移)时:①inbox 与 conversations 的 PENDING 队列、对话上下文**无声丢失**,首次新 record 落盘即覆盖坏文件;②去重账本不可用后,**所有带事件 ID 的入站 record 永久抛错**(ChannelInbox.kt:418-431 `error("Webhook event deduplication is unavailable...")`),webhook 恒 503、四条长轮询/长连接循环 5s 一次永久重试,只能清数据或重装才能恢复——fail-closed 方向正确,但缺少自愈出口。
- 建议:attach 解码失败时 `AtomicFileStore.quarantine()` 保底再空启动;去重账本构造失败时同样隔离旧文件重建空账本(保留 fail-closed 只覆盖"恢复尝试失败后仍不可用"的场景)。

### F5. 核实结果:ON_STOP 未停接收器、退避无抖动/无指数(DEDUPE 两条旧账均"未落地")(P2,可信度:高)

- 位置:`app/src/main/java/io/zer0/muse/MuseApp.kt:354-404`(ON_STOP 观察者只处理 ChatViewModel/memoryTicker/hooks,不触达五个 receiver);`grep` 全仓无任何 `receiver.stop()` 生产调用(仅各类自身 `fun stop()` 无人调用)。
  退避现状:`ChannelInboxJournal.retryDelayMillis`(ChannelInbox.kt:476-479)指数退避封顶 5 分钟但**无抖动**;五个接收器重连/长轮询失败一律**固定 5s 常数**且无上限递增——`TelegramReceiver.kt:109`、`WeClawReceiver.kt:261`、`QqReceiver.kt:251`、`FeishuReceiver.kt:281`、`DingtalkReceiver.kt:190`。
- 影响:①长时断网/平台故障期,五个循环以恒定 5s 频率打平台(电量 + 平台风控风险);②多设备/多实例同时重连有惊群效应(项目已有 `retryOnNetworkError` 的 0–30% 抖动先例,NetworkRetry.kt:46,未复用);③ON_STOP 不管理接收器本身是"渠道需 7×24 常驻"的合理取向,但与"无前台服务、无 WorkManager 兜底"叠加后,后台渠道存活率完全取决于 ROM。
- 建议:接收器重连改为指数退避 + 抖动(复用 NetworkRetry);派发重试补抖动;评估渠道常驻前台服务或至少接入 WorkManager 拉活探测。

### F6. 单消费者串行派发 + 100 槽跨平台共享收件箱:单渠道慢事件拖慢全平台,单渠道突发可致其他平台 503/拒收(P2,可信度:高;结论=已验证的设计现状 + 跨平台爆炸半径)

- 位置:`app/src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt:126-168, 380-408`、`app/src/main/java/io/zer0/muse/channel/ChannelInbox.kt:222, 268-280, 442-453`
- 证据:dispatcher 是**全局单消费者** while 循环,`drainPending` 逐条处理直到队列空;单条事件最坏路径 = 5 轮 LLM×60s(`LLM_TIMEOUT_MS=60_000`,`MAX_TOOL_ROUNDS=5`,ChannelAutoReply.kt:816, 842)+ 工具调用无外层超时(`toolRegistry.executeFromJson` 直调,:598)+ 发送重试;`nextRetryDelayMillis` 在有 PROCESSING 项时恒返回 1s(ChannelInbox.kt:367-369),即慢处理期间循环以 1s 心跳空转但**不并行**。收件箱容量 `MAX_PENDING_ITEMS=100` 为**全平台共享**,超限 `throw ChannelInboxCapacityException()`(webhook → 503 平台重试;长轮询 → 5s 重试)。另外 `claimNextPending` 取 `indexOfFirst`,而 `retain` 把新事件**插到队首**(ChannelInbox.kt:223),积压时新消息反而先于旧消息被处理(LIFO)。
- 影响(回答核心问题):单渠道故障**不会永久饿死**其他渠道(BLOCKED/退避保证队列排空,其它渠道消息最终被处理),但:①单条慢事件(模型慢/工具卡)可让**所有平台**的回复延迟数分钟;②单个活跃群聊突发可填满共享队列,令其他平台 webhook 收 503、长轮询反复拒绝;③积压场景下 LIFO 顺序使最早的用户等最久。
- 建议:声明边界可保留,但建议:工具执行加外层超时;按平台/渠道做简单轮转(每轮从不同 platform 取件)或按渠道分桶并发;容量按平台配额。

### F7. 回发上下文令牌解密失败走无限 Retry 而非 BLOCKED,与 checkpoint 故障处理不一致(P2,可信度:高)

- 位置:`app/src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt:323-338` vs `:342-348, 416-418`
- 证据:
  ```kotlin
  val restored = when (inbound.platform) { ... restoreWeClawReplyContextToken(encrypted) ... }
  return restored?.let { ContextTokenOverride.Value(it) } ?: ContextTokenOverride.Unavailable
  // Unavailable → HandleResult.Retry → retryDispatch → attemptCount 无上限,封顶 5 分钟一次,永不终止
  ```
  而 Agent checkpoint 不可读时显式转 `BLOCKED(CHECKPOINT_UNREADABLE)` 终态(ChannelAutoReply.kt:45-48, 347)。
- 影响:Keystore 失效后,WeClaw/钉钉的带令牌入站事件以 5 分钟间隔**无限重试**(每次都重新解密失败),占住共享派发器节拍、刷日志;与"不可自动恢复转人工复核"的既有原则不一致。
- 建议:令牌解密失败(非空白而是解密异常)归类为终态 BLOCKED(带错误码),与 checkpoint 同口径。

### F8. 飞书 webhook 入站不解析 content JSON,模型收到原始 JSON 串;与长连接路径行为不一致(P2,可信度:高)

- 位置:`app/src/main/java/io/zer0/muse/web/WebhookDispatch.kt:70` vs `app/src/main/java/io/zer0/muse/channel/FeishuReceiver.kt:207-223`
- 证据:
  ```kotlin
  // webhook 路径:text = message?.get("content")?.jsonPrimitive?.contentOrNull   ← content 本身是 JSON 字符串
  // 长连接路径:parseTextContent(...) 解析 {"text": "..."} 并剥 @ 占位符
  ```
- 影响:开启 webhook 的飞书渠道,自动回复的"用户消息"是 `{"text":"你好"}` 这样的原始 JSON(图片则是 `{"image_key":"..."}`),既污染上下文也会让模型把 JSON 当正文复读;同为 FEISHU 平台两条入站路径行为分叉。
- 建议:webhook 路径复用 `parseTextContent` 及消息类型占位映射。

### F9. 发送失败重试后会话库保留第一次生成的回复,用户实际收到第二次的回复(上下文漂移)(P2,可信度:高)

- 位置:`app/src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt:263-302` + `ChannelConversationStore.kt:114-136`
- 证据:`sendPreparedReply` **先** `appendTurn(assistant, reply, dispatchId)` 再发送;发送失败 → Retry → 下轮 `prepareReply` 因 `preparedReply` 未持久化而**重新跑模型生成新文本** `reply'`;第二次 `appendTurn(assistant, reply', 同一 dispatchId)` 被 `channelDispatchTurnIdentity in recentDispatchTurnIds` 去重**跳过**(ChannelConversationStore.kt:118-119)。
- 影响:重试成功后,对话上下文里是 reply(第一次),用户手机上收到的是 reply'(第二次);后续轮次模型基于未被用户看见的文本来延续对话,多轮后偏差累积。用户轮次有同样的 dispatchId 去重所以无重复(正确),仅 assistant 轮错位。
- 建议:发送失败时把已生成回复也持久化为 preparedReply(回滚 assistant 轮次或先落 preparedReply 再入轮次),保证"所见即所存"。

### F10. Tasker 结果通知硬编码 policy="always",无视用户全局通知策略(P2,可信度:高)

- 位置:`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:103-110`、`app/src/main/java/io/zer0/muse/notification/MuseNotificationManager.kt:194-210`
- 证据:`notifyChatCompletedWithPolicy(policy = "always", ...)`;而主链路把用户设置(`never/when_unfocused/always`)传入同一 API。
- 影响:用户即使选择"从不通知",每次 Tasker 触发都会在 `chat_completed` 通道(IMPORTANCE_LOW)弹出通知。
- 建议:改读用户策略,或在 Tasker 触发场景提供独立开关;另注意该通知无 POST_NOTIFICATIONS 权限检查(notify 静默丢弃,无用户可见反馈)。

### F11. Tasker 入站无持久化队列:进程死亡/冻结即静默丢请求(P2,可信度:中)

- 位置:`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:50-60, 63-80`
- 证据:`goAsync()` + 自建 `CoroutineScope(SupervisorJob()+IO)` 跑最长 120s 的 LLM 调用;prompt 只存在于 Intent extra 与内存,无任何落盘。对比:渠道入站有 ChannelInbox 持久化队列。
- 影响:后台进程在长 LLM 调用期间被冻结/杀掉(无前台服务保活),请求无声丢失,Tasker 侧得不到任何失败反馈(broadcast 已 finish 或进程死亡)。
- 建议:入站即落盘(轻量队列或直接复用 ChannelInbox 模式),完成后回执;或至少缩短为快速失败 + 通知"触发失败"。

### 附:低优先级备注(不计入发现)

- `FeishuPbbp2.decode` 对畸形长度字段无防御:`skipField` 的 `next + length.toInt()` 在 length 为超大值转 int 后为负,可使 `pos` 回退造成解析振荡,`copyOfRange` 越界依赖外层 `runCatching`(FeishuPbbp2.kt:98-104, 191-200)。对端是飞书服务端、攻击面小,建议补长度上界校验。
- Telegram 重启瞬间新旧两个 pollLoop 可能并行 getUpdates ≤45s,触发 Telegram 409 冲突后 5s 自愈,可接受。
- 媒体文件名 sanitize 已阻断路径穿越(`substringAfterLast('/')` + 时间戳前缀,WeClawReceiver.kt:200-203, 280-283);`channel_media` 清理保护集覆盖 inbox+对话引用( MuseApp.kt:301-320),但摘要压缩排出的旧轮次引用会在 30 天 TTL 后失效(设计内)。

## DEDUPE_CHANNEL 重点方向核实结论

1. **五渠道接收器生命周期现状**:restart 仅两处(MuseApp.onCreate:516-528、ChannelSettingsPage:85-91);循环以"无启用配置即退出"自管理,无 ON_STOP/ON_START 联动、无前台服务、无 WorkManager 兜底 → 见 F2/F5。
2. **ChannelInbox dispatcher 并发模型**:全局单消费者、跨平台串行,慢事件拖慢全平台但队列必排空(无永久饿死);100 槽容量全平台共享;claim 为 LIFO → 见 F6。
3. **A2A/Tasker 与渠道入站的会话并发**:不共享 ChannelConversationStore(A2A 走 ChatViewModel+SessionRepository,Tasker 走 ChatService+SessionRepository),无同文件并发写;但 A2A 存在全局 ChatViewModel 劫持/并发错配 → 见 F1/F11。
4. **webhook 慢 body deadline**:两条 webhook 路由(飞书/QQ)均经 `readLimitedRequestBody`(1MB/15s,WebhookRequestBody.kt:9-29, ChannelWebhookRoutes.kt:20-30, 67-77)——已全覆盖,闭环。
5. **通知回传**:渠道自动回复不发本地通知(回复直达平台,设计内);Tasker 结果通知用 IMPORTANCE_LOW 通道但硬编码 always → F10。
6. **channel_media 生命周期**:落盘 filesDir/channel_media(私有)、流式下载+上限保护+失败清理(WeClawClient.kt:303-352);清理器 30 天 TTL+512MB LRU+引用保护;缺口仅在 F4(存储恢复失败无隔离)与附注的压缩排出引用。

## 已知项核对(确认不重复上报)

- **at-least-once 外发语义**(全局已知 1):`sendPreparedReply` 发送成功后才 `completeDispatch`,崩溃窗口重复发送——设计边界,未报。
- **ProcessWriteGate volatile 语义**(全局已知 2):`persist()`/`claimEvent` 的写前检查(ChannelInbox.kt:426, 458;ChannelConversationStore.kt:213)与恢复期 1s 退避重试行为一致,未报。
- **队列满拒绝 / PENDING 先落盘 / PROCESSING 恢复 / BLOCKED 不循环重试**(基线已确认):record→persist→claim 顺序(:222-230)、`recoverInterruptedDispatches`(:250-265)、`blockDispatch`(:316-328)均落实,未报。
- **Telegram 整批入站才推进 offset**(基线已确认):TelegramReceiver.kt:66-89 逐条 record 成功后才 `saveOffset`,失败保留游标重试,闭环;仅存的"游标保存失败+>10min+>100 条历史淘汰"重放窗口过窄且被 inbox 匹配+去重账本双重缓解,未列为发现。
- **WeClaw 游标/context token 加密持久化、写失败不推进**(基线已确认):WeClawReceiver.kt:96, 115-116, 21-41,闭环。
- **QQ 绑定原 sourceEventId**(基线已确认):ChannelAutoReply.kt:277-291 + QqClient.replyContext(QqClient.kt:139-145)落实;60 分钟窗外回退主动消息正确,未报。
- **恢复闸门 fail-closed / webhook 异常 503**(基线已确认):record 失败→503(ChannelWebhookRoutes.kt:44-49, 100-105)、恢复期 persist=false→check 抛错重试,闭环。
- **渠道媒体清理保护**(基线已确认):MuseApp.kt:301-320,闭环。
- **群聊 channel_reply/pass/read_context 与本链路无关**(GroupChatRepository 独立事务写,GroupChatRepository.kt:248-269),未报。
- **安全已修项**(全局已知 11):webhook 默认关闭(ChannelModels.kt:34)、飞书加密回调 501(WebhookDispatch.kt:35-37)、QQ 签名/时间窗 fail-closed(WebhookAuthPolicy.kt:81-103)、钉钉 sessionWebhook 落盘脱敏(DingtalkReceiver.kt:22-27)、日志 PII 脱敏(Logger.kt:142-153),抽查达标,未报。
- **真实平台矩阵验收缺失**(基线已知):未报;F3 的"QQ 无 RESUME 丢事件"基于平台公开语义标注可信度:中,建议真机验证。

## 建议修复优先级

1. **F1**(A2A 会话隔离)——v2.5.8 已发行的功能,并发/人机共存必现错配。
2. **F2**(接收器永久死亡路径)——可用性根因,改动小(config==null 退避等待 + loadOffset 容错)。
3. **F6/F7**(派发器超时与 BLOCKED 口径)——共享队列下的体验与一致性。
