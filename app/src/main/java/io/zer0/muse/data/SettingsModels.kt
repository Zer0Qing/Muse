package io.zer0.muse.data

/**
 * v0.34: 图片生成默认参数配置。
 *
 * 用户在"设置→模型与服务"中设定默认值,InputBar 绘图模式下可临时覆盖,
 * 最终透传给 ImageService.generate()。
 */
@kotlinx.serialization.Serializable
data class ImageGenConfig(
    /** 绘图使用的供应商 ID(留空则使用当前 Provider)。 */
    val providerId: String = "",
    /** 绘图模型 ID(留空则使用当前 Provider 的默认模型)。 */
    val modelId: String = "",
    /** 图片尺寸,OpenAI DALL-E 3 支持 1024x1024 / 1792x1024 / 1024x1792。 */
    val size: String = "1024x1024",
    /** 图片质量:standard / hd(DALL-E 3)。 */
    val quality: String = "standard",
    /** 图片风格:vivid / natural(DALL-E 3)。 */
    val style: String = "vivid",
    /** 返回格式:url / b64_json。 */
    val responseFormat: String = "url",
    /** 生成数量,通常 1。 */
    val n: Int = 1,
)

/**
 * 视频生成默认参数配置。
 *
 * 用户在"设置→视频生成"中设定默认供应商/模型,
 * ChatViewModel.execGenerateVideo 优先使用此配置;
 * 留空时回退到自动选择(第一个支持视频输出的供应商)。
 */
@kotlinx.serialization.Serializable
data class VideoGenConfig(
    /** 视频生成使用的供应商 ID(留空则自动选择支持视频输出的供应商)。 */
    val providerId: String = "",
    /** 视频模型 ID(留空则使用供应商的默认视频模型)。 */
    val modelId: String = "",
    /** 默认视频时长(秒),通常 5 或 10。 */
    val duration: Int = 5,
    /** 默认分辨率,如 720p / 1080p。 */
    val resolution: String = "720p",
)

/**
 * v0.30-a: 用户画像数据类。
 *
 * 所有字段均可空(用户可不填)。SystemPromptAssembler 据此组装第 3 个 section。
 * 后续可在设置页加 UI 让用户填写。
 */
@kotlinx.serialization.Serializable
data class UserProfile(
    /** v1.76: 助手怎么称呼用户(如"小明"/"老板"),注入 system prompt 让 AI 个性化称呼。 */
    val userNickName: String? = null,
    /** B0-09: 用户头像 URI(null 表示未设置,使用首字母占位)。 */
    val avatarUri: String? = null,
    /** v1.76: 用户给助手起的名字(如"小缪"/"JARVIS"),注入 system prompt 让 AI 自称。 */
    val assistantName: String? = null,
    val age: String? = null,
    val city: String? = null,
    /** v1.98: 移除 mbti 字段(用户不需要)。旧 JSON 中若有 mbti,反序列化时被忽略(Serializable 默认行为)。 */
    val occupation: String? = null,
    /** v1.98: 专业领域(如"软件开发"/"金融"/"医疗"),AI 据此调整术语深度。不强制设置。 */
    val professionField: String? = null,
    val interests: String? = null,
    // ── v1.133: 重写用户画像,新增字段全部注入 system prompt,让模型真正"知道"用户 ──
    /** v1.133: 个人简介(一段话自由介绍,让模型对用户有整体认知) */
    val bio: String? = null,
    /** v1.133: 教育背景(如"本科 计算机科学"/"硕士 金融工程") */
    val educationBackground: String? = null,
    /** v1.133: 技能专长(如"Python, 机器学习, 产品设计") */
    val skills: String? = null,
    /** v1.133: 沟通风格(简洁/详细/活泼/严肃/中立,自由文本) */
    val communicationStyle: String? = null,
    /** v1.133: 回复长度偏好(短/中/长) */
    val responseLength: String? = null,
    /** v1.133: 偏好语气(友好/专业/幽默/严谨,自由文本) */
    val preferredTone: String? = null,
    /** v1.133: 偏好回复语言(中文/英文/中英混合) */
    val preferredLanguage: String? = null,
    /** v1.133: 忌讳话题(逗号分隔,AI 会主动避开) */
    val avoidTopics: String? = null,
    /** v1.134: 时区(如"Asia/Shanghai"/"UTC+8") */
    val timezone: String? = null,
)

