package io.zer0.muse.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecMerger
import io.zer0.ai.core.ProviderType
import io.zer0.ai.ProviderConfigStore
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.backup.CloudBackupConfig
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.data.preset.SiliconFlowFreeModels
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebServerConfig
import io.zer0.muse.data.prompttemplate.PromptTemplate
import io.zer0.muse.ui.theme.CustomTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import io.zer0.muse.R
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.atomic.AtomicBoolean

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "muse_settings")

/**
 * 应用全局配置仓库 — 基于 Jetpack DataStore (Preferences)。
 *
 * 职责:
 *  - Provider 列表 / 活跃 Provider / 模型 / API Key 的持久化
 *  - Assistant 配置 / 用户画像的存取(具体实体走 Room,这里只存激活态等)
 *  - 主题模式 / 字号 / keepAwake / autoLaunch 等外观与行为开关
 *  - 主动消息配置(开关 + 间隔 + 上次触发时间)
 *  - WebServer 配置 / Web 搜索配置 / ASR 配置 / 媒体配置 / 代理配置
 *  - 账户状态(登录态 / 游客模式)
 *  - 首次引导是否已展示
 *  - MCP OAuth token 存储(按 serverId)
 *
 * 同时实现 [ProviderConfigStore] 接口,供 ai 模块的 ChatService/ImageService 注入。
 *
 * 缓存策略:hot path 字段用 AtomicBoolean 缓存(如 memoryEnabled),避免每次都走 DataStore。
 */
