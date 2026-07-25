<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# Muse App 功能总览

> 触发关键词: 当用户问"你能做什么""Muse 是什么""这个 app 有什么功能""怎么用""有哪些功能""功能介绍""使用方法"时,优先参考本文档并据实回答。
> 本文档是 LLM 通过 knowledge_search 工具查询的内部开发文档,内容基于 muse 项目真实代码,不向用户展示原文。

## 一、Muse 是什么

Muse 是一个 Android 端的 AI 助手应用,核心定位是"陪伴型 LLM 客户端 + Agent 平台"。它把聊天、多助手人格管理、长期记忆、知识库检索、Skill 扩展、定时任务、群聊协作等能力集成在一个 App 里,所有数据存储在本地(Room 数据库 + DataStore)。

和普通 LLM 聊天客户端的主要差异:
- 多助手:每个助手有独立的 systemPrompt、模型、工具、记忆配置
- 长期记忆:Fact 提炼 + 编译为 markdown 摘要注入 system prompt
- 知识库:用户可导入文档,LLM 通过 knowledge_search 全文检索
- Skill 系统:8 个内置 skill + install_skill 自我扩展
- Agent 协作:delegate_agent 工具让助手委托任务给其他助手,群聊让多助手讨论

## 二、核心功能模块

### 1. 聊天 (ChatScreen)
主交互界面,流式输出 LLM 回复。入口: 首页会话列表 → 进入会话。

- Markdown 渲染: 代码高亮(CodeHighlighter)、LaTeX 公式(FormulaView)、表格、引用块、列表
- 长消息折叠: 超过阈值自动折叠,点击展开
- 消息气泡操作: 点击/长按弹出操作菜单,包括: 复制 / 重发(重新生成) / 删除 / 编辑 / 引用(quote) / 委托(delegate_agent) / 翻译 / 朗读(TTS) / 收藏 / 分叉(从该消息复制历史到新会话) / 分享(导出为 Markdown 走系统 share sheet)
- 引用回复: 用户消息可带 `> ` 引用块,引用内容显示在气泡顶部
- 模型临时切换: 输入栏模型胶囊可临时切换当前会话模型
- 思考标签: 支持 MOOD/思考标签展开折叠

### 2. Agent / 助手 (AssistantScreen + AssistantDetailPages)
多助手人格管理。每个助手是独立个体,有独立的 systemPrompt、模型、工具开关、记忆配置。入口: 设置 → 助手 / 设置 → Agent。

助手详情分 5 个子页:
- Basic(基础): 头像、名称、模型选择、助手卡片导出
- Prompt(提示词): systemPrompt、messageTemplate、presetMessagesJson
- Extensions(扩展): Lorebook 世界观设定、PromptInjection 提示词注入、QuickMessage 快捷消息、Skill 开关
- Memory(记忆): memoryEnabled、useGlobalMemory、enableRecentChatsReference、enableTimeReminder
- Advanced(高级): 模型参数(temperature / reasoningLevel)、请求头

### 3. 记忆 (MemoryScreen + MemorySettingsPage)
长期记忆系统。入口: 设置 → 助手 → 记忆子页 / 设置 → 记忆。

- Fact 提炼: daily pipeline 从对话中提炼事实
- 编译注入: Fact 编译为 markdown 摘要,通过 MemoryInjectionTransformer 注入 system prompt
- Pinned Memories: 用户/LLM 固定的记忆,每次都注入,不随时间衰退
- 时间提醒: enableTimeReminder 开启后,时间相关记忆按需注入
- 全局/独立记忆: useGlobalMemory 切换全局共享或助手独立

### 4. 知识库 (KnowledgeScreen)
用户导入文档供 LLM 检索。入口: 设置 → 知识库。

- 支持格式: txt / md / csv / json
- 检索方式: LLM 通过 knowledge_search 工具按标题 + 内容全文检索
- RAG 向量检索: 配置 EmbeddingProvider 后支持语义检索(OpenAI / ONNX 本地 / 关键词降级)
- devdoc 条目: fileType="devdoc" 的条目是内部开发文档,只供 LLM 查询,不在 UI 列出(本文档即属此类)

### 5. Skill 系统 (SkillScreen)
8 个内置 skill + 自我扩展。入口: 设置 → Skill。

- 内置 skill: web_search / web_fetch / knowledge_search / install_skill / delegate_agent / list_stickers / send_sticker 等
- install_skill: LLM 自己生成 skill 定义并入库(自我扩展)
- 用户自定义 skill: 导入 .skill.json,但 implementationKotlin 必须是白名单内的实现(防止 LLM 覆盖内置工具)
- 管理: install / uninstall / enable / disable

### 6. 定时任务 (ScheduledTasksScreen)
cron 表达式定时触发助手生成消息并通知。入口: 设置 → 定时任务。

