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
    // P2-23: 媒体生成工具(图片/视频/二维码),原本由 ChatViewModel 在聊天页注册
    mediaGenToolsRegistrar: MediaGenToolsRegistrar,
    // v1.0.92: 消息渠道工具(外部 IM 发送)
    channelToolsRegistrar: ChannelToolsRegistrar,
    // v2.0: OAuth 连接器工具(connector_list / call_connector)
    connectorToolsRegistrar: ConnectorToolsRegistrar,
) {
    init {
        Logger.i(
            "ToolRegistrarBootstrapper",
            "工具注册器全部初始化完成，当前注册工具数=${toolRegistry.listTools().size}",
        )
        // 分类覆盖校验必须放在这里 —— 所有 Registrar 的 init 已执行完,
        // 此刻 toolRegistry 才持有全部内置工具(详见 ToolRegistry.assertBuiltInCategoryCoverage)。
        toolRegistry.assertBuiltInCategoryCoverage()
    }
}
