package io.zer0.muse.ui

/**
 * 消息操作面板的互斥状态。
 *
 * 使用单一状态替代 showActionMenu/showExtendedMenu/showLanguageSubmenu 三个 Boolean，
 * 从而保证精简菜单、完整菜单和语言菜单不会同时出现。
 */
enum class MessageActionSurface {
    Hidden,
    Compact,
    Extended,
    TranslationLanguages,
}
