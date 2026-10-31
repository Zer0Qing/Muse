package io.zer0.muse.web

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Host Mode WebSocket wire protocol.
 *
 * The browser is a presentation client. It sends intent commands and receives
 * snapshots/events produced by the Android runtime; it never supplies a
 * Provider key or a second copy of the chat business logic.
 */
const val CURRENT_PROTOCOL_VERSION: Int = 1

@Serializable
data class HostCommand(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val type: String,
    val requestId: String? = null,
    val sessionId: String? = null,
    val text: String? = null,
    val title: String? = null,
    val archived: Boolean? = null,
    val messageId: String? = null,
    val generationId: String? = null,
    /**
     * 分页游标。
     *
     * 两种口径共用同一个字段:`state.sync` 传事件序号(数字),`memory.list` 与
     * `knowledge.documents` 传上一页最后一条的 id(字符串)。为了不让两种口径互相
     * 挤占字段名,统一按文本接收,数字与字符串都认得,需要数值的分支自己转。
     */
    @Serializable(with = HostCursorValueSerializer::class)
    val cursor: String? = null,
    val sinceEventSeq: Long? = null,
    /** F-38: history.page 分页参数(offset=跳过最近多少条,limit=每页条数)。 */
    val offset: Int? = null,
    /** 每页条数:`history.page` 与 `memory.list` / `memory.search` 共用。 */
    val limit: Int? = null,
    /** F-38: history.search 关键词;`memory.search` 也用它承载查询串。 */
    val query: String? = null,
    /** message.delete 的目标消息 id 列表（一次可删多条）。 */
    val messageIds: List<String> = emptyList(),
    /** message.favorite 的目标状态（true = 收藏，false = 取消收藏）。 */
    val favored: Boolean? = null,
    /**
     * chat.send 携带的引用目标。
     *
     * 与 `message.quote` 的关系：本字段是「发出去时带着引用」，由 Host 把引用关系挂到新消息上；
     * `message.quote` 是「先看一下被引用的正文」，回内容不落库。两者可同时存在，
     * 网页端选择发送时带上本字段，就不再需要额外一次往返。
     */
    val quotedMessageId: String? = null,
    /** F-37: tool.approval.resolve answered 时用户填写的工具参数覆盖映射(键 → 值)。 */
    val argOverrides: Map<String, String> = emptyMap(),
    /** settings.update 的目标设置键,必须命中服务端白名单。 */
    val key: String? = null,
    /**
     * 助手 id。
     *
     * 三种用法：`session.new` 指定新会话挂在哪个助手(不传则用设置里的默认助手)；
     * `assistants.detail` 指定要读的助手(必填)；`memory.list` / `memory.search` 指定
     * 记忆作用域(不传 = 主助手的主记忆池)。
     */
    val assistantId: String? = null,
    /** settings.update 的目标值,按白名单声明的类型解析(布尔只接受 "true"/"false")。 */
    val value: String? = null,
    /** `knowledge.documents` 的目标知识库 id(必填)。 */
    val kbId: String? = null,
)

/**
 * 把 `cursor` 的两种口径(数字序号 / 字符串 id)收进同一个字段。
 *
 * 只能按字符串解:`state.sync` 发的是数字,`memory.list` / `knowledge.documents` 发的是 id 文本,
 * 声明成数字就收不下知识库文档那种非数字 id,声明成字符串又读不了数字。这里读取时两种都接受,
 * 写回时统一成字符串。
 */
internal object HostCursorValueSerializer : KSerializer<String?> {
    private val delegate: KSerializer<String?> = String.serializer().nullable

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: String?): Unit = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.content
            else -> null
        }
    }
}

