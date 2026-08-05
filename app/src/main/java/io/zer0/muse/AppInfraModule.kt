package io.zer0.muse

import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.backup.BackupService
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.web.CompositeWebSearchService
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebSearchService
import io.zer0.muse.web.createWebSearchClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * P2-3 拆域：MCP / 视觉 / 备份 / 通知 / 更新 / Web / 记忆基础设施注册独立模块。
 */
val appInfraModule = module {

    // Phase 9.5 (M3): MCP server 注册�?管理多个 McpClient,桥接 ToolRegistry)
    single { io.zer0.muse.mcp.McpRegistry(get(), get(), androidContext()) }

    // Phase 5-I / Phase 7: 备份导出/导入服务(�?memory.db + facts.db)
    // Phase 8.9: 增加云备�?余额查询依赖

    // v1.135-A: 视觉辅助结果缓存(session 级 + sidecar 持久化)
    single { io.zer0.muse.vision.VisionCache(androidContext()) }

    // v1.25: 视觉辅助桥接器(让纯文本模型通过视觉模型"看到"图片)
    single { io.zer0.muse.vision.VisionBridge(get(), get(), get()) }

    single { BackupService(get(), get(), get(), get(), get()) }

    // Phase 8.9: 云备份服务(S3/WebDAV 派发)
    // v1.0.4 (P3-8): 移除 BalanceService Koin 注册 — 该类从未被业务代码调用,
    // ProviderSection.kt 内联实现了带本地化错误反馈的余额查询,BalanceService 为死代码,已删除。
    single { io.zer0.muse.backup.CloudBackupService(get(named("chat"))) }
    // Phase 8.9: CherryStudio/Chatbox 配置导入
    single { io.zer0.muse.importer.ConfigImporter(get()) }

    // Phase 8.10: 通知管理�?3 渠道:chat_completed/live_update/web_server)
    single { io.zer0.muse.notification.MuseNotificationManager(androidContext()) }

    // v1.133: GitHub Release 更新检查 — 复用 named("chat") OkHttpClient(已应用用户代理配置)
    single { io.zer0.muse.update.UpdateChecker(get(named("chat"))) }
    // v1.133: 更新通知器(协调 UpdateChecker + SettingsRepository + MuseNotificationManager)
    single { io.zer0.muse.update.UpdateNotifier(get(), get()) }

    // Phase 8.11: mDNS 服务发现(NSD 局域网服务注册)
    single { io.zer0.muse.web.MdnsService(androidContext()) }
    // Phase 8.11: 嵌入�?Web 服务�?Ktor CIO + JWT + mDNS)
    single { io.zer0.muse.web.WebServer(get(), get(), get(), get(), androidContext()) }

    // Phase 8.4: Web 搜索服务(独立 OkHttpClient,避免�?SSE 长连接互相影�?
    // Phase 8.5 修复:�?qualifier 区分;config 改为懒加�?避免主线�?runBlocking
    // v1.39: �?@Volatile 缓存而非 runBlocking,消除主线�?ANR
    single(named("webSearch")) {
        val settings = get<SettingsRepository>()
        val proxyConfig = settings.proxyConfigCache
        createWebSearchClient(proxyConfig)
    }
    single<WebSearchService> {
        // config 不在 Koin 初始化时同步读取(避免主线�?ANR),�?CompositeWebSearchService 懒加�?
        CompositeWebSearchService(get(named("webSearch")), WebSearchConfig())
    }

    // MemoryTicker: �?app 模块注册(�?SettingsRepository �?memory 开�?
    // v0.32: 透传 getConfig 闭包,让用户的 MemoryConfig(tokenBudget/decay/threshold �?
    //         真正影响记忆行为;闭包每次都读 settings.memoryConfigCache(@Volatile,零阻�?,
    //         而不是在构造时缓存,保证用户改完设置页立即生效�?
    single {
        val settings = get<SettingsRepository>()
        MemoryTicker(
            summaryManager = get(),
            compiler = get(),
            deepProcessor = get(),
            dailyStateDao = get(),
            getResetAt = { null },                         // Phase 3: 暂无记忆重置水印
            isMemoryEnabled = { settings.isMemoryEnabled() },
            scope = get(),
            getConfig = { settings.memoryConfigCache },
        )
    }
    // v1.0.51: 存量记忆迁移 — 升级后首次启动补跑历史 session 的 rollingSummary
    single {
        io.zer0.muse.data.MemoryBackfillMigration(
            sessionRepository = get(),
            memoryTicker = get(),
            settings = get(),
        )
    }

    // v1.0.52 P2-3: AI 驱动记忆自动管理(对话中实时提取实体/关系/合并/分类)
    single {
        io.zer0.memory.ai.MemoryAutoSaveScheduler(
            factDbProvider = get(),
            llmClient = get(),
            scope = get(),
        )
    }
}
