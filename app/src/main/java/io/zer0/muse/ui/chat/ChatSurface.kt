package io.zer0.muse.ui.chat

/**
 * 聊天页所处的界面形态。
 *
 * 为什么用枚举而不是继续堆 Boolean：
 * `ChatScreen` 已有 `isAgentMode` 参数，再加一个 `isMiniPhoneMode` 就会出现
 * 两个互斥且可能同时为 true 的开关，分支组合数翻倍且无法在类型层约束。
 * 这里把"形态"收敛成单一枚举，新增形态只需加一个 case，分支必须显式覆盖。
 *
 * - [FULL]：完整页面。任务列表 push 进来的普通对话详情页，自带返回岛与顶栏。
 * - [AGENT]：Agent Tab 嵌入模式。由 `HomeScreen` 提供顶栏与三点菜单，
 *   会话 id 走 `agentSessionId` 分支，与任务会话完全隔离。
 * - [MINI_PHONE]：小手机内嵌模式。微信风格顶栏（头像 + 名称 + 返回），
 *   会话 id 仍走 `currentSessionId`（小手机会话是普通 session，不是 agent session），
 *   返回回到小手机而不是主 App 会话列表。
 *
 * 注意：[MINI_PHONE] 只改外壳与导航，不改生成链路、工具装配、权限判定——
 * 那些由会话自身属性决定，换形态不应产生行为差异。
 */
enum class ChatSurface {
    FULL,
    AGENT,
    MINI_PHONE,
    ;

    /** 是否由外部容器提供顶栏（此时 `ChatScreen` 不画自己的顶栏）。 */
    val hidesOwnTopBar: Boolean get() = this == AGENT

    /** 是否属于小手机内嵌形态。 */
    val isMiniPhone: Boolean get() = this == MINI_PHONE
}