@Serializable
data class HostEvent(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val type: String,
    val requestId: String? = null,
    val connectionId: String? = null,
    val eventSeq: Long = 0L,
    val cursor: Long = 0L,
    val sessionId: String? = null,
    val turnId: String? = null,
    val generationId: String? = null,
    val messageId: String? = null,
    val role: String? = null,
    val title: String? = null,
    val content: String? = null,
    val reasoning: String? = null,
    val delta: String? = null,
    val error: String? = null,
    val isStreaming: Boolean? = null,
    val sessions: List<HostSession> = emptyList(),
    val messages: List<HostMessage> = emptyList(),
    val pendingApprovals: List<HostApproval> = emptyList(),
    val capabilityFlags: List<String> = emptyList(),
    /** message.delete 回执：真正删掉的条数。 */
    val deletedCount: Int? = null,
    /** message.delete 回执：因不存在/不属于当前会话而跳过的 id。 */
    val skippedMessageIds: List<String> = emptyList(),
    /** settings.snapshot 载荷。 */
    val settings: HostSettingsSnapshot? = null,
    /** assistants.list 载荷。 */
    val assistants: List<HostAssistant> = emptyList(),
    /** providers.list 载荷。 */
    val providers: List<HostProvider> = emptyList(),
    /** providers.list 里当前选中的模型 id。 */
    val selectedModelId: String? = null,
    /** sessions.detail 载荷;会话不存在时为空并带 error。 */
    val sessionDetail: HostSessionDetail? = null,
    /** assistants.detail 载荷;助手不存在时为空并带 error。 */
    val assistantDetail: HostAssistantDetail? = null,
    /** memory.list 载荷(正文已截断、已按页裁剪)。 */
    val memory: List<HostMemoryEntry> = emptyList(),
    /** memory.search 载荷(条目 + 命中片段)。 */
    val memoryHits: List<HostMemoryHit> = emptyList(),
    /** memory.list 之后是否还有下一页。 */
    val hasMore: Boolean? = null,
    /** knowledge.list 载荷:知识库一览。 */
    val knowledgeBases: List<HostKnowledgeBase> = emptyList(),
    /** knowledge.documents 回显的目标知识库 id。 */
    val kbId: String? = null,
    /** knowledge.documents 载荷:某知识库下的一页文档。 */
    val documents: List<HostKnowledgeDocument> = emptyList(),
    /** tasks.list 载荷:定时任务一览。 */
    val tasks: List<HostScheduledTask> = emptyList(),
    /** system.status 载荷:应用与设备的只读状态快照。 */
    val systemStatus: HostSystemStatus? = null,
)

/**
 * 应用与设备的状态快照(数据与隐私页 / 平台专有页的只读数据源)。
 *
 * 每个分组都是「当下一次测量」的结果,不做跨请求缓存:体积与电量本来就随时间变,
 * 缓存只会让网页端显示一个说不清是哪一刻的数。
 */
@Serializable
data class HostSystemStatus(
    /** 版本名(如 `2.6.18`)。 */
    val appVersion: String = "",
    /** 版本号(整数递增,发布渠道用来判断谁更新)。 */
    val appVersionCode: Long = 0,
    /** 本次进程已运行的时长(毫秒)。 */
    val uptimeMs: Long = 0,
    val storage: HostStorageBytes = HostStorageBytes(),
    val backup: HostBackupStatus = HostBackupStatus(),
    val capabilities: HostDeviceCapabilities = HostDeviceCapabilities(),
    /** 电量;取不到时整块为 null(不是 `level=0`)。 */
    val battery: HostBatteryStatus? = null,
)

/**
 * 各目录的占用字节数。
 *
 * 口径:五个目录各自递归求和,[totalAppBytes] 是这五项之和 —— 不是整块沙盒的容量,
 * 未列出的子目录(如日志、崩溃转储)不计入,见 [HostStorageUsage] 的说明。
 */
@Serializable
data class HostStorageBytes(
    /** 数据库目录(`databases/`,含 `-wal` / `-shm`)。 */
    val databaseBytes: Long = 0,
    /** 消息图片目录(`files/muse_images`)。 */
    val mediaBytes: Long = 0,
    /** 语音存档目录(`files/voice_messages`)。 */
    val voiceBytes: Long = 0,
    /** 工作区目录(`files/workspace`)。 */
    val workspaceBytes: Long = 0,
    /** 缓存目录(`cache/`)。 */
    val cacheBytes: Long = 0,
    /** 以上五项之和。 */
    val totalAppBytes: Long = 0,
)

/**
 * 备份状态。
 *
 * 两个「是否开启」都取 App 自己的开关口径:[autoBackupEnabled] 是后台调度总控
 * (本地每日备份挂在它下面),[cloudBackupEnabled] 要求云备份已配置且开了自动同步 ——
 * 只配置不自动同步时云备份不会自己跑,显示成「已开启」会误导。
 */
@Serializable
data class HostBackupStatus(
    /** 最近一次成功备份的时间戳;从未备份过为 null。 */
    val lastBackupAt: Long? = null,
    /** 最近一次成功备份的包体大小;从未备份过为 null。 */
    val lastBackupBytes: Long? = null,
    val autoBackupEnabled: Boolean = false,
    val cloudBackupEnabled: Boolean = false,
)

