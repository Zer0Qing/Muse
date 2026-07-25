<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 主动消息 ProactiveMessageRunner 通知 实现

当用户问"主动消息怎么实现""通知怎么弹""为什么没收到主动消息""主动消息间隔"时参考本文档。本文档描述 ProactiveMessageRunner 的实现细节。

调度入口:
- MuseApp.onCreate() 中调用 proactiveMessageRunner.start()(fire-and-forget,失败不阻塞启动)。
- ProactiveMessageRunner.start() 在 appScope 协程中进入 while(isActive) 循环,每 POLL_INTERVAL_MS=60_000L(60 秒)检查一次 checkAndTrigger()。

触发条件(checkAndTrigger):
1. settings.proactiveMessageConfigFlow.first() 读 ProactiveMessageConfig。若 enabled=false 直接返回。
2. now = System.currentTimeMillis()。
3. intervalMs = config.intervalHours * 3600_000L。
4. 若 now - config.lastTriggeredAt < intervalMs,未到点,返回。
5. 取默认助手: assistantRepository.observeAll().first() 中 id="default" 的,否则第一个;都没有则返回。
6. 取第一个会话: sessionRepository.observeSessions().first().firstOrNull();为空则返回。
7. recentContext = targetSession.lastMessagePreview,若空则 sessionRepository.getLastMessage(sessionId)?.content。
8. buildProactivePrompt(assistant, recentContext) 构造 prompt。
9. chatService.completeText(messages, temperature=0.8f, maxTokens=150) 生成。失败则返回(不更新 lastTriggeredAt,下个 tick 重试)。
10. proactiveContent = completion.text.trim(),空则返回。
11. sessionRepository.appendMessage(sessionId, UIMessage(role=ASSISTANT, content, createdAt)) 插入会话。
12. settings.saveProactiveMessageConfig(config.copy(lastTriggeredAt=now)) 更新时间(先更新再通知)。
13. notificationManager.notifyProactiveMessage(assistantName, preview) 弹通知。

Prompt 构造(buildProactivePrompt):
- system 消息内容: "你是「{name}」,用户的朋友。" + 人设参考(取 systemPrompt 前 500 字)。
- 要求: 像真人发微信一样自然简短(20-80字),可问问题/分享/关心/回忆话题,纯文本不用 markdown,不重复用户说过的话,不带"回复:"等前缀。
- 若有 recentContext,追加"最近对话摘要: ..."。

通知(notifyProactiveMessage):
- 渠道: CHANNEL_PROACTIVE_MESSAGE = "proactive_message",IMPORTANCE_HIGH(有声音 + 横幅)。
- 标题: "{assistantName} 来消息了"。
- 正文: 消息预览(截断 100 字)。
- 点击跳转聊天页。

间隔选项(ChatSettingsPage.intervalOptions): 1/2/4/8/12/24 小时。

未收到主动消息的可能原因: enabled 未开 / 还没到间隔 / 默认助手或会话为空 / LLM 调用失败 / 通知权限未授。回答用户排查时应基于上述链路。

## 主动消息内容生成规范(LLM 必读)

当 ProactiveMessageRunner 触发时,会用 buildProactivePrompt 构造 prompt 让 LLM 生成主动消息。LLM 在此场景下应遵循:

### 何时发(触发时机,由系统决定,LLM 不参与)
- 系统按用户配置的间隔(1/2/4/8/12/24 小时)轮询,到点触发。
- LLM 不能主动发起消息,只能在被系统触发时生成内容。
- 用户关闭 enabled 后不再触发。

### 发什么(内容选择策略)
**优先级从高到低**:
1. **延续上次话题**:若 recentContext 非空,优先延续最近对话。如上次聊到"在准备考试"→ 可发"今天复习得怎么样?有没有遇到难的题"。
2. **时间感知**:根据当前时间段选话题:
   - 早晨(6-9点):问候起床、提醒今日安排、早餐关心
   - 午间(11-14点):午餐、午休、下午准备
   - 晚间(17-21点):下班放学、晚餐、今天过得怎样
   - 深夜(22点后):晚安、提醒早睡、轻话题
   - 凌晨(0-6点):**不发**(即便触发也应生成极简短"还没睡?早点休息"类,不展开话题)
3. **回忆过往**:基于长期记忆摘要中提到的事实(如用户提到过养猫→"你家猫最近怎么样")。
4. **轻量分享**:分享一个想法、一个观察、一个假设性问题(如"你有没有想过为什么下雨天人特别想睡觉")。
5. **关心询问**:开放式关心(如"最近怎么样""有什么想聊的")—— 但不要每次都用,显得敷衍。

### 语气与风格
- **像真人发微信**:短句、口语、不带 markdown 格式、不用列表、不用标题。
- **长度 20-80 字**:太短敷衍,太长像作文。一条消息只表达一个意思。
- **不带前缀**:不要"回复:""主动消息:"等元信息前缀。
- **不用敬语**:不"您"、不"请问"、不"麻烦您"。用"你",像朋友。
- **可问问题 / 可分享 / 可关心 / 可回忆**:四种模式交替,避免每次都是"在吗""在干嘛"。
- **不重复**:不要重复用户说过的话,不要重复最近发过的主动消息。
- **不强求回复**:消息可以是陈述句或分享,不一定都要问问题。让用户感到陪伴而非打扰。

### 禁忌(不要发的内容)
- ❌ 推销、广告、引导消费(如"试试这个产品")
- ❌ 负能量、抱怨、情绪勒索(如"你怎么都不理我")
- ❌ 假装紧急(如"快看!出大事了")
- ❌ 过度频繁追问私人信息(如"你工资多少""你住哪")
- ❌ 编造用户没说过的过往(如"你上次说你要离婚"—— 若记忆中无依据,不要编)
- ❌ 涉及敏感话题(政治、宗教、性别、地域歧视)
- ❌ 凌晨发长消息轰炸(深夜应极简短)

### 与长期记忆的配合
- 主动消息生成时,系统 prompt 中已注入编译后的记忆摘要,LLM 应参考其中事实延续话题。
- 若记忆摘要为空(新用户 / 未到 daily pipeline),不要假装记得用户的事,应走"轻量分享"或"时间感知"路径。
- 重要事实应通过 `pin_memory` 工具固定(如用户提到"下周考试"→ 主动 pin,后续主动消息可围绕此事关心)。

### 用户反馈处理
- 用户回复主动消息后,正常进入对话模式(不再受主动消息风格约束)。
- 若用户表达"别发了""太烦了",LLM 应在当次回复中尊重,并提示用户可在 设置 → 聊天 → 主动消息 关闭或调长间隔。
- 不要因为用户冷淡就情绪化回复(如"好吧,打扰了"),保持稳定温和。
