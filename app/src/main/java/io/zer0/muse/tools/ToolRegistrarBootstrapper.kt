package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.tools.defaultTool.UIToolsRegistrar

/**
 * 工具注册器启动引导。
 *
 * 各工具注册器在 Koin 中均为懒加载 single，只有被实例化时才会执行
 * `init { registerAll() }` 并把工具写入 [ToolRegistry]。
 * 本类在 App 启动时被注入一次，强制把所有注册器实例化，
 * 避免工具管理页只显示 ToolRegistry 内置的少量工具。
 */
class ToolRegistrarBootstrapper(
    private val toolRegistry: ToolRegistry,
    encodingToolsRegistrar: EncodingToolsRegistrar,
    coreToolsRegistrar: CoreToolsRegistrar,
    weatherToolsRegistrar: WeatherToolsRegistrar,
    clipboardToolsRegistrar: ClipboardToolsRegistrar,
    networkTextToolsRegistrar: NetworkTextToolsRegistrar,
    reminderToolsRegistrar: ReminderToolsRegistrar,
    calendarToolsRegistrar: CalendarToolsRegistrar,
    phoneToolsRegistrar: PhoneToolsRegistrar,
    systemToolsRegistrar: SystemToolsRegistrar,
    resourceToolsRegistrar: ResourceToolsRegistrar,
    quickNoteToolsRegistrar: QuickNoteToolsRegistrar,
    scheduledTaskToolsRegistrar: ScheduledTaskToolsRegistrar,
    translateToolsRegistrar: TranslateToolsRegistrar,
    ttsToolsRegistrar: TtsToolsRegistrar,
    agentToolsRegistrar: AgentToolsRegistrar,
    workspaceToolsRegistrar: WorkspaceToolsRegistrar,
    fileToolsRegistrar: FileToolsRegistrar,
    pdfVisionToolsRegistrar: PdfVisionToolsRegistrar,
    shellSandboxToolRegistrar: ShellSandboxToolRegistrar,
    uiToolsRegistrar: UIToolsRegistrar,
) {
    init {
        Logger.i(
            "ToolRegistrarBootstrapper",
            "工具注册器全部初始化完成，当前注册工具数=${toolRegistry.listTools().size}",
        )
    }
}
