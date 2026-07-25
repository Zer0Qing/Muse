<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 聊天页面 ChatScreen 特性 深度思考 主动消息 联网搜索 知识库

当用户问"聊天页有什么功能""深度思考怎么开""联网搜索怎么用""主动消息怎么设置""知识库在哪"时参考本文档。

深度思考:
- 开关位置: 聊天输入栏的 + 号菜单(Icons.Default.Psychology,标签"深度思考"或"深度思考(已开启)")。
- 行为: 开启后该次对话用 ReasoningLevel.HIGH(8000 tokens)推理,覆盖助手默认 reasoningLevel。
- 状态: 仅运行时内存状态,不持久化。切换会话或重启 app 后恢复助手默认 reasoningLevel。

联网搜索:
- 开关位置: 聊天输入栏 + 号菜单。
- 默认 provider: Bing,抓 cn.bing.com/search + regex 解析 <li class="b_algo">,不需要 API key。
- 其他 provider: SearXNG(自托管,无需 key)/ Tavily(需 API key)。在 设置 → 模型与服务 → Web 搜索 配置,或在 设置 → 聊天 → 默认搜索引擎 切换。
- 启用后每次对话先搜前 N 条结果作为上下文注入 system prompt。

主动消息:
- 入口: 设置 → 聊天 → 主动消息。
- 配置: enabled 开关 + 发送间隔(1/2/4/8/12/24 小时)。
- 运行: ProactiveMessageRunner 每 60 秒轮询,到点后用默认助手 + 第一个会话上下文生成消息,插入会话并弹通知(IMPORTANCE_HIGH,标题"X 来消息了")。
- 风格: system prompt 要求像真人发微信,短纯文本,不带前缀,temperature=0.8 maxTokens=150。

长消息折叠:
- 长回复有折叠 + 渐变遮罩,点击展开。

消息气泡操作菜单:
- MessageBubble 点击或长按弹出操作菜单(复制/重发/删除等)。

知识库:
- 入口: 设置 → Skill 页可启用/禁用 knowledge_search skill;设置 → 知识库 可导入文档。
- 注意: isInternal=true 的条目是内部开发文档(本文件即其一),只供 LLM 通过 knowledge_search 查询,不在知识库 UI 列表显示,也不在「引用知识库」选择器中出现。

回答用户时应基于上述真实特性,不要编造不存在的功能入口。

## 语音功能(ASR / TTS / 语音对话)

当用户问"语音输入怎么用""能不能说话""朗读""语音对话""ASR""TTS""识别语音"时参考本节。

### ASR(语音识别,Speech-to-Text)
- **入口**: 聊天输入栏的麦克风图标(按住说话 / 点击进入语音对话模式)。
- **Provider** (设置 → 模型与服务 → ASR):
  - SYSTEM: 系统 ACTION_RECOGNIZE_SPEECH Intent(默认,无网络依赖但依赖厂商服务,国产 ROM 可能缺失)
  - DASHSCOPE: 阿里云 Paraformer 实时流式(WebSocket,wss://dashscope.aliyuncs.com/api-ws/v1/inference)
  - STEP: 阶跃星辰 Step-Audio(OpenAI 兼容 API,audio base64 输入)
  - DASHSCOPE_FILE: DashScope 异步文件转录(适合长音频,submit → query 轮询)
  - OPENAI_WHISPER: 通用 OpenAI Whisper 兼容端点(multipart POST /audio/transcriptions)
  - OPENAI_REALTIME: OpenAI Realtime WebSocket 流式(服务端 VAD + 增量 transcription,真流式)
  - AGNES: Agnes 中转站(OpenAI 兼容,内部走 OPENAI_WHISPER 适配器)
- **配置项**: provider / apiKey / model / sampleRate(默认 16000) / language(默认 zh) / 热词列表 / 本地 VAD(静音检测,vadEnabled / vadThreshold / vadSilenceDurationMs)
- **识别后文本进入输入框**,用户可编辑后发送,不会自动发送(除非语音对话模式)。

### TTS(语音合成,Text-to-Speech)
- **入口**: 消息气泡操作菜单 → 朗读;或语音对话模式下自动朗读 AI 回复。
- **引擎** (设置 → 模型与服务 → TTS / 媒体):
  - 系统内置 Android TextToSpeech(默认,免费离线)
  - 云端 TTS: OpenAI / MiniMax / Edge TTS(兼容代理)/ DashScope / Groq / Qwen
- **配置项**: engine / voice(音色)/ speed(语速)/ pitch(音高)/ language
- **音色克隆**: 设置 → 媒体 → 音色克隆,可录制少量样本克隆个人音色(部分云端引擎支持)。
- **流式朗读**: 长文本会分段流式合成播放(speakStream),避免等整段合成完才发声。

### 语音对话模式(VoiceConversationMode)
- **入口**: 聊天页麦克风按钮长按或语音对话图标,进入全屏覆盖式 ASR + AI + TTS 连续对话。
- **流程**: ASR 实时识别 → 识别结果作为输入发送 → AI 流式回复 → TTS 自动朗读 → 朗读完继续听 → 循环。
- **适用场景**: 开车、做饭、运动等不方便打字时;连续陪伴对话。
- **退出**: 点击关闭按钮或返回手势。

### 何时用语音(LLM 行为指引)
- 用户发文字消息时,**不要主动调 TTS 朗读**(除非用户明确要求"读出来""朗读")。TTS 由用户通过消息气泡菜单触发,或语音对话模式下自动触发。
- 用户发语音(ASR 转文字)时,正常文字回复即可,不要因为对方用了语音就也用语音回复(你只能输出文字,TTS 是用户侧操作)。
- 用户在语音对话模式下,回复应更口语、更短(朗读时长不宜过长),避免长列表 / 长代码块(朗读体验差)。若必须给代码,先用一句话总结,再说"详细代码我贴在聊天里了,你看屏幕"。
- 不要假装"我正在听""我听到了你的声音"—— 你只接收文字,不知道用户是否用语音输入。

### ASR/TTS 故障排查(用户问"语音用不了"时)
- ASR 没反应: 检查 provider 配置 / apiKey / 麦克风权限 / 系统 Intent 是否可用(SYSTEM 模式)
- ASR 识别不准: 加热词(专有名词)/ 换 DASHSCOPE 或 OPENAI_REALTIME(比 SYSTEM 准)/ 检查 sampleRate 与设备匹配
- TTS 没声音: 检查引擎配置 / 音量 / 音频输出方式(听筒 vs 扬声器,设置 → 媒体)
- TTS 很慢: 云端 TTS 首次合成有延迟,换系统内置引擎(离线快)或开启流式朗读