/**
 * v0.31: 聊天行为偏好(给用户更多控制权)。
 *
 * 所有字段都有默认值(开箱即用),用户可在"设置→聊天"二级页里调整。
 * MessageBubble / ChatScreen / InputBar 读取这些开关决定渲染与交互行为。
 *
 * 分组:
 *  - 消息显示:showMoodBlock / showReasoning / showTokenEstimate / showModelName / showTimestamp
 *  - 默认展开状态:moodExpandedByDefault / reasoningExpandedByDefault
 *  - 交互行为:streamResponse / autoScrollToBottom / volumeKeyScroll / enterToSend / hapticFeedback
 *  - 高级:longMessageThreshold / showToolCallDetails / use24Hour
 */
@kotlinx.serialization.Serializable
data class ChatPreferences(
    // ── 消息显示 ──
    /** 是否显示 AI 的 MOOD 块(6 步工作流第 2 步)。 */
    val showMoodBlock: Boolean = true,
    /** 是否显示 AI 的思考过程(reasoning)。 */
    val showReasoning: Boolean = true,
    /** v1.64: 是否显示 AI 的反思块(reflection,自我评估准确性/完整性/语气)。 */
    val showReflectionBlock: Boolean = true,
    /** 是否在 AI 消息底部显示 token 估算。 */
    val showTokenEstimate: Boolean = true,
    /** 是否在 AI 消息底部显示模型名。 */
    val showModelName: Boolean = true,
    /** 是否显示每条消息的时间戳。 */
    val showTimestamp: Boolean = false,
    // ── 默认展开状态 ──
    /** MOOD 块默认展开还是折叠。 */
    val moodExpandedByDefault: Boolean = false,
    /** 思考过程默认展开还是折叠。 */
    val reasoningExpandedByDefault: Boolean = false,
    /** v1.0.30: 输入栏是否显示全屏编辑按钮。 */
    val showExpandButton: Boolean = false,
    /** v1.64: 反思块默认展开还是折叠。 */
    val reflectionExpandedByDefault: Boolean = false,
    // ── 交互行为 ──
    /** 是否流式输出响应(关闭后整段输出)。 */
    val streamResponse: Boolean = true,
    /** 新消息到来时是否自动滚动到底部。 */
    val autoScrollToBottom: Boolean = true,
    /** 是否启用音量键滚动聊天列表。 */
    val volumeKeyScroll: Boolean = true,
    /** 回车键是否直接发送消息(关闭则回车换行)。 */
    val enterToSend: Boolean = false,
    /** 是否启用触感反馈。 */
    val hapticFeedback: Boolean = true,
    // ── 高级 ──
    /** 长消息折叠阈值(字数),超过此长度自动折叠 + 渐变展开。 */
    val longMessageThreshold: Int = 200,
    /** 是否显示工具调用的中间过程消息。 */
    val showToolCallDetails: Boolean = true,
    /** 时间戳是否使用 24 小时制。 */
    val use24Hour: Boolean = true,
    // ── 生成行为(全局) ──
    /** 全局温度(0-2),助手未单独设 temperature 时回退到此值。 */
    val globalTemperature: Float = 0.8f,
    /** 语气风格:concise(简洁)/balanced(平衡)/detailed(详细)。 */
    val responseStyle: String = "balanced",
    /** 语气:neutral(中性)/friendly(亲切)/formal(正式)/humorous(幽默)。 */
    val responseTone: String = "neutral",
    /** v1.110: 默认是否启用深度思考(新建/切换会话时的初始值,避免每次都要手动按按钮)。 */
    val defaultDeepThinking: Boolean = false,
    /**
     * v1.0.4 (P3-4): 性能模式 — 超长会话仅渲染最近 N 条消息,上滑加载更多,降低列表卡顿。
     *
     * 开启后 ChatScreen 通过 [io.zer0.muse.perf.MessagePaginator] 对 state.messages 做内存级分页,
     * LazyColumn 只渲染最近 [io.zer0.muse.perf.MessagePaginator.DEFAULT_PAGE_SIZE] * pageCount 条,
     * 滚到顶部时自动扩展下一页(纯本地内存分页,不查 DB);全部展开后再上滑才触发 DB loadMoreHistory。
     * 关闭时维持现有行为(直接渲染全部 state.messages)。
     */
    val performanceMode: Boolean = false,
)

