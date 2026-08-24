package io.zer0.muse.ui

/**
 * v0.22: 顶层路由常量。
 *
 * 首页 HomeScreen 使用顶部双 Tab(任务/Agent),
 * 下列路由用于 NavHost 中"从设置/外部 DeepLink 进入子页面"场景。
 */
object MuseRoutes {
    /** v0.22: 首页 — 顶部 Tab 导航。 */
    const val HOME = "home"
    /** v0.45: 独立全局搜索页(从首页右上角搜索按钮进入)。 */
    const val SEARCH = "search"
    /** 用户画像编辑页(年龄/城市/MBTI 等,用于 AI 个性化)。 */
    const val USER_PROFILE_EDIT = "user_profile_edit"
    /** 聊天详情页(从对话列表进入,无 BottomBar)。 */
    const val CHAT_DETAIL = "chat_detail"
    /** 助手 Tab。 */
    const val ASSISTANTS = "assistants"
    /** v0.37: 助手详情聚合页(头部 + 5 个子页入口)。 */
    const val ASSISTANT_DETAIL = "assistant_detail"
    /** v0.37: 助手基础子页(名称 / 头像 / 模型 / 采样参数)。 */
    const val ASSISTANT_BASIC = "assistant_basic"
    /** v0.37: 助手提示词子页(systemPrompt / 模板 / 预设消息)。 */
    const val ASSISTANT_PROMPT = "assistant_prompt"
    /** v0.37: 助手扩展子页(关联资源数量)。 */
    const val ASSISTANT_EXTENSIONS = "assistant_extensions"
    /** v0.37: 助手记忆子页(4 个记忆开关)。 */
    const val ASSISTANT_MEMORY = "assistant_memory"
    /** v0.37: 助手高级子页(背景 / 自定义请求 / 标签)。 */
    const val ASSISTANT_ADVANCED = "assistant_advanced"
    /** 记忆 Tab(阶段 6 实现,先占位)。 */
    const val MEMORY = "memory"
    /** 设置 Tab。 */
    const val SETTINGS = "settings"

    /** 收藏夹(子页面,从设置入口)。 */
    const val FAVORITES = "favorites"
    /** Lorebook(子页面,从设置入口)。 */
    const val LOREBOOKS = "lorebooks"
    /** P1-2: Worldbook 动态世界书(子页面,从设置入口)。 */
    const val WORLDBOOK = "worldbook"
    /** 快捷消息(子页面,从设置入口)。 */
    const val QUICK_MESSAGES = "quick_messages"
    /** Prompt 注入(子页面,从设置入口)。 */
    const val PROMPT_INJECTIONS = "prompt_injections"
    /** Skill 管理(子页面,从设置入口)。 */
    const val SKILLS = "skills"
    /** Phase 13: 开源许可(子页面,从设置 → 关于 进入)。 */
    const val LICENSES = "licenses"
    /** Phase 15: 账户(从设置 → 账户卡点击进入)。 */
    const val ACCOUNT = "account"
    /** Phase 17: 定时任务。 */
    const val SCHEDULED_TASKS = "scheduled_tasks"
    /** Phase 17: 知识库。 */
    const val KNOWLEDGE = "knowledge"
    /** v1.136: 快速记录。 */
    const val QUICK_NOTES = "quick_notes"

    /** v0.46: 统计页(热力图 + 使用统计,从设置 → 通用偏好 → 统计 进入)。 */
    const val STATS = "stats"