/**
 * 六项平台能力位。
 *
 * 全部来自 App 内部同一份自动化权限状态(见 [io.zer0.muse.automation.core.AutomationManager]),
 * 不在网关里另做探测;拿不到状态时一律 false,因此六个字段永远是布尔值而不是 null。
 */
@Serializable
data class HostDeviceCapabilities(
    /** 无障碍服务是否已连接。 */
    val accessibility: Boolean = false,
    /** Shell 通道(当前实现由 Shizuku 授权决定)。 */
    val shell: Boolean = false,
    /** Root 通道。 */
    val root: Boolean = false,
    /** Shizuku 是否处于 READY。 */
    val shizuku: Boolean = false,
    /** 虚拟屏是否可用 —— 权限门与 Shell/Root 同源,两者之一可用即可建屏。 */
    val virtualDisplay: Boolean = false,
    /** 通知使用权是否已授予(或监听服务已连接)。 */
    val notificationListener: Boolean = false,
)

/** 电量;两个字段都取不到时整块为 null。 */
@Serializable
data class HostBatteryStatus(
    /** 剩余电量百分比 0–100;读不到为 null。 */
    val level: Int? = null,
    /** 是否在充电;系统没给出明确状态时为 null。 */
    val charging: Boolean? = null,
)

/**
 * 一个知识库的概览。
 *
 * [documentCount] / [chunkCount] 是实时聚合值,不读 `knowledge_bases.doc_count` 那一列 ——
 * 该列只在迁移时写过一次,导入/删除路径不会跟。
 */
@Serializable
data class HostKnowledgeBase(
    val id: String,
    val name: String,
    /** 该库下用户可见的文档数(不含内部开发文档)。 */
    val documentCount: Int = 0,
    /** 该库里真实存在的分块数(索引规模)。 */
    val chunkCount: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 当前实际生效的 embedding 后端名;本次进程还没解析过后端时为 null。 */
    val embeddingBackend: String? = null,
)

/**
 * 知识库里的一个文档。
 *
 * 正文不下发,只给 [sizeBytes]:列表页只需要「多大、分了几块、索到哪一步了」。
 */
@Serializable
data class HostKnowledgeDocument(
    val id: String,
    val title: String,
    /** 已写入索引的分块数;0 表示还没索引。 */
    val chunkCount: Int = 0,
    /** 正文的 UTF-8 字节数。 */
    val sizeBytes: Long = 0,
    val createdAt: Long = 0,
    /** 索引状态,取值见 [HostDocumentStatus]。 */
    val status: String = HostDocumentStatus.PENDING,
)

/** [HostKnowledgeDocument.status] 的取值。 */
object HostDocumentStatus {
    /** 已索引,可被检索召回。 */
    const val INDEXED = "indexed"

    /** 还没索引过(导入未完成、或分块数为 0)。 */
    const val PENDING = "pending"

    /** 曾经建过索引,但重建中途失败、旧分块已清掉,需要重新索引。 */
    const val INDEX_BROKEN = "index_broken"
}

/**
 * 一条定时任务。
 *
 * [cron] 是库里存的原始表达式,不做解析也不做归一(interval 型任务为空串);
 * [lastResult] 是最近一次执行的结局状态(`success` / `failed` / `skipped` 等),从未执行过为 null。
 */
@Serializable
data class HostScheduledTask(
    val id: String,
    val name: String,
    val cron: String = "",
    val enabled: Boolean = true,
    /** 下次执行时间;未启用或没有下次执行时为 null。 */
    val nextRunAt: Long? = null,
    /** 上次执行时间;从未执行过为 null。 */
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
)

@Serializable
data class HostSettingsSnapshot(
    val groups: List<HostSettingsGroup> = emptyList(),
    /** 常用开关的当前值,键同 [HostSettingToggle.key]。 */
    val toggles: List<HostSettingToggle> = emptyList(),
    val display: HostSettingsDisplay = HostSettingsDisplay(),
    /** settings.update 接受的白名单键;不在此列的键一律拒绝。 */
    val writableKeys: List<String> = emptyList(),
    /**
     * 子页表单用的结构值。
     *
     * 每项自带类型、当前值与枚举候选（含已本地化的显示名），网页端照着渲染就行，
     * 不需要自己维护类型表或中文文案。写入白名单与这里同源：可写项集合就是
     * [writableKeys]，只读项（如当前音色名、是否已配置模型）带 writable=false。
     */
    val values: List<HostSettingValue> = emptyList(),
)