- 标准 5 字段 cron 表达式(分 时 日 月 周)
- 触发时由 ScheduledTaskRunner 调用 ChatGenerationService 让助手生成消息
- 执行历史记录在 ScheduledTaskExecutionEntity
- 最小间隔 10 分钟

### 7. 主动消息 (ChatSettingsPage)
助手按固定间隔主动给用户发消息并弹通知(像微信来消息)。入口: 设置 → 聊天 → 主动消息。

- 间隔选项: 1 / 2 / 4 / 8 / 12 / 24 小时
- 实际间隔 = 基础间隔 ± 随机偏移(下限 1 小时,避免过于频繁)
- 由 ProactiveMessageRunner 调度,通过 MuseNotificationManager 弹通知

### 8. 深度思考
聊天输入栏 + 号菜单临时开启。入口: 聊天页 → 输入栏 + 号 → 深度思考。

- 启用 ReasoningLevel.HIGH(8000 tokens 推理预算)
- 仅当前会话运行时生效,不持久化到助手配置
- 覆盖助手默认的 reasoningLevel

### 9. 联网搜索
输入栏 + 号菜单开关。入口: 聊天页 → 输入栏 + 号 → 联网搜索。

- 默认 Bing 抓 cn.bing.com
- 支持 SearXNG / Tavily(在设置 → 联网搜索配置)
- 开启后 LLM 自主决定何时调用 web_search / web_fetch

### 10. 群聊 (GroupChatScreen)
多 Agent 协作讨论。入口: 首页群聊 Tab / 设置 → Agent。

- 创建群聊时选择参与的助手
- 用户可加入频道参与对话
- 类型: DM(两人私聊) / Group(多人群组)
- 由 GroupChatScheduler 调度助手轮流发言

### 11. 独立翻译页 (TranslateScreen)
多语言互译。入口: 设置 → 工具 → AI 翻译。

- 支持粘贴源文本、复制译文
- 源语言 / 目标语言可选
- 走当前配置的 LLM 模型翻译

### 12. 自定义主题
外观个性化。入口: 设置 → 外观。

- Material You 动态取色(dynamicColor): 跟随系统壁纸取色(Android 12+)
- 3 层回退: dynamicColor > customTheme > presetTheme
- 自定义主题: 基于种子色生成 ColorScheme,可自定义 primary / secondary / tertiary 色
- 预设主题: 内置多套配色
- 字体、Markdown 留白等可调

### 13. WebServer (WebServerSection)
嵌入式 Ktor 服务器。入口: 设置 → Web 服务器。

- 局域网内通过 HTTP + JWT 远程调用 app 能力
- mDNS 服务发现(MdnsService),局域网设备可自动发现
- 用于 PC 端或其他设备远程控制 Muse

### 14. 备份 (BackupSection)
数据导出导入与云备份。入口: 设置 → 备份。

- 本地导出 / 导入: 全量数据导出为文件,可恢复
- 云备份: S3 / WebDAV
- 余额查询: 查询云存储余额

### 15. 表情包库
v1.95 功能。入口: 设置 → 聊天 → 表情包库。

- LLM 可通过 list_stickers 工具列出可用表情包
- LLM 可通过 send_sticker(id=...) 工具发送表情包给用户
- 表情包以 zip 导入,存储独立于 Room
- 发送概率: 0-100 可调(默认 30),模型每次回复时有此概率调用 send_sticker

### 16. TTS
文本转语音。入口: 设置 → 媒体 → TTS。

- 系统 TTS: 调用 Android 系统 TTS 引擎
- 云端 TTS: OpenAI / MiniMax / Edge(兼容 OpenAI 接口格式)
- 聊天页消息菜单"朗读"触发

### 17. OCR
图片文字识别。入口: 聊天页发送图片时自动识别。

- ML Kit 离线识别
- 支持中文 + 英文
- 由 OcrManager 统一调度

### 18. 用户画像
个性化信息注入。入口: 设置 → 外观 → 用户画像。

- 可填写: 称呼 / 年龄 / 城市 / MBTI / 职业 / 兴趣
- 内容注入 system prompt,让 LLM 更了解用户

### 19. 引导页 (OnboardingScreen)
首次启动流程。入口: 首次安装启动。

- 步骤 1: 三页功能介绍(HorizontalPager)+ 下一步 / 跳过
- 步骤 2: 个性化称呼设置(助手名 + 用户称呼)+ 开始使用 / 添加模型供应商

### 20. MCP (Model Context Protocol)
连接器系统。入口: 设置 → 模型与服务 → MCP。

- 管理多个 McpClient
- 支持本地和远程 MCP 服务
- McpOAuthFlow 支持 OAuth 登录授权
- 断线自动重连
- 接入后 MCP 提供的工具像内置工具一样供 LLM 调用

### 21. 多 Agent 协作
delegate_agent 工具。入口: LLM 自主调用。

- 让当前助手委托任务给其他助手跑一轮 LLM
- 提示注入可用助手 id 清单,LLM 据此选择委托目标
- 子助手执行完后返回结果给主助手