    /** v0.26: 设置二级页 — 模型与服务。 */
    const val SETTINGS_MODEL = "settings_model"
    /** B0-04: 设置二级页 — 任务模型路由。 */
    const val SETTINGS_TASK_ROUTING = "settings_task_routing"
    /** B0-07: 提示词模板管理页。 */
    const val PROMPT_TEMPLATE_MANAGER = "prompt_template_manager"
    /** v0.26: 设置二级页 — 数据与备份。 */
    const val SETTINGS_DATA = "settings_data"
    /** v0.26: 设置二级页 — 外观。 */
    const val SETTINGS_APPEARANCE = "settings_appearance"
    /** v0.26: 设置二级页 — 关于。 */
    const val SETTINGS_ABOUT = "settings_about"
    /** v0.31: 设置二级页 — 聊天行为。 */
    const val SETTINGS_CHAT = "settings_chat"
    /** v1.0.51: 记忆中心(4 Tab 查看+编辑)。 */
    const val SETTINGS_MEMORY = "settings_memory"
    /** v1.0.51: 记忆参数配置页(原"记忆与通知",从记忆中心齿轮入口进入)。 */
    const val SETTINGS_MEMORY_CONFIG = "settings_memory_config"
    /** v1.0.52 P2-2: 记忆空间管理页(Space CRUD)。 */
    const val SETTINGS_MEMORY_SPACE = "settings_memory_space"
    /** v0.32: 设置二级页 — 媒体。 */
    const val SETTINGS_MEDIA = "settings_media"
    /** v0.32: 设置二级页 — 实验性功能。 */
    const val SETTINGS_EXPERIMENTS = "settings_experiments"
    /** v0.32: 设置二级页 — 安全与分享。 */
    const val SETTINGS_SECURITY = "settings_security"

    /** v0.32: 设置二级页 — 网络代理。 */
    const val SETTINGS_PROXY = "settings_proxy"

    /** v1.25: 设置二级页 — 多 Agent 协作。 */
    const val SETTINGS_MULTI_AGENT = "settings_multi_agent"

    /** Multi-Agent 工作流可视化编排页(带 {teamId} 参数)。 */
    
    /** v1.27: 设置二级页 — Agent 配置(Agent 助手选择/协作/主动消息)。 */
    const val SETTINGS_AGENT = "settings_agent"

    /** 独立主动消息设置页。 */
    const val SETTINGS_PROACTIVE = "settings_proactive"

    /** v1.56: 设置二级页 — RAG 知识库检索配置。 */
    const val SETTINGS_RAG = "settings_rag"

    /** v1.133: 三级页 — 多知识库管理(从 RAG 设置页进入)。 */
    const val KB_MANAGE = "kb_manage"

    /** v1.25: 设置二级页 — 视觉辅助(让纯文本模型通过视觉模型"看到"图片)。 */
    const val SETTINGS_VISION = "settings_vision"

    /** v1.61: 设置二级页 — 数据导入。 */
    const val SETTINGS_DATA_IMPORT = "settings_data_import"

    /** v1.132: 设置二级页 — 云备份(独立页,管理 S3/WebDAV 配置与远端备份列表)。 */
    const val SETTINGS_CLOUD_BACKUP = "settings_cloud_backup"

    /** v1.61: 设置二级页 — 使用教程。 */
    const val SETTINGS_TUTORIAL = "settings_tutorial"

    /** v1.30: 群聊详情页(从群聊列表进入,带 chatId 参数)。 */
    const val GROUP_CHAT_DETAIL = "group_chat_detail"

    /** v1.97 gap8: 独立翻译页(从设置 → 工具 进入)。 */
    const val TRANSLATE = "translate"

    /** v2.0: 数据管理页(从设置 → 数据与备份 进入)。 */
    const val DATA_MANAGEMENT = "data_management"

    /** v1.0.53: 封面库管理页(知识库文档详情 → 封面)。 */
    const val COVER_MANAGER = "cover_manager"

    /** v2.0: 最近删除页。 */
    const val RECENTLY_DELETED = "recently_deleted"

    /** v1.126: Agent 私信收件箱。 */
    const val AGENT_DM = "agent_dm"

    /** v1.127: 里程碑管理页。 */
    const val MILESTONES = "milestones"


    /** HTML 全屏预览页(从消息气泡内 HTML/SVG 代码块入口进入)。 */
    const val HTML_PREVIEW = "html_preview"