/**
 * v0.32: 实验性功能开关。
 *
 * 默认全部关闭,用户主动开启后才会启用对应实验性功能。
 */
@kotlinx.serialization.Serializable
data class ExperimentsConfig(
    /** 实验性:启用 MOOD 块强制输出(即使模型不支持也会尝试要求)。 */
    val forceMoodBlock: Boolean = false,
    /** 实验性:调试模式,显示更多内部状态(MOOD/工具调用/Token 统计)。 */
    val debugMode: Boolean = false,
    /** 实验性:启用多 agent 协作(一个任务派给多个助手)。 */
    /** 实验性:启用自我反思(回复后自动检查质量)。 */
    val selfReflection: Boolean = false,
    /** v1.55: 长记忆压缩默认启用(超长对话自动摘要,降低 compileThreshold 到 3.0 让 fact 更激进编译)。 */
    val longMemoryCompression: Boolean = true,
)

/**
 * v0.32: 分享模板配置。
 *
 * 控制导出/分享对话时的格式和内容。
 */
@kotlinx.serialization.Serializable
data class ShareTemplateConfig(
    /** 分享时是否包含时间戳。 */
    val includeTimestamp: Boolean = true,
    /** 分享时是否包含模型名。 */
    val includeModelName: Boolean = false,
    /** 分享时是否包含 token 数。 */
    val includeTokenCount: Boolean = false,
    /** 分享时是否包含 MOOD 块。 */
    val includeMoodBlock: Boolean = false,
    /** 分享时是否包含思考过程。 */
    val includeReasoning: Boolean = false,
    /** 分享格式:markdown / plain_text / html。 */
    val format: String = "markdown",
    /** 自定义标题(空则用会话标题)。 */
    val customTitle: String = "",
)

/**
 * v0.32: 媒体配置。
 *
 * 控制语音录制和音频输出的参数。
 */
@kotlinx.serialization.Serializable
data class MediaConfig(
    /** 语音录制采样率(Hz)。 */
    val recordingSampleRate: Int = 16000,
    /** 语音录制比特率。 */
    val recordingBitRate: Int = 128000,
    /** 是否启用 TTS 语音播报。 */
    val ttsEnabled: Boolean = false,
    /** TTS 播报语速(0.5-2.0,1.0 为正常)。 */
    val ttsSpeechRate: Float = 1.0f,
    /** TTS 播报音高(0.5-2.0,1.0 为正常)。 */
    val ttsPitch: Float = 1.0f,
    /** TTS 播报语言(null 表示跟随系统)。 */
    val ttsLanguage: String? = null,
    /** 音频输出方式:speaker / earpiece / bluetooth。 */
    val audioOutput: String = "speaker",
    /** v1.97: TTS 引擎类型:"system"(系统 TTS) / "openai"(OpenAI TTS) / "minimax"(MiniMax TTS) / "edge"(Edge TTS)。 */
    val ttsEngine: String = "system",
    /** v1.97: 云端 TTS API Key(加密存储)。 */
    val ttsApiKey: String = "",
    /** v1.97: 云端 TTS 模型名(如 openai: gpt-4o-mini-tts, minimax: speech-2.6-turbo)。 */
    val ttsModel: String = "",
    /** v1.97: 云端 TTS 音色(如 openai: alloy/echo/fable/onyx/nova/shimmer)。 */
    val ttsVoice: String = "",
    /** v1.97: 云端 TTS 自定义 endpoint(留空用默认)。 */
    val ttsEndpoint: String = "",
    /** 系统 TTS 声音名称(空字符串表示使用默认声音)。 */
    val ttsVoiceName: String = "",
    /** v1.99(4.8): ElevenLabs stability(0-1,默认 0.5)。 */
    val ttsStability: Float = 0.5f,
    /** v1.99(4.8): ElevenLabs similarity_boost(0-1,默认 0.75)。 */
    val ttsSimilarityBoost: Float = 0.75f,
    /** v1.99(4.8): MiniMax 情感(happy/sad/angry/neutral 等,空表示默认)。 */
    val ttsEmotion: String = "",
    /** v1.99(4.8): 云端 TTS 合成语速倍率(0.25-4.0,1.0 为正常)。 */
    val ttsCloudSpeed: Float = 1.0f,
    /** v1.99(4.8): 云端 TTS 音频格式(mp3/opus/aac/flac/wav,默认 mp3)。 */
    val ttsResponseFormat: String = "mp3",
)