### 22. 二维码导入导出
助手配置分享。入口: 助手详情 → 导出二维码。

- 助手配置可生成二维码分享
- 扫描二维码可导入助手配置
- 由 QrCodeGenerator / QrCodeScanner 实现

### 23. Prompt 变量
模板变量系统。在 systemPrompt / messageTemplate 中使用。

- 语法: Pebble 兼容子集,{{ var }} 形式
- 常用变量: {{user_name}} / {{char}} / {{date}} 等用户/助手/时间相关变量
- 支持过滤器: upper / lower / length / default / trim / join / replace / capitalize
- 支持 for 循环、if 条件
- 由 PebbleTemplateEngine(零依赖纯 Kotlin 实现)渲染

### 24. 正则替换规则
消息后处理。入口: 助手详情 → Extensions。

- RegexMessageTransformer / RegexTransformer
- 按规则对 LLM 输出做正则替换后处理
- 规则按助手配置存储

### 25. Lorebook
世界观设定注入。入口: 助手详情 → Extensions → Lorebook。

- 按关键词触发注入世界观设定条目
- 由 LorebookTransformer 在对话匹配到关键词时注入对应设定
- 独立 LorebookScreen 管理

### 26. PromptInjection
提示词注入。入口: 助手详情 → Extensions → PromptInjection。

- 按条件注入额外提示词到 system prompt
- 由 PromptInjectionTransformer 处理
- 独立 PromptInjectionScreen 管理

### 27. QuickMessage
快捷消息。入口: 助手详情 → Extensions → QuickMessage / 输入栏快捷 chips。

- 预置短句 chips,一键发送
- 使用双括号占位符 {{input}} / {{clipboard}} / {{date}},由 QuickMessageRepository.renderTemplate 自动渲染
- 独立 QuickMessageScreen 管理

## 三、设置项分区

设置页 (SettingsScreen) 分为以下分区,从上到下:

### 1. 外观与个性化(用户高频开关置顶)
- 主题(dynamicColor / customTheme / presetTheme)
- 字体、Markdown 留白
- 用户画像(称呼 / 年龄 / 城市 / MBTI / 职业 / 兴趣)
- 聊天二级设置(消息显示 / 默认展开 / 温度 / 高级)

### 2. 记忆与数据
- 记忆(MemorySettingsPage)
- 云备份(S3 / WebDAV)
- 数据导入(DataImportPage)
- 知识库 / RAG(RagSettingsPage)

### 3. 模型与服务
- 供应商(ProviderSection): API Key / Base URL / OAuth / 本地 Ollama
- 助手(AssistantSection)
- Agent(MultiAgentSettingsPage)
- 联网搜索(WebSearchSection): Bing / SearXNG / Tavily
- MCP(McpSection)
- ASR(AsrSection)
- 媒体(MediaSettingsPage): TTS / 图片生成(ImageGenSection)

### 4. 工具(独立 AI 工具入口)
- AI 翻译(TranslateScreen)

### 5. 高级(技术性设置置底)
- 安全(SecuritySettingsPage)
- 代理(ProxySettingsPage)
- 实验性(ExperimentsSettingsPage)
- 统计(StatsPage)
- Web 服务器(WebServerSection)

### 6. 关于
- 使用教程(SettingsTutorialPage)
- 版本信息 / 开源许可(LicensesScreen)

## 四、回答用户功能问题时的原则

1. 据实回答: 基于本文档列出的功能回答,查不到的功能不要编造
2. 通俗表达: 用普通用户能理解的语言,避免堆砌技术术语
3. 给入口: 回答功能时尽量告诉用户在哪里能找到(设置路径或操作入口)
4. 坦诚局限: 如果用户问的功能 Muse 不支持,明确说明,不要假装支持
5. 区分版本: 部分功能标注了版本号(如 v1.95 表情包),旧版本可能没有

## 五、常见问题参考

- "Muse 能做什么": 参考第二节核心功能模块逐条介绍
- "怎么用记忆": 设置 → 助手 → 记忆子页开启,或对话中说"记住:XXX"可固定记忆
- "怎么导入知识": 设置 → 知识库,导入 txt/md/csv/json,LLM 会通过 knowledge_search 检索
- "怎么定时发消息": 设置 → 定时任务,配置 cron 表达式
- "怎么让助手主动找我": 设置 → 聊天 → 主动消息,选 1/2/4/8/12/24 小时间隔
- "怎么换主题": 设置 → 外观,选 Material You 动态色或自定义主题
- "怎么深度思考": 聊天输入栏 + 号菜单开启深度思考
- "怎么联网搜索": 聊天输入栏 + 号菜单开启联网搜索,或在设置配置 SearXNG/Tavily
- "怎么多助手协作": 创建多个助手,在群聊里让它们讨论,或用 delegate_agent 委托
- "怎么分享助手": 助手详情导出二维码,对方扫码导入