    /** 调试日志页(从设置 → 关于 → 调试日志 进入,展示最近 Logger 调用)。 */
    const val DEBUG = "debug"

    /** P2-4: 审计日志页(从设置 → 数据与隐私 → 审计日志 进入)。 */
    const val AUDIT_LOG = "audit_log"

    /** P2-7: 工作区页(从设置 → 数据与隐私 → 工作区 进入,文件管理器)。 */
    const val WORKSPACE = "workspace"

    /** P2-8: 视频生成页(从设置 → 工具 进入)。 */
    const val VIDEO_GENERATION = "video_generation"

    /** P2-9: 语音克隆页(从设置 → 媒体 → 语音克隆 进入,ElevenLabs 等 Provider)。 */
    const val VOICE_CLONING = "voice_cloning"


    /** P2-10: Provider 插件管理页(从设置 → 模型与服务 → 插件管理 进入)。 */
    const val PROVIDER_PLUGINS = "provider_plugins"
    const val MUSE_PLUGINS = "muse_plugins"

    /** v1.133: 设置二级页 — 联网搜索(从 SettingsModelPage 拆出)。 */
    const val SETTINGS_WEB_SEARCH = "settings_web_search"
    /** v1.133: 设置二级页 — 语音识别 ASR(从 SettingsModelPage 拆出)。 */
    const val SETTINGS_ASR = "settings_asr"
    /** v1.133: 设置二级页 — MCP 服务器(从 SettingsModelPage 拆出)。 */
    const val SETTINGS_MCP = "settings_mcp"

    /** v1.133: 设置二级页 — 助手资源(从 SettingsModelPage 拆出:收藏夹/世界书/快捷消息/模式注入/Skills/记忆开关)。 */
    const val SETTINGS_ASSISTANT_RESOURCES = "settings_assistant_resources"

    /** P3-3: 权限引导页 — 三通道(无障碍/Shizuku/Root)权限配置向导。 */
    const val SETTINGS_PERMISSION_WIZARD = "settings_permission_wizard"

    /** v1.0.4: 通知监听页(从设置 → 助手与 Agent 进入,引导用户授权通知使用权 + 查看最近通知)。 */
    const val NOTIFICATION_LISTENER = "notification_listener"

    /** v1.0.4: AI 工具管理页(从设置 → 助手与 Agent 进入,展示 ToolRegistry 中全部工具)。 */
    const val TOOLS = "tools"

    /**
     * v1.0.20: 工具批准管理页 — 按风险等级分组列出所有工具,
     * 每个工具可设置三档策略(ALWAYS_ALLOW / ALWAYS_DENY / ASK_EVERY_TIME)。
     *
     * 从"设置 → 聊天 → 工具调用批准"或"设置 → 助手与 Agent → AI 工具"二级入口进入。
     */
    const val TOOLS_SETTINGS = "tools_settings"

    /** v1.0.80: UI 自动化权限设置(三层梯度: 无障碍/Shell/Root)。 */
    const val SETTINGS_AUTOMATION = "settings_automation"

    /**
     * v1.30: 构造群聊详情页路由(带 chatId 参数)。
     *
     * @param chatId 群聊 id
     * @return 形如 "group_chat_detail/{chatId}" 的路由字符串
     */
    fun groupChatDetailRoute(chatId: String) = "$GROUP_CHAT_DETAIL/$chatId"



    /**
     * 构造 HTML 预览页路由(带 URL 编码的 html 参数)。
     *
     * 调用方需先用 [java.net.URLEncoder.encode] 编码 HTML 源码,
     * 避免特殊字符(&、?、#、/、空格)破坏 NavHost 路由匹配。
     *
     * @param encodedHtml 已经过 URLEncoder.encode 编码的 HTML 源码
     * @return 形如 "html_preview/{encodedHtml}" 的路由字符串
     */
    fun htmlPreviewRoute(encodedHtml: String) = "$HTML_PREVIEW/$encodedHtml"

}