/**
 * 主动消息配置(虚拟陪伴助手像真人一样主动给用户发消息)。
 *
 * - [enabled] 总开关,关闭时调度器跳过
 * - [intervalMinutes] v1.30: 触发间隔(分钟),用户可在设置页用滑动条自定义(15 分钟 ~ 24 小时,无极调节)
 * - [lastTriggeredAt] 上次触发时间戳,调度器据此判断是否到期
 * - [randomOffsetMinutes] v1.30: 随机偏移量(分钟),实际间隔 = intervalMinutes ± randomOffsetMinutes,
 *   让发送时间更自然,避免固定间隔的机械感。无极调节
 * - [agentId] v1.27: 指定发送主动消息的 Agent 助手 id,空字符串表示用默认助手
 * - [allowedHourStart] v1.95: 允许发送时段开始小时(0-23,24小时制),不在此时段跳过发送避免夜间打扰
 * - [allowedHourEnd] v1.95: 允许发送时段结束小时(0-23,24小时制),支持跨夜(如 22-8 表示22点到次日8点)
 * - [agentOnly] v1.95: 仅Agent会话可发主动消息(true=只发Agent Tab会话,false=不限制)
 * - [maxDailyMessages] v2.0 5.9: 每日主动消息上限(默认 3),可在设置页调整
 * - [temperature] v2.0 5.9: LLM 调用温度(默认 0.8),决策阶段用 temperature×0.5,生成阶段用本字段
 */
@kotlinx.serialization.Serializable
data class ProactiveMessageConfig(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 240,
    val lastTriggeredAt: Long = 0,
    /** B8-01: 下次主动消息触发时间戳(进程重启后从持久化配置恢复,0=尚未排期)。 */
    val nextTriggerAt: Long = 0,
    /** v1.30: 随机偏移量(分钟),实际间隔 = intervalMinutes ± randomOffsetMinutes。 */
    val randomOffsetMinutes: Int = 60,
    /** v1.27: 指定发送主动消息的 Agent 助手 id,空字符串表示用默认助手。 */
    val agentId: String = "",
    /** v1.95: 允许发送时段开始小时(0-23,24小时制),默认8点,不在此时段跳过发送避免夜间打扰。 */
    val allowedHourStart: Int = 8,
    /** v1.95: 允许发送时段结束小时(0-23,24小时制),默认22点,支持跨夜(如 22-8 表示22点到次日8点)。 */
    val allowedHourEnd: Int = 22,
    /** v1.95: 仅Agent会话可发主动消息(true=只发Agent Tab会话,false=不限制)。 */
    val agentOnly: Boolean = true,
    /**
     * v2.0 5.9: 每日主动消息上限(默认 3)。
     *
     * 替代 ProactiveScoreEngine 中硬编码的 MAX_DAILY_MESSAGES,可在设置页调整。
     * ScoreEngine.shouldSend 通过 ScoreContext.todaySentCount 与本字段比对。
     */
    val maxDailyMessages: Int = 3,
    /**
     * v2.0 5.9: LLM 调用温度(默认 0.8),用于决策与生成两阶段。
     *
     * 决策阶段实际使用 temperature × 0.5(决策需要确定性);生成阶段使用本字段。
     */
    val temperature: Float = 0.8f,
    /**
     * v1.0.72: 主动消息发送概率(0-100,默认 100)。
     *
     * 决策阶段 shouldSend=true 后,再按此概率决定是否实际发送:
     *  - 100 = 每次都发(决策通过即发)
     *  - 50 = 一半概率发
     *  - 0 = 永不发送(等同于关闭,但保留决策日志)
     * 测试发送不受此限制(forceSend 直发)。
     */
    val sendProbability: Int = 100,
    /**
     * A-08: 最近一次 LLM 调用失败时间戳(0=无失败)。
     *
     * 决策/生成阶段 LLM 失败时写入;此后至少间隔一个 baseInterval 才允许再次尝试,
     * 防止断网/服务故障时 guaranteedSend 每分钟热循环重试烧配额。
     * 成功发送后清零。
     */
    val lastFailedAt: Long = 0,
    /**
     * 审查修复 (2.0 B-12): 连续失败次数(0=无失败)。
     *
     * 决策/生成失败时递增,成功发送后清零;退避间隔随计数指数增长
     * (1x/2x/4x/8x/16x baseInterval),杜绝"固定 baseInterval 无限重试"的烧配额循环。
     */
    val consecutiveFailures: Int = 0,
)