class SettingsRepository(
    private val appContext: Context,
    /** P2-4: 审计日志记录器,用于记录关键用户操作(如删除 Provider)。 */
    private val auditLogger: AuditLogger,
) : ProviderConfigStore {

    private val store get() = appContext.settingsDataStore
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryEnabledCache = AtomicBoolean(true)
    // M-SR5: 防止 migrateLegacyProviderIfNeeded 在并发首调时重复执行(读旧 JSON + addProvider + remove 之间存在竞态)
    private val migrationDone = AtomicBoolean(false)
    /** v1.0.51: 防止 memory backfill 迁移在并发首调时重复执行(读标志位 + 跑 backfill + 写标志位之间存在竞态)。 */
    private val memoryBackfillMigrationDone = AtomicBoolean(false)

    /**
     * v1.0.7: 内置供应商规格声明(对齐 openhanako BUILTIN_PLUGINS)。
     * lazy 初始化,供 [providersFlow] 合并 spec 默认模型 + 用户 overlay。
     */
    private val presetProviders by lazy { PresetProviders(appContext) }

    /** v1.131: 语言快速同步缓存(与 DataStore 双写,供 [getLanguageSync] 同步读取)。 */
    private val languageSyncCache = appContext.getSharedPreferences("muse_language_cache", Context.MODE_PRIVATE)

    init {
        // v1.131: 把 DataStore 中的语言设置一次性同步到 SP 快速缓存。
        // 历史用户升级到 v1.131+ 时 SP 为空,需从 DataStore 拷贝过来;
        // 异步执行,不阻塞 Application.onCreate / Activity.attachBaseContext。
        // v1.138: 修复 NPE — init 块执行时 languageFlow 属性尚未初始化(声明在 line 227),
        // 直接内联 store.data.map 避免引用未初始化的 languageFlow。
        cacheScope.launch {
            try {
                val dsLang = store.data.map { prefs -> prefs[KEY_LANGUAGE] ?: "system" }.first()
                if (languageSyncCache.getString(KEY_LANGUAGE_SP, null) == null) {
                    languageSyncCache.edit().putString(KEY_LANGUAGE_SP, dsLang).apply()
                }
            } catch (e: Exception) {
                Logger.w("SettingsRepository", "language SP cache migration failed: ${e.message}", e)
            }
        }
    }

    /**
     * v0.32: 当前 MemoryConfig 的内存缓存(供 MemoryTicker 的 getConfig 闭包同步读取)。
     *
     * MemoryConfig 是用户在设置页可实时调整的(写入 DataStore),但调度器每次 tick /
     * 每轮对话都要读它,不适合每次都跑 suspend 的 [getMemoryConfig]。这里仿照
     * [memoryEnabledCache] 的模式:后台协程订阅 [memoryConfigFlow],把最新值落到
     * 这个 @Volatile 字段;MemoryTicker 用 `getConfig = { settings.memoryConfigCache }`
     * 即可零阻塞拿到当前配置。
     */
    @Volatile
    var memoryConfigCache: io.zer0.memory.ticker.MemoryConfig = io.zer0.memory.ticker.MemoryConfig()
        private set

    /**
     * v0.32: 当前 ExperimentsConfig 的内存缓存(供 SystemPromptAssembler / ChatViewModel 等
     * 同步读取实验性开关)。
     *
     * 仿照 [memoryConfigCache] 模式:后台协程订阅 [experimentsFlow],把最新值落到这个
     * @Volatile 字段;调用方(`getExperiments = { settings.experimentsCache }`)零阻塞读取。
     *
     * 副作用:当 [ExperimentsConfig.longMemoryCompression] = true 时,会让 [memoryConfigCache]
     * 的 compileThreshold 降到 3.0(更激进编译)。两个缓存通过 combine 流联动更新,
     * 保证用户切换开关后立即生效。
     */
    @Volatile
    var experimentsCache: ExperimentsConfig = ExperimentsConfig()
        private set

    /**
     * v1.25: 当前 MultiAgentConfig 的内存缓存,供 SystemPromptAssembler 零阻塞读取。
     */
    @Volatile
    var multiAgentConfigCache: MultiAgentConfig = MultiAgentConfig()
        private set

    /**
     * v1.98: 当前经验库开关的内存缓存,供 SystemPromptAssembler 零阻塞读取。
     * 仿照 [experimentsCache] 模式:后台协程订阅 [experienceEnabledFlow],把最新值落到 @Volatile 字段。
     */
    @Volatile
    var experienceEnabledCache: Boolean = false
        private set

    /**
     * v1.116: 表情包开关与发送概率的内存缓存,供 ChatViewModel 零阻塞读取。
     * 仿照 [experimentsCache] 模式:后台协程订阅对应 Flow,把最新值落到 @Volatile 字段。
     * ChatViewModel 在构造 tools 列表时读取这两个缓存,按概率决定是否向 LLM 暴露 sticker 工具。
     */
    @Volatile
    var stickerEnabledCache: Boolean = false
        private set
    @Volatile
    var stickerSendProbabilityCache: Int = 30
        private set

    /**
     * PII Guard 开关的内存缓存,供 ChatViewModel 在发送消息前零阻塞读取。
     * 仿照 [stickerEnabledCache] 模式:后台协程订阅 [piiGuardEnabledFlow],把最新值落到 @Volatile 字段。
     * 默认 true(开启),用户在"数据与隐私"设置页可关闭。
     */
    @Volatile
    var piiGuardEnabledCache: Boolean = true
        private set

    /**
     * ANR 检测开关的内存缓存,供 AnrWatcher 零阻塞同步读取。
     * 仿照 [piiGuardEnabledCache] 模式:后台协程订阅 [anrDetectionFlow] 落缓存,默认 true。
     */
    @Volatile
    var anrDetectionCache: Boolean = true
        private set

    /**
     * 性能数据上报开关的内存缓存,供 PerformanceReporter 零阻塞同步读取。
     * 默认 false(隐私优先),用户主动开启后才会标记待上报。
     */
    @Volatile
    var perfReportCache: Boolean = false
        private set

    /**
     * v1.39: 当前 ProxyConfig 的内存缓存,供 AppKoinModule 创建 OkHttpClient 时零阻塞读取。
     *
     * 仿照 [memoryConfigCache] 模式:init 时后台协程订阅 [proxyConfigFlow] 落缓存。
     * 缓存未就绪时返回默认 [ProxyConfig](无代理),用户改完代理设置后下次创建 client 生效。
     * 这消除了 AppKoinModule 中的 2 处 `runBlocking`(主线程 ANR 根因)。
     */
    @Volatile
    var proxyConfigCache: ProxyConfig = ProxyConfig()
        private set

    /**
     * v1.74: 从 DataStore 原始 JSON 解析配置,解析失败时记录日志(不再静默吞异常)。
     *
     * 消除 13+ 处 `prefs[KEY]?.let { runCatching { decodeFromString(...) }.getOrNull() } ?: default`
     * 重复样板。当持久化数据因 schema 变更或损坏解析失败时,Logger.w 留下诊断线索,
     * 避免用户感到"配置突然丢了"却无任何日志可查。
     *
     * @param raw DataStore 中的原始 JSON 字符串(null 表示未设置)
     * @param serializer 对应类型的序列化器
     * @param keyName 配置项名称(仅用于日志标识)
     * @return 解析成功返回配置对象,raw 为 null 或解析失败返回 null
     */
    private fun <T> decodePrefsOrNull(
        raw: String?,
        serializer: KSerializer<T>,
        keyName: String,
    ): T? {
        if (raw == null) return null
        return runCatching { AppJson.decodeFromString(serializer, raw) }.getOrElse {
            Logger.w("SettingsRepository", "$keyName 解析失败,回退默认值", it)
            null
        }
    }

    // ── Flows (必须在 init 块之前声明,Kotlin 按声明顺序初始化;
    // 否则 init 里启动的协程异步访问 providersFlow 时会是 null) ──
    val providersFlow: Flow<List<ProviderConfig>> = store.data.map { prefs ->
        // v1.53-A2: 读取后解密 apiKey(旧明文数据透传,加密数据解密)
        val raw = prefs[KEY_PROVIDERS]?.let { json -> decodeProviders(json) } ?: emptyList()
        // v1.0.7: 三层合并 — specId 非空时,把 spec 默认模型列表与用户 overlay 合并
        // 对齐 openhanako BUILTIN_PLUGINS + Provider Catalog overlay 合并机制
        raw.map { enrichWithSpecDefaults(it) }
    }.catch {
        // M-SR3: 上游异常(DataStore IO / 解密失败)不应让 Flow 永久失效,回退空列表并记日志
        Logger.w("SettingsRepository", "providersFlow 异常,回退空列表", it)
        emit(emptyList())
    }
    val activeProviderIdFlow: Flow<String?> = store.data.map { prefs -> prefs[KEY_ACTIVE_PROVIDER_ID] }
    val providerConfigFlow: Flow<ProviderConfig?> = store.data.map { activeProviderFromPrefs(it) }
        .catch {
            // M-SR3: 上游异常回退 null,避免 UI 持续崩溃
            Logger.w("SettingsRepository", "providerConfigFlow 异常,回退 null", it)
            emit(null)
        }
    val selectedModelIdFlow: Flow<String?> = store.data.map { prefs -> prefs[KEY_SELECTED_MODEL] }
    /** v1.60-A: 工具模型 id(用于工具调用轮次的轻量模型,null 表示沿用主对话模型)。 */
    val toolModelIdFlow: Flow<String?> = store.data.map { prefs -> prefs[KEY_TOOL_MODEL_ID] }
    /**
     * 压缩模型 id(用于 ConversationCompressor 的分块并行摘要压缩)。
     * null 表示沿用当前主对话模型([selectedModelIdFlow] / 激活 Provider 首个模型)。
     * 用户可在此设置一个便宜的模型(如 SiliconFlow 免费模型)专做摘要压缩,避免主模型阻塞。
     */
    val compressModelIdFlow: Flow<String?> = store.data.map { prefs -> prefs[KEY_COMPRESS_MODEL_ID] }
    /** v1.0.47: Token 估算开关(默认关闭,用户显式开启以避免性能开销)。 */
    val tokenEstimateEnabledFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_TOKEN_ESTIMATE_ENABLED] ?: false }
    /** v1.0.47 P5-2: 长文本粘贴转文件开关(默认开启,粘贴超阈值文本时提示转为 txt 附件)。 */
    val pasteAsFileEnabledFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_PASTE_AS_FILE_ENABLED] ?: true }
    /** v1.0.47 P5-2: 长文本粘贴转文件阈值(字符数,超过则提示转文件)。 */
    val pasteAsFileThresholdFlow: Flow<Int> = store.data.map { prefs -> prefs[KEY_PASTE_AS_FILE_THRESHOLD] ?: 2000 }
    val memoryEnabledFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_MEMORY_ENABLED] ?: true }
    /** v1.0.51: 存量记忆迁移是否已完成(升级后首次启动补跑历史 session 摘要)。 */
    val memoryBackfillMigrationDoneFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_MEMORY_MIGRATION_V1_0_51_DONE] ?: false }
    val themeModeFlow: Flow<String> = store.data.map { prefs -> prefs[KEY_THEME_MODE] ?: "system" }
    /** v1.60-C: 应用界面语言(system=跟随系统 / zh=中文 / en=英文 / ja=日语 / ko=韩语 / ru=俄语)。 */
    val languageFlow: Flow<String> = store.data.map { prefs -> prefs[KEY_LANGUAGE] ?: "system" }
    val themeIdFlow: Flow<String> = store.data.map { prefs -> prefs[KEY_THEME_ID] ?: "mono" }
    /** 深色模式独立主题 id(空字符串表示跟随亮色主题的暗色版)。 */
    val darkThemeIdFlow: Flow<String> = store.data.map { prefs -> prefs[KEY_DARK_THEME_ID] ?: "" }
    /** 主题定时切换配置。 */
    val themeScheduleFlow: Flow<ThemeScheduleConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_THEME_SCHEDULE], ThemeScheduleConfig.serializer(), "ThemeSchedule") ?: ThemeScheduleConfig()
    }
    /** v1.65: Material You 动态取色开关(Android 12+)。 */
    val dynamicColorFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_DYNAMIC_COLOR] ?: false }
    /**
     * v1.97 gap7: 用户自定义主题列表 — 基于种子色生成 ColorScheme。
     *
     * 持久化为 JSON 数组,首次安装时返回空列表(未创建任何自定义主题)。
     * 解析失败时回退空列表,避免单个主题损坏导致 Flow 永久失效。
     */
    val customThemesFlow: Flow<List<CustomTheme>> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_CUSTOM_THEMES], ListSerializer(CustomTheme.serializer()), "CustomThemes") ?: emptyList()
    }.catch {
        Logger.w("SettingsRepository", "customThemesFlow 异常,回退空列表", it)
        emit(emptyList())
    }
    val fontSizeScaleFlow: Flow<String> = store.data.map { prefs -> prefs[KEY_FONT_SIZE_SCALE] ?: "medium" }
    /** v1.95: 启动默认页(0=任务, 1=Agent, 2=群聊)。 */
    val defaultHomePageFlow: Flow<Int> = store.data.map { prefs -> prefs[KEY_DEFAULT_HOME_PAGE] ?: 0 }
    val onboardingShownFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_ONBOARDING_SHOWN] == true }
    // v1.95: 系统语音识别首次提示是否已展示(仅首次使用时弹提示,后续直接调起 Intent)
    val asrTipShownFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_ASR_TIP_SHOWN] ?: false }
    // v1.95: 表情包库开关(默认关闭);开启后模型可在回复时发送表情包
    val stickerEnabledFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_STICKER_ENABLED] ?: false }
    // v1.95: 表情包发送概率(0-100,默认 30);模型每次回复时有此概率调用 send_sticker
    val stickerSendProbabilityFlow: Flow<Int> = store.data.map { prefs -> prefs[KEY_STICKER_SEND_PROBABILITY] ?: 30 }
    // v1.135: 调用 WebSearchConfig.decrypted() 统一解密 apiKey + apiKeys,并同步旧版单 key 到 apiKeys 映射
    val webSearchConfigFlow: Flow<WebSearchConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_WEB_SEARCH_CONFIG], WebSearchConfig.serializer(), "WebSearchConfig")?.decrypted()
            ?: WebSearchConfig()
    }.catch {
        // M-SR3: 解密/解析异常回退默认值,避免 Flow 永久失效
        Logger.w("SettingsRepository", "webSearchConfigFlow 异常,回退默认值", it)
        emit(WebSearchConfig())
    }
    // H-SR2: CloudBackupConfig 含 s3SecretKey / webdavPassword 等敏感凭据,读写均走 SecureKeyStore
    val cloudBackupConfigFlow: Flow<CloudBackupConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_CLOUD_BACKUP_CONFIG], CloudBackupConfig.serializer(), "CloudBackupConfig")
            ?.decrypted()
            ?: CloudBackupConfig()
    }.catch {
        Logger.w("SettingsRepository", "cloudBackupConfigFlow 异常,回退默认值", it)
        emit(CloudBackupConfig())
    }

    // ── v1.132: 云备份细粒度配置流(供 CloudBackupPage 表单双向绑定) ──
    /** 云备份是否已启用(type != "none" 视为启用)。 */
    val cloudBackupEnabledFlow: Flow<Boolean> = cloudBackupConfigFlow.map { it.type != "none" }
    /** 云备份类型(none / s3 / webdav)。 */
    val cloudBackupTypeFlow: Flow<String> = cloudBackupConfigFlow.map { it.type }
    /** WebDAV 服务地址。 */
    val webDavUrlFlow: Flow<String> = cloudBackupConfigFlow.map { it.webdavUrl }
    /** WebDAV 用户名。 */
    val webDavUsernameFlow: Flow<String> = cloudBackupConfigFlow.map { it.webdavUsername }
    /** WebDAV 密码(已解密;仅 UI 显示用,不要写入日志)。 */
    val webDavPasswordFlow: Flow<String> = cloudBackupConfigFlow.map { it.webdavPassword }
    /** WebDAV 远程目录。 */
    val webDavPathFlow: Flow<String> = cloudBackupConfigFlow.map { it.webdavPath }
    /** S3 端点。 */
    val s3EndpointFlow: Flow<String> = cloudBackupConfigFlow.map { it.s3Endpoint }
    /** S3 区域。 */
    val s3RegionFlow: Flow<String> = cloudBackupConfigFlow.map { it.s3Region }
    /** S3 bucket 名。 */
    val s3BucketFlow: Flow<String> = cloudBackupConfigFlow.map { it.s3Bucket }
    /** S3 Access Key。 */
    val s3AccessKeyFlow: Flow<String> = cloudBackupConfigFlow.map { it.s3AccessKey }
    /** S3 Secret Key(已解密;仅 UI 显示用,不要写入日志)。 */
    val s3SecretKeyFlow: Flow<String> = cloudBackupConfigFlow.map { it.s3SecretKey }
    /** S3 对象 key 前缀。 */
    val s3KeyPrefixFlow: Flow<String> = cloudBackupConfigFlow.map { it.s3KeyPrefix }
    /** 云备份加密密码(已解密;留空表示明文备份)。 */
    val backupPasswordFlow: Flow<String> = cloudBackupConfigFlow.map { it.backupPassword }
    /** 自动云备份开关。 */
    val cloudBackupAutoEnabledFlow: Flow<Boolean> = cloudBackupConfigFlow.map { it.autoSync }
    /** 自动云备份间隔(小时)。 */
    val cloudBackupIntervalHoursFlow: Flow<Int> = cloudBackupConfigFlow.map { it.autoSyncIntervalHours }
    // H8: WebServerConfig 含 password/pin 敏感凭据,读写均走 SecureKeyStore
    val webServerConfigFlow: Flow<WebServerConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_WEB_SERVER_CONFIG], WebServerConfig.serializer(), "WebServerConfig")
            ?.decrypted()
            ?: WebServerConfig()
    }
    val asrConfigFlow: Flow<AsrConfig> = store.data.map { prefs -> decodePrefsOrNull(prefs[KEY_ASR_CONFIG], AsrConfig.serializer(), "AsrConfig")?.let { c -> c.copy(apiKey = SecureKeyStore.decrypt(c.apiKey)) } ?: AsrConfig() }
        .catch {
            Logger.w("SettingsRepository", "asrConfigFlow 异常,回退默认值", it)
            emit(AsrConfig())
        }

    /** v1.54: RAG 配置(embedding 来源 + 检索参数)。 */
    val ragConfigFlow: Flow<RagConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_RAG_CONFIG], RagConfig.serializer(), "RagConfig") ?: RagConfig()
    }
    // H-SR2: McpServerConfig.authToken 是 Bearer token(敏感凭据),读写均走 SecureKeyStore
    val mcpServersFlow: Flow<List<io.zer0.muse.mcp.McpServerConfig>> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_MCP_SERVERS], ListSerializer(io.zer0.muse.mcp.McpServerConfig.serializer()), "McpServers")
            ?.map { it.copy(authToken = SecureKeyStore.decrypt(it.authToken)) }
            ?: emptyList()
    }.catch {
        Logger.w("SettingsRepository", "mcpServersFlow 异常,回退空列表", it)
        emit(emptyList())
    }

    /** v1.58: Prompt 模板列表(首次读取返回内置模板,用户修改后整体持久化)。 */
    val promptTemplatesFlow: Flow<List<PromptTemplate>> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_PROMPT_TEMPLATES], ListSerializer(PromptTemplate.serializer()), "PromptTemplates") ?: PromptTemplate.getBuiltInPromptTemplates(appContext)
    }

    // v0.30-a: 用户画像(6 步工作流第 1 步的用户画像 section 用)
    val userProfileFlow: Flow<UserProfile> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_USER_PROFILE], UserProfile.serializer(), "UserProfile") ?: UserProfile()
    }

    // v0.31: 聊天行为偏好(打包存储,一次序列化)
    val chatPreferencesFlow: Flow<ChatPreferences> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_CHAT_PREFERENCES], ChatPreferences.serializer(), "ChatPreferences") ?: ChatPreferences()
    }

    // v0.32: 记忆系统高级配置
    val memoryConfigFlow: Flow<io.zer0.memory.ticker.MemoryConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_MEMORY_CONFIG], io.zer0.memory.ticker.MemoryConfig.serializer(), "MemoryConfig") ?: io.zer0.memory.ticker.MemoryConfig()
    }

    // v0.32: 通知策略(never / when_unfocused / always)
    val notificationPolicyFlow: Flow<String> = store.data.map { prefs ->
        prefs[KEY_NOTIFICATION_POLICY] ?: "when_unfocused"
    }

    // v0.32: 经验库开关(默认关闭)
    val experienceEnabledFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_EXPERIENCE_ENABLED] ?: false
    }

    // PII Guard:发送消息给 LLM 前自动遮蔽敏感信息(身份证/手机/邮箱等),默认开启。
    val piiGuardEnabledFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_PII_GUARD_ENABLED] ?: true
    }

    // ANR 检测开关(默认 true),供 AnrWatcher 运行时同步读取。
    val anrDetectionFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_ANR_DETECTION] ?: true
    }

    // 性能数据上报开关(默认 false,隐私优先),供 PerformanceReporter 同步读取。
    val perfReportFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_PERF_REPORT] ?: false
    }

    // v0.32: 保持唤醒(默认关闭)
    val keepAwakeFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_KEEP_AWAKE] ?: false
    }

    // v0.32: 开机自启动(默认关闭)
    val autoLaunchFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_AUTO_LAUNCH] ?: false
    }

    // 功能1: 生物识别解锁开关
    val biometricEnabledFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_BIOMETRIC_ENABLED] ?: false
    }

    // v0.32: 应用 PIN 锁(空字符串=未启用)
    // H-SR1: PIN 是敏感凭据,绝不明文落盘 — 保存时 encrypt,读取时 decrypt(旧明文数据由 decrypt 透传兼容)
    // v1.125: 将 .catch 改为 .map 内 try-catch,确保 Flow 永不终结,
    // PIN 变更后无需重启 app 即可生效。
    val appPinFlow: Flow<String> = store.data.map { prefs ->
        try {
            prefs[KEY_APP_PIN]?.let { SecureKeyStore.decrypt(it) } ?: ""
        } catch (e: Exception) {
            // 解密失败时重置 PIN 为空串(视为未启用),Flow 继续运行不终结
            Logger.w("SettingsRepository", "appPinFlow 解密失败,PIN 已重置", e)
            ""
        }
    }

    // v1.104: PIN 锁暴力破解防护 — 失败计数与锁定截止时间持久化到 DataStore。
    // 之前用 rememberSaveable 仅 survive 配置变更,杀进程/冷启动后计数归零,
    // 攻击者杀进程重启即可绕过锁定。改为持久化后跨冷启动保留。
    val pinFailCountFlow: Flow<Int> = store.data.map { it[KEY_PIN_FAIL_COUNT] ?: 0 }
    val pinLockUntilFlow: Flow<Long> = store.data.map { it[KEY_PIN_LOCK_UNTIL] ?: 0L }

    suspend fun savePinFailState(failCount: Int, lockUntil: Long) {
        store.edit {
            it[KEY_PIN_FAIL_COUNT] = failCount
            it[KEY_PIN_LOCK_UNTIL] = lockUntil
        }
    }

    // v0.32: 实验性功能开关(打包存储)
    val experimentsFlow: Flow<ExperimentsConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_EXPERIMENTS], ExperimentsConfig.serializer(), "ExperimentsConfig") ?: ExperimentsConfig()
    }

    // v0.32: 分享模板配置
    val shareTemplateFlow: Flow<ShareTemplateConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_SHARE_TEMPLATE], ShareTemplateConfig.serializer(), "ShareTemplateConfig") ?: ShareTemplateConfig()
    }

    // v0.32: 媒体配置(语音录制/音频输出)
    val mediaConfigFlow: Flow<MediaConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_MEDIA_CONFIG], MediaConfig.serializer(), "MediaConfig") ?: MediaConfig()
    }

    // v0.32: 默认搜索引擎
    val defaultSearchEngineFlow: Flow<String> = store.data.map { prefs ->
        prefs[KEY_DEFAULT_SEARCH_ENGINE] ?: "auto"
    }

    // 全局网络代理配置
    // H-SR2: ProxyConfig.password 是敏感凭据,通过 ProxyConfig.decrypted() 在读出后解密
    // (旧版明文 password 由 decrypt 透传兼容),写入时由 saveProxyConfig 调 encrypted() 加密
    val proxyConfigFlow: Flow<ProxyConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_PROXY_CONFIG], ProxyConfig.serializer(), "ProxyConfig")?.let { c -> c.decrypted() } ?: ProxyConfig()
    }.catch {
        Logger.w("SettingsRepository", "proxyConfigFlow 异常,回退默认值(无代理)", it)
        emit(ProxyConfig())
    }

    // 主动消息配置(助手像真人一样定时主动给用户发消息)
    val proactiveMessageConfigFlow: Flow<ProactiveMessageConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_PROACTIVE_MESSAGE], ProactiveMessageConfig.serializer(), "ProactiveMessageConfig") ?: ProactiveMessageConfig()
    }

    // v0.34: 图片生成默认参数配置(尺寸/质量/风格/数量)
    val imageGenConfigFlow: Flow<ImageGenConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_IMAGE_GEN_CONFIG], ImageGenConfig.serializer(), "ImageGenConfig") ?: ImageGenConfig()
    }

    /** 视频生成默认参数配置流(用户在设置页配置)。 */
    val videoGenConfigFlow: Flow<VideoGenConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_VIDEO_GEN_CONFIG], VideoGenConfig.serializer(), "VideoGenConfig") ?: VideoGenConfig()
    }

    // v1.25: 多 Agent 协作配置(团队列表与总开关)
    // v1.201: 合并独立 DataStore key(multi_agent_review_model / multi_agent_llm_review_enabled)
    //         到 MultiAgentConfig —— 这两个字段为 @Transient,不随 JSON 序列化,
    //         由独立 key 单独读写,避免 updateMultiAgentConfig 与独立 save 方法双写竞态。
    val multiAgentConfigFlow: Flow<MultiAgentConfig> = store.data.map { prefs ->
        val base = decodePrefsOrNull(prefs[KEY_MULTI_AGENT_CONFIG], MultiAgentConfig.serializer(), "MultiAgentConfig") ?: MultiAgentConfig()
        base.copy(
            reviewModelId = prefs[KEY_MULTI_AGENT_REVIEW_MODEL],
            llmReviewEnabled = prefs[KEY_MULTI_AGENT_LLM_REVIEW_ENABLED] ?: false,
        )
    }

    // v1.25: 视觉辅助开关(默认关闭)
    val visionEnabledFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_VISION_ENABLED] ?: false }

    // P2-12: 富文本输入开关(默认关闭) — 开启后 ChatScreen 的 InputBar 替换为 RichInputBar,
    // 在输入框上方显示 Markdown 格式工具条(粗体/斜体/代码/列表等)。
    val richInputEnabledFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_RICH_INPUT_ENABLED] ?: false }
    // v1.25: 视觉辅助使用的模型 ID
    val visionModelIdFlow: Flow<String?> = store.data.map { prefs -> prefs[KEY_VISION_MODEL_ID] }
    // v1.25: 视觉辅助使用的供应商 ID
    val visionProviderIdFlow: Flow<String?> = store.data.map { prefs -> prefs[KEY_VISION_PROVIDER_ID] }

    // ── Account state ──
    val accountStateFlow: Flow<AccountState> = store.data.map { prefs ->
        AccountState(
            isLoggedIn = prefs[KEY_ACCOUNT_LOGGED_IN] ?: false,
            userName = prefs[KEY_ACCOUNT_USER_NAME] ?: "",
            loginAt = prefs[KEY_ACCOUNT_LOGIN_AT] ?: 0L,
            loginMethod = prefs[KEY_ACCOUNT_LOGIN_METHOD] ?: "",
            isGuestMode = prefs[KEY_ACCOUNT_GUEST_MODE] ?: false,
            avatarUri = prefs[KEY_ACCOUNT_AVATAR_URI],
        )
    }
    /** 是否已登录(本地标记)。 */
    val isLoggedInFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_ACCOUNT_LOGGED_IN] ?: false }
    /** 是否处于游客(离线体验)模式。 */
    val isGuestModeFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_ACCOUNT_GUEST_MODE] ?: false }

    // v2.3: 任务路由配置 Flow + 缓存(必须在 init 块之前声明,否则 init 中协程访问到 null)
    val taskRoutingConfigFlow: Flow<TaskRoutingConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_TASK_ROUTING_CONFIG], TaskRoutingConfig.serializer(), "TaskRoutingConfig")
            ?: TaskRoutingConfig()
    }
    @Volatile
    var taskRoutingConfigCache: TaskRoutingConfig = TaskRoutingConfig()
        private set

    // ── v1.133: 应用更新检查配置 ───────────────────────────────────
    /** 上次更新检查时间戳(毫秒)。0L 表示从未检查过。 */
    val lastUpdateCheckTimeFlow: Flow<Long> = store.data.map { prefs ->
        prefs[KEY_LAST_UPDATE_CHECK_TIME] ?: 0L
    }
    /**
     * 最新版本信息 JSON 缓存(序列化的 UpdateChecker.ReleaseInfo)。
     * null 表示当前无新版本(或未检查)。UI Banner 订阅此流来决定是否展示。
     */
    val latestReleaseInfoFlow: Flow<String?> = store.data.map { prefs ->
        prefs[KEY_LATEST_RELEASE_INFO]
    }
    /** 是否启用自动更新检查(默认 true;用户在设置中可关闭)。 */
    val updateCheckEnabledFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_UPDATE_CHECK_ENABLED] ?: true
    }

    // ── v2.0+: 崩溃上报配置(默认全部关闭,隐私优先) ───────────────────────
    /** 是否启用崩溃上报(默认 false — 必须用户主动开启,绝不默认上报)。 */
    val crashReportEnabledFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_CRASH_REPORT_ENABLED] ?: false
    }
    /** 上报方式:"email" / "webhook"(默认 "email")。 */
    val crashReportMethodFlow: Flow<String> = store.data.map { prefs ->
        prefs[KEY_CRASH_REPORT_METHOD] ?: "email"
    }
    /** 邮件上报收件人地址(默认空 — 用户必须配置后才生效)。 */
    val crashReportEmailFlow: Flow<String> = store.data.map { prefs ->
        prefs[KEY_CRASH_REPORT_EMAIL] ?: ""
    }
    /** Webhook 上报 URL(默认空 — 用户必须配置后才生效)。 */
    val crashReportWebhookUrlFlow: Flow<String> = store.data.map { prefs ->
        prefs[KEY_CRASH_REPORT_WEBHOOK_URL] ?: ""
    }

    /** 保存上次更新检查时间戳。 */
    suspend fun saveLastUpdateCheckTime(timestamp: Long) {
        store.edit { it[KEY_LAST_UPDATE_CHECK_TIME] = timestamp }
    }

    /**
     * 保存最新版本信息 JSON(null 表示清除缓存)。
     * [json] 为序列化后的 ReleaseInfo;null 表示当前已是最新版本,清空 Banner。
     */
    suspend fun saveLatestReleaseInfo(json: String?) {
        store.edit {
            if (json == null) it.remove(KEY_LATEST_RELEASE_INFO) else it[KEY_LATEST_RELEASE_INFO] = json
        }
    }

    /** 保存"启用自动更新检查"开关。 */
    suspend fun saveUpdateCheckEnabled(enabled: Boolean) {
        store.edit { it[KEY_UPDATE_CHECK_ENABLED] = enabled }
    }

    // ── v1.0.20: 全局默认会话权限模式(工具批准开关) ───────────────────────────
    /**
     * 全局默认会话权限模式 — 控制工具调用是否需要用户审批。
     *
     * 三档:
     *  - [SessionPermissionMode.TRUSTED]:完全放权,所有工具直接调用,不需批准
     *  - [SessionPermissionMode.ASK](默认):SAFE 工具自动放行,NORMAL/HIGH 需批准
     *  - [SessionPermissionMode.STRICT]:严格模式,仅白名单工具可用,其余全部禁止
     *
     * 用户在"设置 → 聊天 → 工具调用批准"中切换。
     * 新建会话时由 [io.zer0.muse.data.session.SessionRepository] 读取此值作为会话初始模式。
     */
    val defaultSessionPermissionModeFlow: Flow<SessionPermissionMode> = store.data.map { prefs ->
        prefs[KEY_DEFAULT_SESSION_PERMISSION_MODE]?.let { name ->
            runCatching { SessionPermissionMode.valueOf(name) }.getOrNull()
        } ?: SessionPermissionMode.ASK
    }

    /** 保存全局默认会话权限模式。 */
    suspend fun setDefaultSessionPermissionMode(mode: SessionPermissionMode) {
        store.edit { it[KEY_DEFAULT_SESSION_PERMISSION_MODE] = mode.name }
    }

    // ── v2.0+: 崩溃上报配置保存方法 ───────────────────────────────────
    /** 保存"启用崩溃上报"开关(默认关闭,用户主动开启后才会上报)。 */
    suspend fun saveCrashReportEnabled(enabled: Boolean) {
        store.edit { it[KEY_CRASH_REPORT_ENABLED] = enabled }
    }
    /** 保存上报方式("email" / "webhook")。 */
    suspend fun saveCrashReportMethod(method: String) {
        store.edit { it[KEY_CRASH_REPORT_METHOD] = method }
    }
    /** 保存邮件上报收件人地址。 */
    suspend fun saveCrashReportEmail(email: String) {
        store.edit { it[KEY_CRASH_REPORT_EMAIL] = email }
    }
    /** 保存 Webhook 上报 URL。 */
    suspend fun saveCrashReportWebhookUrl(url: String) {
        store.edit { it[KEY_CRASH_REPORT_WEBHOOK_URL] = url }
    }

    /**
     * v1.114: 关闭 cacheScope,释放协程资源。
     * SettingsRepository 是 Koin 单例,App 退出时调用。
     */
    fun shutdown() {
        cacheScope.cancel()
        Logger.i("SettingsRepository", "cacheScope cancelled")
    }

    init {
        cacheScope.launch { store.data.collect { prefs -> memoryEnabledCache.set(prefs[KEY_MEMORY_ENABLED] ?: true) } }
        // v0.32: 订阅 memoryConfigFlow + experimentsFlow,把最新 MemoryConfig 落到内存缓存,
        // 供 MemoryTicker 的 getConfig 闭包零阻塞读取(见 [memoryConfigCache])。
        // v0.32 实验性 longMemoryCompression 接入:开关打开时把 compileThreshold 从默认 4.5 降到 3.0,
        // 让 fact 更激进地进入 memory.md。combine 让两个流任一变化都重算,保证用户切开关立即生效。
        cacheScope.launch {
            combine(memoryConfigFlow, experimentsFlow) { memCfg, expCfg ->
                if (expCfg.longMemoryCompression) {
                    memCfg.copy(compileThreshold = 3.0f)
                } else {
                    memCfg
                }
            }.collect { combined ->
                memoryConfigCache = combined
            }
        }
        // v0.32: 订阅 experimentsFlow,把最新 ExperimentsConfig 落到内存缓存,
        // 供 SystemPromptAssembler / ChatViewModel 等零阻塞读取(见 [experimentsCache])。
        cacheScope.launch { experimentsFlow.collect { experimentsCache = it } }
        // v1.98: 订阅 experienceEnabledFlow,把最新开关值落到内存缓存,
        // 供 SystemPromptAssembler 零阻塞读取(见 [experienceEnabledCache])。
        cacheScope.launch { experienceEnabledFlow.collect { experienceEnabledCache = it } }
        // v1.25: 订阅 multiAgentConfigFlow,把最新 MultiAgentConfig 落到内存缓存,
        // 供 SystemPromptAssembler 零阻塞读取(见 [multiAgentConfigCache])。
        cacheScope.launch { multiAgentConfigFlow.collect { multiAgentConfigCache = it } }
        // v1.39: 订阅 proxyConfigFlow,把最新 ProxyConfig 落到内存缓存,
        // 供 AppKoinModule 创建 OkHttpClient 时零阻塞读取(见 [proxyConfigCache]),消除 runBlocking。
        cacheScope.launch { proxyConfigFlow.collect { proxyConfigCache = it } }
        // v1.116: 订阅表情包开关与概率 Flow,供 ChatViewModel 零阻塞读取。
        cacheScope.launch { stickerEnabledFlow.collect { stickerEnabledCache = it } }
        cacheScope.launch { stickerSendProbabilityFlow.collect { stickerSendProbabilityCache = it } }
        // PII Guard:订阅开关 Flow,供 ChatViewModel 在 launchStream 内零阻塞读取。
        cacheScope.launch { piiGuardEnabledFlow.collect { piiGuardEnabledCache = it } }
        // ANR 检测开关:订阅 Flow 落缓存,供 AnrWatcher 同步读取(支持运行时切换)。
        cacheScope.launch { anrDetectionFlow.collect { anrDetectionCache = it } }
        // 性能上报开关:订阅 Flow 落缓存,供 PerformanceReporter 同步读取。
        cacheScope.launch { perfReportFlow.collect { perfReportCache = it } }
        cacheScope.launch { migrateLegacyProviderIfNeeded() }
        // v1.0.18: 自动注入 SiliconFlow 免费供应商(免登录可用),确保用户进入 App 即能看到免费模型。
        // 幂等:通过 providers 列表中是否已存在 [SiliconFlowFreeModels.PROVIDER_ID] 判断,
        //   不会重复添加;用户主动删除后下次启动会重新注入(符合"免费兜底"语义)。
        cacheScope.launch {
            try {
                val providers = providersFlow.first()
                val hasFree = providers.any { it.id == SiliconFlowFreeModels.PROVIDER_ID }
                if (!hasFree) {
                    val freePreset = presetProviders.byId(SiliconFlowFreeModels.PROVIDER_ID)
                        ?: return@launch
                    val wasEmpty = providers.isEmpty()
                    // 使用原子 addProviderIfAbsent 防止与引导流程并发写入导致重复供应商
                    if (addProviderIfAbsent(freePreset)) {
                        Logger.i("SettingsRepository", "已自动注入 SiliconFlow 免费供应商")
                        // 仅在用户此前没有任何供应商(首次启动/跳过引导)且无激活供应商时,
                        // 把免费供应商设为激活,避免覆盖已有用户的主动选择。
                        if (wasEmpty && activeProviderIdFlow.first().isNullOrBlank()) {
                            setActiveProvider(freePreset.id)
                            Logger.i("SettingsRepository", "已将 SiliconFlow 免费供应商设为激活")
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.w("SettingsRepository", "注入 SiliconFlow 免费供应商失败: ${e.message}", e)
            }
        }
        // v2.3: 恢复连接测试缓存
        cacheScope.launch { restoreConnectionTestCache() }
        // v2.3: 订阅任务路由配置到内存缓存(taskRoutingConfigFlow 已在 init 前声明,安全)
        cacheScope.launch { taskRoutingConfigFlow.collect { taskRoutingConfigCache = it } }
    }
    suspend fun mockLogin(userName: String, method: String = "phone") {
        store.edit { prefs -> prefs[KEY_ACCOUNT_LOGGED_IN] = true; prefs[KEY_ACCOUNT_USER_NAME] = userName.ifBlank { appContext.getString(R.string.settings_repo_default_user_name) }; prefs[KEY_ACCOUNT_LOGIN_AT] = System.currentTimeMillis(); prefs[KEY_ACCOUNT_LOGIN_METHOD] = method; prefs[KEY_ACCOUNT_GUEST_MODE] = false }
    }
    /** 进入游客(离线体验)模式 — 跳过登录直接进入主界面。 */
    suspend fun enterGuestMode() {
        store.edit { prefs -> prefs[KEY_ACCOUNT_GUEST_MODE] = true; prefs[KEY_ACCOUNT_LOGGED_IN] = false; prefs[KEY_ACCOUNT_USER_NAME] = appContext.getString(R.string.settings_repo_guest_name); prefs[KEY_ACCOUNT_LOGIN_AT] = 0L; prefs[KEY_ACCOUNT_LOGIN_METHOD] = "guest" }
    }
    suspend fun logout() {
        store.edit { prefs -> prefs[KEY_ACCOUNT_LOGGED_IN] = false; prefs[KEY_ACCOUNT_USER_NAME] = ""; prefs[KEY_ACCOUNT_LOGIN_AT] = 0L; prefs[KEY_ACCOUNT_LOGIN_METHOD] = ""; prefs[KEY_ACCOUNT_GUEST_MODE] = false }
    }

    /**
     * v2.x: 保存用户个人资料(头像 + 昵称)。
     *
     * 用于"编辑个人资料"页 — 用户可本地选择头像图片和自定义昵称,
     * 无需登录/注册即可生效。空昵称回退默认名 [R.string.settings_repo_default_user_name]。
     *
     * @param userName 新昵称(blank 时回退默认名)
     * @param avatarUri 头像 URI 字符串(null 表示清除头像,用首字母占位)
     */
    suspend fun saveUserProfile(userName: String, avatarUri: String?) {
        store.edit { prefs ->
            prefs[KEY_ACCOUNT_USER_NAME] = userName.ifBlank { appContext.getString(R.string.settings_repo_default_user_name) }
            if (avatarUri == null) {
                prefs.remove(KEY_ACCOUNT_AVATAR_URI)
            } else {
                prefs[KEY_ACCOUNT_AVATAR_URI] = avatarUri
            }
        }
    }

    // ── Model profiles ──
    // 以 JSON 存储 Map<modelId, ModelProfile>,避免旧版 `;`/`,` 分隔格式在 avatarUrl 含分隔符时截断。
    private val modelProfileSerializer = MapSerializer(String.serializer(), ModelProfile.serializer())

    val modelProfilesFlow: Flow<Map<String, ModelProfile>> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_MODEL_PROFILES], modelProfileSerializer, "ModelProfiles") ?: emptyMap()
    }
    suspend fun saveModelProfile(modelId: String, profile: ModelProfile) {
        store.edit {
            val current = decodePrefsOrNull(it[KEY_MODEL_PROFILES], modelProfileSerializer, "ModelProfiles(save)") ?: emptyMap()
            val updated = current.toMutableMap().apply { put(modelId, profile) }
            it[KEY_MODEL_PROFILES] = AppJson.encodeToString(modelProfileSerializer, updated)
        }
    }

    fun isMemoryEnabled(): Boolean = memoryEnabledCache.get()
    suspend fun saveMemoryEnabled(enabled: Boolean) { store.edit { it[KEY_MEMORY_ENABLED] = enabled } }
    /** v1.0.51: 存量记忆迁移并发守卫 — compareAndSet 保证只跑一次,即使两个协程同时读到 false。 */
    fun tryStartMemoryBackfillMigration(): Boolean = memoryBackfillMigrationDone.compareAndSet(false, true)
    suspend fun saveMemoryBackfillMigrationDone(done: Boolean) { store.edit { it[KEY_MEMORY_MIGRATION_V1_0_51_DONE] = done } }
    suspend fun saveThemeMode(mode: String) { store.edit { it[KEY_THEME_MODE] = mode } }
    suspend fun saveThemeId(id: String) { store.edit { it[KEY_THEME_ID] = id } }
    /** 保存深色模式独立主题 id(空字符串表示跟随亮色主题的暗色版)。 */
    suspend fun saveDarkThemeId(id: String) { store.edit { it[KEY_DARK_THEME_ID] = id } }
    /** 保存主题定时切换配置。 */
    suspend fun saveThemeSchedule(config: ThemeScheduleConfig) {
        store.edit { it[KEY_THEME_SCHEDULE] = AppJson.encodeToString(ThemeScheduleConfig.serializer(), config) }
    }
    /** v1.65: 保存动态取色开关。 */
    suspend fun saveDynamicColor(enabled: Boolean) { store.edit { it[KEY_DYNAMIC_COLOR] = enabled } }
    /**
     * v1.97 gap7: 保存自定义主题列表(整体替换)。
     *
     * @param themes 完整的自定义主题列表;空列表表示清空所有自定义主题
     */
    suspend fun saveCustomThemes(themes: List<CustomTheme>) {
        store.edit { it[KEY_CUSTOM_THEMES] = AppJson.encodeToString(ListSerializer(CustomTheme.serializer()), themes) }
    }
    /**
     * v1.97 gap7: 新增或更新单个自定义主题(按 id 去重)。
     *
     * 若 [theme.id] 已存在则替换,否则追加到列表末尾。
     *
     * M-SR: 原子读-改-写 — 在单个 [store.edit] 事务内读取当前列表、应用变换、写回,
     * 避免并发调用时基于 [customThemesFlow.first()] 的快照互相覆盖而丢失更新
     * (与 [saveModelProfile] / [addProvider] / [updateMultiAgentConfig] 同模式)。
     */
    suspend fun upsertCustomTheme(theme: CustomTheme) {
        store.edit { prefs ->
            val current = decodePrefsOrNull(prefs[KEY_CUSTOM_THEMES], ListSerializer(CustomTheme.serializer()), "CustomThemes(upsert)") ?: emptyList()
            val updated = if (current.any { it.id == theme.id }) {
                current.map { if (it.id == theme.id) theme else it }
            } else {
                current + theme
            }
            prefs[KEY_CUSTOM_THEMES] = AppJson.encodeToString(ListSerializer(CustomTheme.serializer()), updated)
        }
    }
    /**
     * v1.97 gap7: 按 id 删除自定义主题。
     *
     * 若删除后当前 themeId 指向被删主题,调用方需自行回退到预设主题(如 warm_paper)。
     *
     * M-SR: 原子读-改-写 — 在单个 [store.edit] 事务内读取当前列表、过滤后写回,
     * 避免并发调用时基于 [customThemesFlow.first()] 的快照互相覆盖而丢失更新
     * (与 [deleteProvider] / [saveModelProfile] 同模式)。
     */
    suspend fun deleteCustomTheme(id: String) {
        store.edit { prefs ->
            val current = decodePrefsOrNull(prefs[KEY_CUSTOM_THEMES], ListSerializer(CustomTheme.serializer()), "CustomThemes(delete)") ?: emptyList()
            prefs[KEY_CUSTOM_THEMES] = AppJson.encodeToString(ListSerializer(CustomTheme.serializer()), current.filterNot { it.id == id })
        }
    }
    suspend fun saveFontSizeScale(scale: String) { store.edit { it[KEY_FONT_SIZE_SCALE] = scale } }
    /** v1.95: 保存启动默认页(0=任务, 1=Agent, 2=群聊)。 */
    suspend fun saveDefaultHomePage(page: Int) { store.edit { it[KEY_DEFAULT_HOME_PAGE] = page.coerceIn(0, 2) } }
    // v1.135: 调用 WebSearchConfig.encrypted() 统一加密 apiKey + apiKeys
    suspend fun saveWebSearchConfig(config: WebSearchConfig) {
        store.edit {
            it[KEY_WEB_SEARCH_CONFIG] = AppJson.encodeToString(
                WebSearchConfig.serializer(),
                config.encrypted(),
            )
        }
    }
    // H-SR2: CloudBackupConfig 含 s3SecretKey / webdavPassword 敏感凭据,写入前加密(空值原样保留)
    suspend fun saveCloudBackupConfig(config: CloudBackupConfig) {
        val encrypted = config.encrypted()
        store.edit { it[KEY_CLOUD_BACKUP_CONFIG] = AppJson.encodeToString(CloudBackupConfig.serializer(), encrypted) }
    }
    // H8: WebServerConfig 含 password/pin 敏感凭据,写入前加密
    suspend fun saveWebServerConfig(config: WebServerConfig) {
        val encrypted = config.encrypted()
        store.edit { it[KEY_WEB_SERVER_CONFIG] = AppJson.encodeToString(WebServerConfig.serializer(), encrypted) }
    }
    suspend fun saveAsrConfig(config: AsrConfig) { store.edit { it[KEY_ASR_CONFIG] = AppJson.encodeToString(AsrConfig.serializer(), config.copy(apiKey = SecureKeyStore.encrypt(config.apiKey))) } }

    /** v1.54: RAG 配置读写。 */
    suspend fun getRagConfig(): RagConfig = ragConfigFlow.first()
    suspend fun saveRagConfig(config: RagConfig) { store.edit { it[KEY_RAG_CONFIG] = AppJson.encodeToString(RagConfig.serializer(), config) } }
    // H-SR2: McpServerConfig.authToken 是 Bearer token(敏感凭据),写入前加密(空值原样保留)
    suspend fun saveMcpServers(servers: List<io.zer0.muse.mcp.McpServerConfig>) { store.edit { it[KEY_MCP_SERVERS] = AppJson.encodeToString(ListSerializer(io.zer0.muse.mcp.McpServerConfig.serializer()), servers.map { it.copy(authToken = SecureKeyStore.encrypt(it.authToken)) }) } }

    /** v1.58: 保存 Prompt 模板列表(整体替换)。 */
    suspend fun savePromptTemplates(templates: List<PromptTemplate>) {
        store.edit { it[KEY_PROMPT_TEMPLATES] = AppJson.encodeToString(ListSerializer(PromptTemplate.serializer()), templates) }
    }

    // v0.30-a: 用户画像读写(SystemPromptAssembler 用)
    suspend fun getUserProfile(): UserProfile = userProfileFlow.first()
    suspend fun saveUserProfile(profile: UserProfile) { store.edit { it[KEY_USER_PROFILE] = AppJson.encodeToString(UserProfile.serializer(), profile) } }

    // v0.31: 聊天行为偏好读写
    suspend fun getChatPreferences(): ChatPreferences = chatPreferencesFlow.first()
    suspend fun saveChatPreferences(prefs: ChatPreferences) { store.edit { it[KEY_CHAT_PREFERENCES] = AppJson.encodeToString(ChatPreferences.serializer(), prefs) } }

    // v0.32: 记忆系统高级配置
    suspend fun getMemoryConfig(): io.zer0.memory.ticker.MemoryConfig = memoryConfigFlow.first()
    suspend fun saveMemoryConfig(config: io.zer0.memory.ticker.MemoryConfig) { store.edit { it[KEY_MEMORY_CONFIG] = AppJson.encodeToString(io.zer0.memory.ticker.MemoryConfig.serializer(), config) } }

    // v0.32: 通知策略
    suspend fun saveNotificationPolicy(policy: String) { store.edit { it[KEY_NOTIFICATION_POLICY] = policy } }

    // v0.32: 经验库开关
    suspend fun saveExperienceEnabled(enabled: Boolean) { store.edit { it[KEY_EXPERIENCE_ENABLED] = enabled } }

    // PII Guard 开关(默认开启)
    suspend fun savePiiGuardEnabled(enabled: Boolean) { store.edit { it[KEY_PII_GUARD_ENABLED] = enabled } }

    // ANR 检测开关
    suspend fun saveAnrDetection(enabled: Boolean) { store.edit { it[KEY_ANR_DETECTION] = enabled } }

    // 性能数据上报开关
    suspend fun savePerfReport(enabled: Boolean) { store.edit { it[KEY_PERF_REPORT] = enabled } }

    // v0.32: 保持唤醒
    suspend fun saveKeepAwake(enabled: Boolean) { store.edit { it[KEY_KEEP_AWAKE] = enabled } }

    // v0.32: 开机自启动
    suspend fun saveAutoLaunch(enabled: Boolean) { store.edit { it[KEY_AUTO_LAUNCH] = enabled } }

    // 功能1: 生物识别解锁开关
    suspend fun saveBiometricEnabled(enabled: Boolean) { store.edit { it[KEY_BIOMETRIC_ENABLED] = enabled } }

    // v0.32: 应用 PIN 锁
    // H-SR1: PIN 是敏感凭据,绝不明文落盘 — 写入前 encrypt(空 PIN 原样保留,不加密空值)
    suspend fun saveAppPin(pin: String) { store.edit { it[KEY_APP_PIN] = SecureKeyStore.encrypt(pin) } }

    // v0.32: 实验性功能
    suspend fun saveExperiments(config: ExperimentsConfig) { store.edit { it[KEY_EXPERIMENTS] = AppJson.encodeToString(ExperimentsConfig.serializer(), config) } }

    // v0.32: 分享模板
    suspend fun saveShareTemplate(config: ShareTemplateConfig) { store.edit { it[KEY_SHARE_TEMPLATE] = AppJson.encodeToString(ShareTemplateConfig.serializer(), config) } }

    // v0.32: 媒体配置
    suspend fun saveMediaConfig(config: MediaConfig) { store.edit { it[KEY_MEDIA_CONFIG] = AppJson.encodeToString(MediaConfig.serializer(), config) } }

    // v0.32: 默认搜索引擎
    suspend fun saveDefaultSearchEngine(engine: String) { store.edit { it[KEY_DEFAULT_SEARCH_ENGINE] = engine } }

    // 全局网络代理配置读写
    // H-SR2: 写入前用 ProxyConfig.encrypted() 加密 password(空密码原样保留)
    suspend fun saveProxyConfig(config: ProxyConfig) { store.edit { it[KEY_PROXY_CONFIG] = AppJson.encodeToString(ProxyConfig.serializer(), config.encrypted()) } }

    // 主动消息配置读写
    suspend fun saveProactiveMessageConfig(config: ProactiveMessageConfig) { store.edit { it[KEY_PROACTIVE_MESSAGE] = AppJson.encodeToString(ProactiveMessageConfig.serializer(), config) } }

    // v0.34: 图片生成默认参数配置读写
    suspend fun saveImageGenConfig(config: ImageGenConfig) { store.edit { it[KEY_IMAGE_GEN_CONFIG] = AppJson.encodeToString(ImageGenConfig.serializer(), config) } }

    /** 保存视频生成默认参数配置。 */
    suspend fun saveVideoGenConfig(config: VideoGenConfig) { store.edit { it[KEY_VIDEO_GEN_CONFIG] = AppJson.encodeToString(VideoGenConfig.serializer(), config) } }

    // v1.25: 多 Agent 协作配置读写
    suspend fun saveMultiAgentConfig(config: MultiAgentConfig) { store.edit { it[KEY_MULTI_AGENT_CONFIG] = AppJson.encodeToString(MultiAgentConfig.serializer(), config) } }

    // v1.25: 视觉辅助开关
    suspend fun saveVisionEnabled(enabled: Boolean) { store.edit { it[KEY_VISION_ENABLED] = enabled } }

    // P2-12: 富文本输入开关
    suspend fun saveRichInputEnabled(enabled: Boolean) { store.edit { it[KEY_RICH_INPUT_ENABLED] = enabled } }
    // v1.25: 视觉辅助模型 ID
    suspend fun saveVisionModelId(modelId: String?) { store.edit { if (modelId != null) it[KEY_VISION_MODEL_ID] = modelId else it.remove(KEY_VISION_MODEL_ID) } }
    // v1.25: 视觉辅助供应商 ID
    suspend fun saveVisionProviderId(providerId: String?) { store.edit { if (providerId != null) it[KEY_VISION_PROVIDER_ID] = providerId else it.remove(KEY_VISION_PROVIDER_ID) } }

    /**
     * M3: 原子更新多 Agent 配置,避免读-改-写竞态。
     * 在 DataStore edit 事务内读取当前值、应用变换、写回。
     *
     * 注意:[MultiAgentConfig.reviewModelId] 与 [MultiAgentConfig.llmReviewEnabled] 为 @Transient,
     * 不随 JSON 序列化 —— 修改这两个字段需用 [saveReviewModelId] / [saveLlmReviewEnabled],
     * 而非本方法(本方法的 block 中对它们的修改不会持久化)。
     */
    suspend fun updateMultiAgentConfig(block: (MultiAgentConfig) -> MultiAgentConfig) {
        store.edit { prefs ->
            val current = decodePrefsOrNull(prefs[KEY_MULTI_AGENT_CONFIG], MultiAgentConfig.serializer(), "MultiAgentConfig") ?: MultiAgentConfig()
            prefs[KEY_MULTI_AGENT_CONFIG] = AppJson.encodeToString(MultiAgentConfig.serializer(), block(current))
        }
    }

    /**
     * v1.201: 保存 LLM 综合评审使用的模型 id。
     * @param modelId 模型 id;null 清除设置,回退到 active provider 的默认模型
     */
    suspend fun saveReviewModelId(modelId: String?) {
        store.edit { if (modelId != null) it[KEY_MULTI_AGENT_REVIEW_MODEL] = modelId else it.remove(KEY_MULTI_AGENT_REVIEW_MODEL) }
    }

    /**
     * v1.201: 保存全局 LLM 综合评审开关。
     * 关闭时 LLM_REVIEW 聚合策略自动降级为 EXPERT_REVIEW。
     */
    suspend fun saveLlmReviewEnabled(enabled: Boolean) {
        store.edit { it[KEY_MULTI_AGENT_LLM_REVIEW_ENABLED] = enabled }
    }

    suspend fun saveMcpToken(serverId: String, token: io.zer0.muse.mcp.McpTokenInfo) { store.edit { it[stringPreferencesKey("mcp_token_$serverId")] = AppJson.encodeToString(io.zer0.muse.mcp.McpTokenInfo.serializer(), token.copy(accessToken = SecureKeyStore.encrypt(token.accessToken), refreshToken = SecureKeyStore.encrypt(token.refreshToken))) } }
    suspend fun getMcpToken(serverId: String): io.zer0.muse.mcp.McpTokenInfo? {
        return decodePrefsOrNull(store.data.first()[stringPreferencesKey("mcp_token_$serverId")], io.zer0.muse.mcp.McpTokenInfo.serializer(), "McpToken($serverId)")?.let { t -> t.copy(accessToken = SecureKeyStore.decrypt(t.accessToken), refreshToken = SecureKeyStore.decrypt(t.refreshToken)) }
    }
    suspend fun clearMcpToken(serverId: String) { store.edit { it.remove(stringPreferencesKey("mcp_token_$serverId")) } }
    suspend fun saveOnboardingShown() { store.edit { it[KEY_ONBOARDING_SHOWN] = true } }
    // v1.95: 保存系统语音识别首次提示是否已展示
    suspend fun saveAsrTipShown(shown: Boolean) { store.edit { it[KEY_ASR_TIP_SHOWN] = shown } }
    // v1.95: 保存表情包库开关
    suspend fun saveStickerEnabled(enabled: Boolean) { store.edit { it[KEY_STICKER_ENABLED] = enabled } }
    // v1.95: 保存表情包发送概率(0-100,超出范围会自动收束)
    suspend fun saveStickerSendProbability(prob: Int) { store.edit { it[KEY_STICKER_SEND_PROBABILITY] = prob.coerceIn(0, 100) } }
    suspend fun addProvider(config: ProviderConfig) {
        store.edit { prefs -> val list = decodePrefsOrNull(prefs[KEY_PROVIDERS], ListSerializer(ProviderConfig.serializer()), "Providers(add)") ?: emptyList(); prefs[KEY_PROVIDERS] = encodeProviders(list + config) }
    }
    /**
     * v1.0.18: 原子「不存在才添加」— 同 id 已存在则跳过,避免自动注入与引导页保存竞态产生重复。
     *
     * 与 [addProvider] 区别:在单个 [store.edit] 事务内读-检查-写,保证幂等。
     * 供 SiliconFlow 免费供应商自动注入使用。
     *
     * @return true 表示新添加;false 表示同 id 已存在,跳过
     */
    private suspend fun addProviderIfAbsent(config: ProviderConfig): Boolean {
        var added = false
        store.edit { prefs ->
            val list = decodePrefsOrNull(prefs[KEY_PROVIDERS], ListSerializer(ProviderConfig.serializer()), "Providers(addIfAbsent)") ?: emptyList()
            if (list.none { it.id == config.id }) {
                prefs[KEY_PROVIDERS] = encodeProviders(list + config)
                added = true
            }
        }
        return added
    }
    suspend fun updateProvider(config: ProviderConfig) {
        store.edit { prefs -> val list = decodePrefsOrNull(prefs[KEY_PROVIDERS], ListSerializer(ProviderConfig.serializer()), "Providers(update)") ?: emptyList(); prefs[KEY_PROVIDERS] = encodeProviders(list.map { if (it.id == config.id) config else it }) }
        // v1.132: 失效模型列表缓存(baseUrl/apiKey 可能已变更)
        io.zer0.ai.core.ModelListCache.invalidate(config.id)
    }
    suspend fun deleteProvider(id: String) {
        store.edit { prefs -> val list = decodePrefsOrNull(prefs[KEY_PROVIDERS], ListSerializer(ProviderConfig.serializer()), "Providers(delete)") ?: emptyList(); prefs[KEY_PROVIDERS] = encodeProviders(list.filter { it.id != id }) }
        // v1.132: 失效模型列表缓存
        io.zer0.ai.core.ModelListCache.invalidate(id)
        // P2-4: 审计日志 — 用户删除 Provider(fire-and-forget,失败不影响业务)
        auditLogger.log(
            category = "user_action",
            action = "delete_provider",
            target = id,
        )
    }
    suspend fun setActiveProvider(id: String) { store.edit { it[KEY_ACTIVE_PROVIDER_ID] = id } }
    suspend fun saveSelectedModel(modelId: String?) { store.edit { if (modelId != null) it[KEY_SELECTED_MODEL] = modelId else it.remove(KEY_SELECTED_MODEL) } }
    /** v1.60-A: 保存工具模型 id(null 表示清除,沿用主对话模型)。 */
    suspend fun saveToolModel(modelId: String?) { store.edit { if (modelId != null) it[KEY_TOOL_MODEL_ID] = modelId else it.remove(KEY_TOOL_MODEL_ID) } }
    /**
     * 保存压缩模型 id(null 表示清除,沿用主对话模型)。
     * 供 ConversationCompressor 使用,建议设置为便宜模型(如 SiliconFlow 免费模型)。
     */
    suspend fun saveCompressModel(modelId: String?) { store.edit { if (modelId != null) it[KEY_COMPRESS_MODEL_ID] = modelId else it.remove(KEY_COMPRESS_MODEL_ID) } }
    /** v1.0.47: 保存 Token 估算开关。 */
    suspend fun saveTokenEstimateEnabled(enabled: Boolean) { store.edit { it[KEY_TOKEN_ESTIMATE_ENABLED] = enabled } }
    /** v1.0.47 P5-2: 保存长文本粘贴转文件开关。 */
    suspend fun savePasteAsFileEnabled(enabled: Boolean) { store.edit { it[KEY_PASTE_AS_FILE_ENABLED] = enabled } }
    /** v1.0.47 P5-2: 保存长文本粘贴转文件阈值(字符数)。 */
    suspend fun savePasteAsFileThreshold(threshold: Int) { store.edit { it[KEY_PASTE_AS_FILE_THRESHOLD] = threshold } }
    /**
     * v1.60-C: 保存应用界面语言(system / zh / en)。
     *
     * v1.131: 同步写入 [languageSyncCache],确保 [getLanguageSync] 立即返回最新值,
     * 避免下次冷启动 attachBaseContext 时主线程 runBlocking 读 DataStore 的 ANR 风险。
     */
    suspend fun saveLanguage(lang: String) {
        store.edit { it[KEY_LANGUAGE] = lang }
        languageSyncCache.edit().putString(KEY_LANGUAGE_SP, lang).apply()
    }

    /**
     * v1.131: 同步读取语言设置(从 SharedPreferences 快速缓存)。
     *
     * 用于 [io.zer0.muse.MainActivity.attachBaseContext] 等必须在主线程同步拿到语言值的场景,
     * 替代旧 `runBlocking { languageFlow.first() }` — 后者在 DataStore 首次冷启动读盘时
     * 可能产生 50-200ms 主线程阻塞,极端情况下触发 ANR。
     *
     * 缓存策略:
     *  - 写入:[saveLanguage] 同步写 SP + DataStore(DataStore 为 source of truth)
     *  - 读取:本方法直接读 SP,无 IO 阻塞
     *  - 迁移:首次构造时若 SP 为空,异步从 DataStore 同步一次到 SP(见 init 块)
     */
    fun getLanguageSync(): String = languageSyncCache.getString(KEY_LANGUAGE_SP, "system") ?: "system"

    /** 功能2: 保存指定会话的输入草稿(空文本时删除 key)。 */
    suspend fun saveChatDraft(sessionId: String, draft: String) {
        store.edit { prefs ->
            val drafts = decodePrefsOrNull(prefs[KEY_CHAT_DRAFTS], MapSerializer(String.serializer(), String.serializer()), "ChatDrafts")?.toMutableMap() ?: mutableMapOf()
            if (draft.isBlank()) {
                drafts.remove(sessionId)
            } else {
                drafts[sessionId] = draft
            }
            if (drafts.isEmpty()) {
                prefs.remove(KEY_CHAT_DRAFTS)
            } else {
                prefs[KEY_CHAT_DRAFTS] = AppJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), drafts)
            }
        }
    }

    /** 功能2: 读取指定会话的输入草稿(无草稿返回空串)。 */
    suspend fun loadChatDraft(sessionId: String): String {
        return store.data.first().let { prefs ->
            val drafts = decodePrefsOrNull(prefs[KEY_CHAT_DRAFTS], MapSerializer(String.serializer(), String.serializer()), "ChatDrafts") ?: emptyMap()
            drafts[sessionId] ?: ""
        }
    }

    /**
     * 获取当前选中的 [Model](从激活 Provider 的 models 中按 selectedModelId 查找)。
     * selectedModelId 为空时回退到激活 Provider 的首个模型。
     * 无激活 Provider 或模型列表为空时返回 null。
     */
    suspend fun getSelectedModel(): Model? {
        val config = get() ?: return null
        val modelId = selectedModelIdFlow.first()
        return config.models.firstOrNull { it.id == modelId } ?: config.models.firstOrNull()
    }

    /**
     * 按 ID 查找已配置的供应商。
     */
    suspend fun getProviderById(id: String): ProviderConfig? =
        providersFlow.first().firstOrNull { it.id == id }

    suspend fun markOnboardingShown() { store.edit { it[KEY_ONBOARDING_SHOWN] = true } }

    override suspend fun get(): ProviderConfig? = providersFlow.first()?.firstOrNull { it.id == activeProviderIdFlow.first() } ?: providersFlow.first()?.firstOrNull()

    /** v1.54: 全部 Provider 列表(用于 embedding provider 选择)。 */
    override suspend fun getAllProviders(): List<ProviderConfig> = providersFlow.first()

    private suspend fun migrateLegacyProviderIfNeeded() {
        // M-SR5: AtomicBoolean 保证迁移只执行一次,避免并发首调时
        // 两个协程同时观察到 providers 为空而重复 addProvider(旧 JSON 会被解析两次)
        if (!migrationDone.compareAndSet(false, true)) return
        if (providersFlow.first().isNotEmpty()) return
        val legacy = store.data.first()[KEY_PROVIDER_LEGACY]
        if (legacy != null) {
            runCatching { AppJson.decodeFromString(ProviderConfig.serializer(), legacy) }.onSuccess { config ->
                addProvider(config)
                store.edit { it.remove(KEY_PROVIDER_LEGACY) }
            }
        }
    }

    private suspend fun activeProviderFromPrefs(prefs: Preferences): ProviderConfig? {
        val providers = prefs[KEY_PROVIDERS]?.let { decodeProviders(it) } ?: return null
        val activeId = prefs[KEY_ACTIVE_PROVIDER_ID] ?: return providers.firstOrNull()?.let { enrichWithSpecDefaults(it) }
        return (providers.firstOrNull { it.id == activeId } ?: providers.firstOrNull())?.let { enrichWithSpecDefaults(it) }
    }

    /**
     * v1.0.7: 三层合并 — 用 spec 默认模型列表丰富用户配置。
     *
     * 对齐 openhanako _merge(plugin, userConfig):specId 非空时,从 [presetProviders]
     * 查找 spec,把 spec 默认模型 + 用户 overlay 模型合并(同 id 用户优先)。
     * specId 为 null(纯自定义供应商)或 spec 未找到时原样返回。
     */
    private fun enrichWithSpecDefaults(config: ProviderConfig): ProviderConfig {
        val specId = config.specId ?: return config
        val spec = presetProviders.bySpecId(specId) ?: return config
        return ProviderSpecMerger.enrichConfig(config, spec)
    }

    /**
     * v1.0.7: 数据迁移 — 给已有 "preset_" 前缀 id 的配置推断 specId(幂等)。
     *
     * 旧数据(v1.0.6 及之前)反序列化后 specId=null,这里按 id 前缀补全:
     *  "preset_openai" → specId="openai"
     *  "preset_deepseek" → specId="deepseek"
     *
     * 幂等:已有 specId 的配置不受影响。纯自定义供应商(id 不以 "preset_" 开头)不受影响。
     * 下次 [updateProvider] 写入时 specId 自然持久化,后续读取不再需要推断。
     */
    private fun migrateSpecId(config: ProviderConfig): ProviderConfig {
        if (config.specId != null) return config
        if (!config.id.startsWith("preset_")) return config
        val inferred = config.id.removePrefix("preset_").ifBlank { null } ?: return config
        return config.copy(specId = inferred)
    }

    // ── v1.53-A2: 敏感字段(apiKey)透明加解密 ──────────────────────────
    // 写入 DataStore 前加密,读取后解密。ai 模块和 UI 无感知。
    // 旧版明文数据 decrypt 透传,下次写入时 encrypt 自动加密(无需显式迁移)。

    /** 加密每个 Provider 的 apiKey 后序列化为 JSON。 */
    private suspend fun encodeProviders(list: List<ProviderConfig>): String =
        AppJson.encodeToString(
            ListSerializer(ProviderConfig.serializer()),
            list.map { it.copy(apiKey = SecureKeyStore.encrypt(it.apiKey)) },
        )

    /**
     * 反序列化 JSON 后解密每个 Provider 的 apiKey。
     *
     * M-SR1: 解析失败不再静默吞异常 — 记录 Logger.w 便于诊断"配置突然丢失"问题,
     * 返回 null 让上游回退空列表(与 decodePrefsOrNull 行为一致)。
     *
     * 用 [resultOf] 而非 runCatching: block 内含 suspend 调用(SecureKeyStore.decrypt),
     * runCatching 会吞掉 CancellationException 破坏结构化并发,resultOf 会重抛。
     */
    private suspend fun decodeProviders(json: String): List<ProviderConfig>? =
        resultOf {
            AppJson.decodeFromString(ListSerializer(ProviderConfig.serializer()), json)
                .map { it.copy(apiKey = SecureKeyStore.decrypt(it.apiKey)) }
                // v1.0.7: 幂等迁移 — 给旧数据(v1.0.6 及之前,无 specId 字段)推断 specId
                .map { migrateSpecId(it) }
        }.onError { msg, t ->
            Logger.w("SettingsRepository", "Providers JSON 解析失败,回退 null: $msg", t)
        }.getOrNull()

    // ── 备份: 设置快照导出/导入 ─────────────────────────────────────

    /**
     * 备份用: 导出所有非敏感 DataStore 设置为 Map<String, String>。
     *
     * 敏感 key(PIN/Provider API Key/MCP token/云备份凭据/代理密码等)被跳过,
     * 因为加密依赖设备级 SecureKeyStore,跨设备解密会失败。
     * 用户在恢复后需重新输入这些敏感配置。
     */
    suspend fun exportSettingsSnapshot(): Map<String, String> {
        val prefs = store.data.first()
        val result = mutableMapOf<String, String>()
        // 安全的 string 类型 key
        val safeStringKeys = listOf(
            "active_provider_id", "selected_model_id", "tool_model_id",
            "compress_model_id",
            "theme_mode", "language", "theme_id", "dark_theme_id",
            "theme_schedule_json", "custom_themes_json", "font_size_scale",
            "prompt_templates_json", "user_profile_json", "chat_preferences_json",
            "memory_config_json", "notification_policy", "experiments_json",
            "share_template_json", "media_config_json", "default_search_engine",
            "proactive_message_json", "image_gen_config_json", "video_gen_config_json",
            "multi_agent_config_json", "rag_config_json", "chat_drafts_json",
            "task_routing_config_json", "model_profiles_json",
            "account_user_name", "account_login_method",
            "multi_agent_review_model",
        )
        // 安全的 boolean 类型 key
        val safeBooleanKeys = listOf(
            "memory_enabled", "dynamic_color", "onboarding_shown",
            "asr_tip_shown", "sticker_enabled", "experience_enabled",
            "keep_awake", "auto_launch", "biometric_enabled",
            "account_logged_in", "account_guest_mode",
            "pii_guard_enabled",
            "multi_agent_llm_review_enabled",
        )
        // 安全的 int 类型 key
        val safeIntKeys = listOf(
            "default_home_page", "sticker_send_probability",
        )
        // 安全的 long 类型 key
        val safeLongKeys = listOf(
            "account_login_at",
        )
        for (name in safeStringKeys) {
            prefs[stringPreferencesKey(name)]?.let { result[name] = it }
        }
        for (name in safeBooleanKeys) {
            prefs[booleanPreferencesKey(name)]?.let { result["bool:$name"] = it.toString() }
        }
        for (name in safeIntKeys) {
            prefs[intPreferencesKey(name)]?.let { result["int:$name"] = it.toString() }
        }
        for (name in safeLongKeys) {
            prefs[longPreferencesKey(name)]?.let { result["long:$name"] = it.toString() }
        }
        return result
    }

    /**
     * 备份恢复用: 把设置快照写回 DataStore。
     *
     * 仅覆盖快照中包含的 key,不影响其他 key(如敏感 key)。
     */
    suspend fun restoreSettingsSnapshot(snapshot: Map<String, String>) {
        if (snapshot.isEmpty()) return
        store.edit { prefs ->
            for ((rawKey, value) in snapshot) {
                when {
                    rawKey.startsWith("bool:") -> {
                        val name = rawKey.removePrefix("bool:")
                        prefs[booleanPreferencesKey(name)] = value.toBoolean()
                    }
                    rawKey.startsWith("int:") -> {
                        val name = rawKey.removePrefix("int:")
                        prefs[intPreferencesKey(name)] = value.toIntOrNull() ?: continue
                    }
                    rawKey.startsWith("long:") -> {
                        val name = rawKey.removePrefix("long:")
                        prefs[longPreferencesKey(name)] = value.toLongOrNull() ?: continue
                    }
                    else -> {
                        prefs[stringPreferencesKey(rawKey)] = value
                    }
                }
            }
        }
    }

    companion object {
        private val KEY_PROVIDERS = stringPreferencesKey("providers_json")
        private val KEY_ACTIVE_PROVIDER_ID = stringPreferencesKey("active_provider_id")
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model_id")
        private val KEY_TOOL_MODEL_ID = stringPreferencesKey("tool_model_id")
        /** 压缩模型 id(独立便宜模型,供 ConversationCompressor 使用)。 */
        private val KEY_COMPRESS_MODEL_ID = stringPreferencesKey("compress_model_id")
        /** v1.0.47: Token 估算开关。 */
        private val KEY_TOKEN_ESTIMATE_ENABLED = booleanPreferencesKey("token_estimate_enabled")
        private val KEY_PASTE_AS_FILE_ENABLED = booleanPreferencesKey("paste_as_file_enabled")
        private val KEY_PASTE_AS_FILE_THRESHOLD = intPreferencesKey("paste_as_file_threshold")
        private val KEY_MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        /** v1.0.51: 一次性存量记忆迁移标志位 — 升级后首次启动补跑历史 session 的 rollingSummary。 */
        private val KEY_MEMORY_MIGRATION_V1_0_51_DONE = booleanPreferencesKey("memory_migration_v1_0_51_done")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        /** v1.131: [KEY_LANGUAGE] 的字符串形式,供 SharedPreferences 同步缓存使用(见 [getLanguageSync])。 */
        private const val KEY_LANGUAGE_SP = "language"
        private val KEY_THEME_ID = stringPreferencesKey("theme_id")
        private val KEY_DARK_THEME_ID = stringPreferencesKey("dark_theme_id")
        private val KEY_THEME_SCHEDULE = stringPreferencesKey("theme_schedule_json")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        /** v1.97 gap7: 自定义主题列表 JSON(数组序列化,空数组=未创建)。 */
        private val KEY_CUSTOM_THEMES = stringPreferencesKey("custom_themes_json")
        private val KEY_FONT_SIZE_SCALE = stringPreferencesKey("font_size_scale")
        /** v1.95: 启动默认页(0=任务, 1=Agent, 2=群聊)。 */
        private val KEY_DEFAULT_HOME_PAGE = intPreferencesKey("default_home_page")
        private val KEY_ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")
        // v1.95: 系统语音识别首次提示是否已展示
        private val KEY_ASR_TIP_SHOWN = booleanPreferencesKey("asr_tip_shown")
        // v1.95: 表情包库开关(默认关闭)
        private val KEY_STICKER_ENABLED = booleanPreferencesKey("sticker_enabled")
        // v1.95: 表情包发送概率(0-100,默认 30)
        private val KEY_STICKER_SEND_PROBABILITY = intPreferencesKey("sticker_send_probability")
        private val KEY_WEB_SEARCH_CONFIG = stringPreferencesKey("web_search_config_json")
        private val KEY_CLOUD_BACKUP_CONFIG = stringPreferencesKey("cloud_backup_config_json")
        private val KEY_WEB_SERVER_CONFIG = stringPreferencesKey("web_server_config_json")
        private val KEY_ASR_CONFIG = stringPreferencesKey("asr_config_json")
        private val KEY_MCP_SERVERS = stringPreferencesKey("mcp_servers_json")
        private val KEY_PROMPT_TEMPLATES = stringPreferencesKey("prompt_templates_json")
        private val KEY_PROVIDER_LEGACY = stringPreferencesKey("provider_config_json")
        private val KEY_ACCOUNT_LOGGED_IN = booleanPreferencesKey("account_logged_in")
        private val KEY_ACCOUNT_USER_NAME = stringPreferencesKey("account_user_name")
        private val KEY_ACCOUNT_LOGIN_AT = longPreferencesKey("account_login_at")
        private val KEY_ACCOUNT_LOGIN_METHOD = stringPreferencesKey("account_login_method")
        private val KEY_ACCOUNT_GUEST_MODE = booleanPreferencesKey("account_guest_mode")
    private val KEY_ACCOUNT_AVATAR_URI = stringPreferencesKey("account_avatar_uri")
        private val KEY_MODEL_PROFILES = stringPreferencesKey("model_profiles_json")
        // v0.30-a: 用户画像
        private val KEY_USER_PROFILE = stringPreferencesKey("user_profile_json")
        // v0.31: 聊天行为偏好
        private val KEY_CHAT_PREFERENCES = stringPreferencesKey("chat_preferences_json")
        // v0.32: 记忆系统高级配置
        private val KEY_MEMORY_CONFIG = stringPreferencesKey("memory_config_json")
        private val KEY_NOTIFICATION_POLICY = stringPreferencesKey("notification_policy")
        private val KEY_EXPERIENCE_ENABLED = booleanPreferencesKey("experience_enabled")
        // PII Guard 开关(默认开启)
        private val KEY_PII_GUARD_ENABLED = booleanPreferencesKey("pii_guard_enabled")
        private val KEY_KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        private val KEY_AUTO_LAUNCH = booleanPreferencesKey("auto_launch")
        /** ANR 检测开关(默认 true)。 */
        private val KEY_ANR_DETECTION = booleanPreferencesKey("anr_detection_enabled")
        /** 性能数据上报开关(默认 false,隐私优先)。 */
        private val KEY_PERF_REPORT = booleanPreferencesKey("perf_report_enabled")
        private val KEY_APP_PIN = stringPreferencesKey("app_pin")
        private val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        // v1.104: PIN 锁暴力破解防护持久化(之前用 rememberSaveable,杀进程即重置)
        private val KEY_PIN_FAIL_COUNT = intPreferencesKey("pin_fail_count")
        private val KEY_PIN_LOCK_UNTIL = longPreferencesKey("pin_lock_until")
        private val KEY_EXPERIMENTS = stringPreferencesKey("experiments_json")
        private val KEY_SHARE_TEMPLATE = stringPreferencesKey("share_template_json")
        private val KEY_MEDIA_CONFIG = stringPreferencesKey("media_config_json")
        private val KEY_DEFAULT_SEARCH_ENGINE = stringPreferencesKey("default_search_engine")
        private val KEY_PROXY_CONFIG = stringPreferencesKey("proxy_config_v1")
        private val KEY_PROACTIVE_MESSAGE = stringPreferencesKey("proactive_message_json")
        private val KEY_IMAGE_GEN_CONFIG = stringPreferencesKey("image_gen_config_json")
        /** 视频生成默认参数配置。 */
        private val KEY_VIDEO_GEN_CONFIG = stringPreferencesKey("video_gen_config_json")
        /** v1.25: 多 Agent 协作配置（团队列表与总开关）。 */
        private val KEY_MULTI_AGENT_CONFIG = stringPreferencesKey("multi_agent_config_json")
        /** v1.201: LLM 综合评审使用的模型 id(独立 key,不随 MultiAgentConfig JSON 序列化)。 */
        private val KEY_MULTI_AGENT_REVIEW_MODEL = stringPreferencesKey("multi_agent_review_model")
        /** v1.201: 全局 LLM 综合评审开关(独立 key,不随 MultiAgentConfig JSON 序列化)。 */
        private val KEY_MULTI_AGENT_LLM_REVIEW_ENABLED = booleanPreferencesKey("multi_agent_llm_review_enabled")
        /** v1.25: 视觉辅助开关（让纯文本模型通过视觉模型"看到"图片）。 */
        private val KEY_VISION_ENABLED = booleanPreferencesKey("vision_enabled")

        /** P2-12: 富文本输入开关(开启后 InputBar 替换为 RichInputBar)。 */
        private val KEY_RICH_INPUT_ENABLED = booleanPreferencesKey("rich_input_enabled")
        /** v1.25: 视觉辅助使用的模型 ID。 */
        private val KEY_VISION_MODEL_ID = stringPreferencesKey("vision_model_id")
        /** v1.25: 视觉辅助使用的供应商 ID。 */
        private val KEY_VISION_PROVIDER_ID = stringPreferencesKey("vision_provider_id")
        /** v1.54: RAG 配置(embedding 来源 + 检索参数)。 */
        private val KEY_RAG_CONFIG = stringPreferencesKey("rag_config_json")
        private val KEY_CHAT_DRAFTS = stringPreferencesKey("chat_drafts_json")
        // v2.3: 连接测试缓存 JSON(providerId → result,带 TTL)
        private val KEY_CONNECTION_TEST_CACHE = stringPreferencesKey("connection_test_cache_json")
        // v2.3: 任务模型路由配置
        private val KEY_TASK_ROUTING_CONFIG = stringPreferencesKey("task_routing_config_json")
        // v1.133: GitHub Release 更新检查相关键
        private val KEY_LAST_UPDATE_CHECK_TIME = longPreferencesKey("last_update_check_time")
        private val KEY_LATEST_RELEASE_INFO = stringPreferencesKey("latest_release_info_json")
        private val KEY_UPDATE_CHECK_ENABLED = booleanPreferencesKey("update_check_enabled")
        // v1.0.20: 全局默认会话权限模式(TRUSTED / ASK / STRICT,默认 ASK)
        private val KEY_DEFAULT_SESSION_PERMISSION_MODE = stringPreferencesKey("default_session_permission_mode")
        // v2.0+: 崩溃上报配置键(默认全部关闭,隐私优先)
        private val KEY_CRASH_REPORT_ENABLED = booleanPreferencesKey("crash_report_enabled")
        private val KEY_CRASH_REPORT_METHOD = stringPreferencesKey("crash_report_method")
        private val KEY_CRASH_REPORT_EMAIL = stringPreferencesKey("crash_report_email")
        private val KEY_CRASH_REPORT_WEBHOOK_URL = stringPreferencesKey("crash_report_webhook_url")
    }

    // ── v2.3: Provider 连接测试缓存 ───────────────────────────────────

    /** 单条连接测试缓存条目。 */
    @kotlinx.serialization.Serializable
    data class ConnectionTestCacheEntry(
        val result: String,
        val isSuccess: Boolean,
        val testedAt: Long = System.currentTimeMillis(),
    )

    /** 连接测试缓存映射(providerId → 缓存条目),TTL = 5 分钟。 */
    private var connectionTestCache: Map<String, ConnectionTestCacheEntry> = emptyMap()

    /** 取缓存的连接测试结果(未过期时返回缓存,否则 null)。 */
    suspend fun getCachedConnectionTest(providerId: String): ConnectionTestCacheEntry? {
        val now = System.currentTimeMillis()
        val entry = connectionTestCache[providerId] ?: return null
        if (now - entry.testedAt > 300_000L) { // 5 分钟 TTL
            connectionTestCache = connectionTestCache - providerId
            return null
        }
        return entry
    }

    /** 保存连接测试缓存。 */
    suspend fun saveConnectionTestCache(providerId: String, result: String, isSuccess: Boolean) {
        val entry = ConnectionTestCacheEntry(result = result, isSuccess = isSuccess)
        connectionTestCache = connectionTestCache + (providerId to entry)
        store.edit { it[KEY_CONNECTION_TEST_CACHE] = AppJson.encodeToString(
            MapSerializer(String.serializer(), ConnectionTestCacheEntry.serializer()),
            connectionTestCache,
        ) }
    }

    /** 恢复缓存(init 时调用)。 */
    private suspend fun restoreConnectionTestCache() {
        val cached = store.data.first()[KEY_CONNECTION_TEST_CACHE]
        if (cached != null) {
            connectionTestCache = runCatching {
                AppJson.decodeFromString(
                    MapSerializer(String.serializer(), ConnectionTestCacheEntry.serializer()),
                    cached,
                )
            }.getOrDefault(emptyMap())
        }
    }

    // ── v2.3: 任务模型路由 ─────────────────────────────────────────

    /** 任务类型枚举。 */
    enum class TaskType(val displayName: String, val description: String) {
        CHAT("闲聊", "日常对话与快速问答"),
        REASONING("推理", "复杂逻辑推理与数学"),
        CODE("编程", "代码生成与调试"),
        CREATIVE("创作", "写作与创意生成"),
        ANALYSIS("分析", "数据分析与报告"),
    }

    /** 任务路由配置——每种任务类型绑定默认模型,以及总开关。 */
    @kotlinx.serialization.Serializable
    data class TaskRoutingConfig(
        val enabled: Boolean = false,
        val chatModelId: String? = null,
        val reasoningModelId: String? = null,
        val codeModelId: String? = null,
        val creativeModelId: String? = null,
        val analysisModelId: String? = null,
    )

    suspend fun saveTaskRoutingConfig(config: TaskRoutingConfig) {
        store.edit { it[KEY_TASK_ROUTING_CONFIG] = AppJson.encodeToString(TaskRoutingConfig.serializer(), config) }
    }

    /** 根据输入文本自动检测任务类型。 */
    fun detectTaskType(input: String): TaskType {
        val text = input.trim().lowercase()
        return when {
            text.startsWith("写") || text.startsWith("创作") || text.startsWith("作诗") ||
                text.startsWith("诗歌") || text.startsWith("故事") || text.startsWith("小说") ||
                text.contains("写一") || text.contains("创作一") -> TaskType.CREATIVE
            text.startsWith("分析") || text.startsWith("总结") || text.startsWith("统计") ||
                text.startsWith("比较") || text.startsWith("对比") || text.startsWith("报告") ||
                text.contains("数据分析") -> TaskType.ANALYSIS
            text.startsWith("写代码") || text.startsWith("编程") || text.startsWith("实现") ||
                text.startsWith("debug") || text.startsWith("调试") || text.startsWith("函数") ||
                text.contains("代码") || text.contains("bug") || text.contains("算法") -> TaskType.CODE
            text.startsWith("为什么") || text.startsWith("如何") || text.startsWith("推理") ||
                text.startsWith("证明") || text.startsWith("推导") || text.startsWith("解释") ||
                text.contains("逻辑") || text.contains("数学") || text.contains("原因") -> TaskType.REASONING
            else -> TaskType.CHAT
        }
    }

    /** 根据任务类型推荐模型 id(路由开启时返回绑定模型,否则 null)。非 suspend,基于内存缓存。 */
    fun recommendModelForTask(input: String, fallbackModelId: String?): String? {
        val config = taskRoutingConfigCache
        if (!config.enabled) return null
        val type = detectTaskType(input)
        return when (type) {
            TaskType.CHAT -> config.chatModelId
            TaskType.REASONING -> config.reasoningModelId
            TaskType.CODE -> config.codeModelId
            TaskType.CREATIVE -> config.creativeModelId
            TaskType.ANALYSIS -> config.analysisModelId
        } ?: fallbackModelId
    }
}

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
    val multiAgentCollaboration: Boolean = false,
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
 * 主题定时切换配置。
 *
 * 在指定时间自动切换亮色/深色模式。
 */
@kotlinx.serialization.Serializable
data class ThemeScheduleConfig(
    /** 总开关。 */
    val enabled: Boolean = false,
    /** 起床时间(小时,0-23)。到此后切换为浅色模式(或跟随系统)。 */
    val wakeUpHour: Int = 7,
    /** 起床时间(分钟,0-59)。 */
    val wakeUpMinute: Int = 0,
    /** 睡觉时间(小时,0-23)。到此后切换为深色模式。 */
    val sleepHour: Int = 22,
    /** 睡觉时间(分钟,0-59)。 */
    val sleepMinute: Int = 0,
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