/**
 * 设置项的当前值。
 *
 * [type] 取 `boolean` / `number` / `enum` / `text`；[value] 统一用字符串承载：布尔写
 * `true`/`false`，数字写十进制文本，枚举写候选里的 value，文本原样。这样网页端
 * 提交时只需回传同一个字符串，不必关心各类型的序列化。
 */
@Serializable
data class HostSettingValue(
    val key: String,
    val type: String,
    val value: String,
    /** 枚举候选（含本地化显示名）；非 enum 为空。 */
    val options: List<HostSettingOption> = emptyList(),
    /** 是否可写；false 的项只用于展示，往它写会被拒绝。 */
    val writable: Boolean = true,
    /** number 的下界/上界/步进；非 number 为 null。 */
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
)

/** 枚举候选：稳定 value + 已按应用语言本地化的显示名。 */
@Serializable
data class HostSettingOption(
    val value: String,
    val label: String = "",
)

@Serializable
data class HostSettingsGroup(
    /** 分组标题的字符串资源名(稳定标识,不直接显示)。 */
    val key: String,
    /** 已按应用语言本地化的分组标题。 */
    val groupTitle: String = "",
    val entries: List<HostSettingsEntry> = emptyList(),
)

@Serializable
data class HostSettingsEntry(
    val id: String,
    /** 标题的字符串资源名(稳定标识:导航与测试断言用,不直接显示)。 */
    val titleKey: String,
    /** 说明的字符串资源名;说明由运行时拼出的条目(如代理状态)为空。 */
    val descKey: String? = null,
    /** 已按应用语言本地化的标题。 */
    val title: String = "",
    /** 已按应用语言本地化的说明;无说明的条目为空。 */
    val desc: String? = null,
    val groupKey: String,
    val iconKey: String,
    /** link / switch / action。 */
    val kind: String,
    /** 入口在 App 端是否可用(编译期闸门关闭的入口为 false)。 */
    val enabled: Boolean = true,
    /** kind=switch 时对应的设置键,可直接用于 settings.update。 */
    val toggleKey: String? = null,
    /** kind=switch 时的当前值;非 switch 条目为空。 */
    val checked: Boolean? = null,
)

@Serializable
data class HostSettingToggle(
    val key: String,
    val enabled: Boolean,
)

@Serializable
data class HostSettingsDisplay(
    val language: String = "",
    val themeMode: String = "",
    val fontSizeScale: String = "",
    val themeId: String = "",
)

@Serializable
data class HostAssistant(
    val id: String,
    val name: String,
    val avatarUri: String? = null,
    val isDefault: Boolean = false,
)

/**
 * 单个助手的可展示详情。
 *
 * 只带网页端要画出来的东西:模型参数、人设摘要、提示词长度、工具数量。
 * 完整提示词正文不下发 —— 网页端只需要显示「已配置 N 字」,而提示词是助手的"源代码",
 * 摊进浏览器既撑大回执也没有展示位。
 */
@Serializable
data class HostAssistantDetail(
    val id: String,
    val name: String,
    val avatarUri: String? = null,
    /** 一句话简介(助手卡片副标题那段)。 */
    val description: String = "",
    val modelId: String? = null,
    val providerId: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    /** 人设摘要(已按上限截断);没有单独配置人设时为空串。 */
    val personaSummary: String = "",
    /** [personaSummary] 是否被截断,供网页端决定要不要显示"查看完整人设请到 App"。 */
    val personaTruncated: Boolean = false,
    /** 自定义提示词的字数(字符数),正文不下发。 */
    val systemPromptLength: Int = 0,
    /** 该助手勾选的工具数量。 */
    val toolCount: Int = 0,
    /** 注入上下文的消息条数上限。 */
    val contextMessageSize: Int = 20,
    /** 思考深度档位(枚举名,如 AUTO / OFF)。 */
    val reasoningLevel: String = "AUTO",
    val enabled: Boolean = true,
)

/**
 * 一条记忆条目。
 *
 * [content] 已截断:列表页只画两三行,整段下发会让一页 30 条的回执膨胀到几百 KB。
 * 需要看命中位置就用 `memory.search` 的 [HostMemoryHit.snippet]。
 */
@Serializable
data class HostMemoryEntry(
    /** 条目 id(十进制字符串,与 `memory.list` 的 cursor 同一套编号)。 */
    val id: String,
    val content: String,
    /** 入库时间 ISO 8601。 */
    val createdAt: String,
    /** 来源类别,取值见 [HostMemorySourceKind]。 */
    val source: String = HostMemorySourceKind.AUTO,
    /** 是否被用户置顶。 */
    val pinned: Boolean = false,
)