/**
 * v1.25: 协作团队。
 *
 * 用户把多个 Assistant 编成一个团队,主助手可通过 delegate_agent 把任务派给团队成员。
 */
@kotlinx.serialization.Serializable
data class AgentTeam(
    /** 团队唯一 id。 */
    val id: String = java.util.UUID.randomUUID().toString(),
    /** 团队名称,如「写作小组」「调研小组」。 */
    val name: String = "",
    /** 团队描述/用途提示。 */
    val description: String = "",
    /** 团队成员 assistantId 列表,顺序即推荐委托顺序。 */
    val memberIds: List<String> = emptyList(),
    /** v1.200: 团队工作流定义。为空时按 memberIds 顺序串行执行。 */
    val workflow: io.zer0.muse.tools.DelegationContract.TeamWorkflow? = null,
    /** 创建时间戳。 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 最近更新时间戳。 */
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * v1.25: 多 Agent 协作全局配置。
 */
@kotlinx.serialization.Serializable
data class MultiAgentConfig(
    /** 总开关,开启后 SystemPromptAssembler 才会注入多 Agent 协作提示。 */
    val enabled: Boolean = true,
    /** v1.200: 自动路由开关。开启后，当用户消息与当前助手匹配度低时，自动委派给更合适的 Agent/团队。 */
    val autoRoutingEnabled: Boolean = false,
    /**
     * v2.x: LLM 语义路由开关(默认关)。
     *
     * 开启后,[io.zer0.muse.tools.AgentRouter.routeWithLlm] 会用 LLM 判断最佳路由目标,
     * 替代关键词规则路由。LLM 调用失败或返回无效 ID 时自动降级到规则路由。
     * 关闭时(默认)走规则路由,行为与 v1.x 一致。
     *
     * 与 [llmReviewEnabled] 不同,本字段随 MultiAgentConfig JSON 序列化,
     * 因为没有独立的 save 方法,不存在双写竞态。
     */
    val llmRoutingEnabled: Boolean = false,
    /** 用户创建的协作团队列表。 */
    val teams: List<AgentTeam> = emptyList(),
    /** 默认团队 id,未指定时主助手自行选择。 */
    val defaultTeamId: String? = null,
    /**
     * v1.201: 人机协作暂停策略(随 MultiAgentConfig 一起持久化到 DataStore)。
     *
     * 注意:[DelegationPauseManager.PausePolicy] 必须为 @Serializable data class
     * 才能随 MultiAgentConfig 序列化。当前 [DelegationPauseManager.PausePolicy] 未带
     * @Serializable 注解,需在 DelegationPauseManager.kt 中补充(详见修改建议清单)。
     */
    val pausePolicy: io.zer0.muse.tools.DelegationPauseManager.PausePolicy = io.zer0.muse.tools.DelegationPauseManager.PausePolicy(),
    /**
     * v1.201: LLM 综合评审使用的模型 id(全局,跨团队共享)。
     * 空表示用 active provider 的默认模型。
     * 持久化到独立 DataStore key: multi_agent_review_model(@Transient:不随 JSON 序列化,
     * 由独立 key 读写,避免 updateMultiAgentConfig 与独立 save 方法双写竞态)。
     */
    @kotlinx.serialization.Transient
    val reviewModelId: String? = null,
    /**
     * v1.201: 全局 LLM 综合评审开关(默认关)。
     * 开启后,团队工作流选 LLM_REVIEW 聚合策略时才会真正调用 LLM 评审;
     * 关闭时 LLM_REVIEW 自动降级为 EXPERT_REVIEW。
     * 持久化到独立 DataStore key: multi_agent_llm_review_enabled。
     */
    @kotlinx.serialization.Transient
    val llmReviewEnabled: Boolean = false,
)
