package io.zer0.muse

import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolRegistry
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * P2-3 拆域：ToolRegistry 与工具注册器、权限存储、浏览器自动化注册独立模块。
 */
val appToolModule = module {

    // Phase 5-H: 工具注册表(简化版 MCP 框架)
    // Phase 8.8: 传入 context 用于 Clipboard/UsageStats/Calendar 系统服务
    single { ToolRegistry(androidContext()) }
    // P1-3b 拆域: 文本/编码工具注册器(URL/Base64/哈希/UUID/随机数,从 ToolRegistry 抽出)
    single { io.zer0.muse.tools.EncodingToolsRegistrar(androidContext(), get()) }
    // P1-3b 拆域: 核心基础工具注册器(get_current_time/calculator/echo)
    single { io.zer0.muse.tools.CoreToolsRegistrar(androidContext(), get()) }
    // P1-3b 拆域: 天气工具注册器(get_weather)
    single { io.zer0.muse.tools.WeatherToolsRegistrar(androidContext(), get()) }
    // P1-3b 拆域: 剪贴板工具注册器(clipboard_read/write)
    single { io.zer0.muse.tools.ClipboardToolsRegistrar(androidContext(), get()) }
    // P1-3b 拆域: 网络/文本工具注册器(ping/dns/公网IP/json_pretty/密码)
    single { io.zer0.muse.tools.NetworkTextToolsRegistrar(androidContext(), get()) }
    // P1-3b 拆域: 定时提醒工具注册器(schedule/cancel/list_reminders)
    single { io.zer0.muse.tools.ReminderToolsRegistrar(androidContext(), get()) }
    // P1-3b 拆域: 日历工具注册器(calendar_today/add_calendar_event)
    single { io.zer0.muse.tools.CalendarToolsRegistrar(androidContext(), get()) }
    // P1-3b 拆域: 手机端工具注册器(12 个)
    single { io.zer0.muse.tools.PhoneToolsRegistrar(androidContext(), get()) }
    // P1-3b 拆域: 系统/设备工具注册器(24 个)
    single { io.zer0.muse.tools.SystemToolsRegistrar(androidContext(), get()) }
    // P1-3b 拆域: 资源库 + 快速记录工具注册器
    single { io.zer0.muse.tools.ResourceToolsRegistrar(androidContext(), get()) }
    single { io.zer0.muse.tools.QuickNoteToolsRegistrar(androidContext(), get()) }
    // P1-3b 拆域: 定时任务 + 翻译工具注册器
    single { io.zer0.muse.tools.ScheduledTaskToolsRegistrar(androidContext(), get()) }
    single { io.zer0.muse.tools.TranslateToolsRegistrar(get()) }
    // P1-3b 拆域: TTS 工具注册器(speak_text)
    single { io.zer0.muse.tools.TtsToolsRegistrar(androidContext(), get()) }

    // v1.137: 快速记录存储,供自动化任务和 UI 共享同一实例
    single { io.zer0.muse.tools.quicknote.QuickNoteStore(androidContext()) }

    // P3: 会话级工具权限模式持久化
    single { SessionPermissionStore(androidContext()) }

    // v1.0.20: 单工具审批策略持久化(DataStore)
    // 供 ToolsSettingsPage(koinInject)与 ToolPermissionResolver 共享同一实例
    // 修复 NoDefinitionFoundException:此前 ToolsSettingsPage 用 koinInject() 取 ToolConfigStore,
    // 但 Koin 中未注册,导致点击"为每个工具设置是否批准"按钮进入页面时 Compose 重组崩溃
    single { ToolConfigStore(androidContext()) }

    // P2-6: BrowserManager 浏览器自动化(Headless WebView,供 AI 工具调用)
    // 注:ToolRegistry 内部还会创建自己的 BrowserManager 实例供 AI 工具使用,
    // 此处注册的 BrowserManager 可供 UI 或其他消费者共享访问(如展示当前页 URL/Title/HTML 状态)
    single { io.zer0.muse.tools.BrowserManager(androidContext()) }
}