/** `memory.search` 的一条命中:[entry] 与 [memory.list] 同结构,[snippet] 是命中位置附近的片段。 */
@Serializable
data class HostMemoryHit(
    val entry: HostMemoryEntry,
    val snippet: String,
)

/** [HostMemoryEntry.source] 的取值。 */
object HostMemorySourceKind {
    /** 从对话里抽取出来的条目(带来源会话)。 */
    const val CONVERSATION = "conversation"

    /** 用户手动添加或明确陈述的条目。 */
    const val MANUAL = "manual"

    /** 系统自动整理/推断出来的条目。 */
    const val AUTO = "auto"
}

@Serializable
data class HostProvider(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val models: List<HostProviderModel> = emptyList(),
)

@Serializable
data class HostProviderModel(
    val id: String,
    val name: String,
)

@Serializable
data class HostSessionDetail(
    val id: String,
    val title: String,
    val assistantId: String,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
)

@Serializable
data class HostApproval(
    val toolCallId: String,
    val toolName: String,
    val argumentsPreview: String,
)

@Serializable
data class HostSession(
    val id: String,
    val title: String,
    val assistantId: String,
    val updatedAt: Long,
    val archived: Boolean,
    val pinned: Boolean,
)

@Serializable
data class HostMessage(
    val id: String,
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val modelId: String? = null,
    val createdAt: Long,
    /** 可访问的图片地址: `/api/media/<token>` 相对路径,或本来就是 `http(s)` 的原地址。 */
    val imageUrls: List<String> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val citationUrls: List<String> = emptyList(),
    /** 语音消息的可访问地址,与 [imageUrls] 同一规则;非语音消息为空。 */
    val voiceUrl: String? = null,
    /** 语音时长(毫秒)。null = 非语音消息或时长未知(旧数据、探测失败)。 */
    val voiceDurationMs: Long? = null,
    /**
     * 本条消息携带的工具调用。
     *
     * 一条助手消息可能一次提出多个调用,所以是数组;不携带工具调用的消息为空数组。
     * [toolName] 保留原语义(单值、只给工具名),新字段不取代它。
     */
    val toolCalls: List<HostToolCall> = emptyList(),
    /** 消息级状态,取值为 [HostMessageStatus] 里的四个常量之一。 */
    val status: String = HostMessageStatus.SENT,
    /** 失败原因文案,仅 [status] 为 [HostMessageStatus.FAILED] 时非空。 */
    val errorText: String? = null,
    /**
     * 引用回复里的被引用正文。
     *
     * 取自消息本身的引用关系（用户消息才可能有），与 App 消息气泡上那块引用卡是同一份内容；
     * 无引用时为空。正文同时仍以 `> ` 前缀的形式留在 [content] 里（App 的持久化格式）。
     */
    val quotedContent: String? = null,
)

/**
 * 一次工具调用的完整信息。
 *
 * App 端工具卡片渲染折叠卡片用的就是这些字段,网页端不重实现业务逻辑也能把卡片原样摆出来。
 * 参数与结果都只下发预览(见 [argumentsPreview]),完整结果不进快照 ——
 * 一条读文件工具的结果可以是几十 KB,而快照每收到一个流式增量就重发一次。
 */
@Serializable
data class HostToolCall(
    /** 回写工具结果时的 id(对应 `tool_call_id`);无提供方 id 时由服务端生成。 */
    val callId: String,
    val name: String,
    /** 参数 JSON 的截断预览。 */
    val argumentsPreview: String,
    /** 执行结果的截断预览;null = 还没执行或结果未下发(待审批、仅请求列表的调用)。 */
    val resultPreview: String? = null,
    /** 是否执行成功;null = 未知(仅请求列表的调用,结果不在本条消息上)。 */
    val success: Boolean? = null,
    /** 本地执行耗时(毫秒),与 App 的 ToolCallInfo.durationMs 同一口径;null = 未统计。 */
    val durationMs: Long? = null,
)

/** [HostMessage.status] 的取值。 */
object HostMessageStatus {
    /** 已写入本地消息列表,等待入队发出。 */
    const val SENDING = "sending"

    /** 助手回复正在流式输出。 */
    const val STREAMING = "streaming"

    /** 已完成。 */
    const val SENT = "sent"

    /** 本轮生成以失败结束,[HostMessage.errorText] 带原因。 */
    const val FAILED = "failed"
}
