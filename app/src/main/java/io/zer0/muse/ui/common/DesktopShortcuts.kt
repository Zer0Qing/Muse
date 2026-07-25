package io.zer0.muse.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration

/**
 * P2-13: 桌面端键盘快捷键定义。
 *
 * 仅在 [WindowWidthClass.Expanded] 且检测到物理键盘时启用。
 * 用 `Modifier.onKeyEvent` 拦截硬件键盘事件。
 *
 * 设计原则:
 *  - 不破坏 InputBar 已有的 `enterToSend` 行为(用户在设置中可切换"回车发送"或"Shift+回车发送")
 *  - 全局快捷键(Ctrl+K / Ctrl+N / Ctrl+, / Esc)在 MainActivity 根部拦截
 *  - 局部快捷键(Enter 发送 / Ctrl+Shift+C 复制回复)在 ChatScreen 内部拦截
 *  - 快捷键仅作用于硬件键盘场景(软键盘不触发),通过 [rememberHasPhysicalKeyboard] 门控
 *
 * 修饰键约定(对齐桌面应用习惯):
 *  - Ctrl 修饰:全局导航类快捷键(搜索 / 新建 / 设置)
 *  - Shift 修饰:与基本键组合产生反向行为(Shift+Enter = 换行,Enter = 发送)
 *  - Ctrl+Shift 修饰:辅助操作(复制最后回复)
 *  - 无修饰:Esc 关闭
 */
object DesktopShortcuts {
    /** Enter(无 Shift): 发送消息 — 仅在 `enterToSend=true` 时由 InputBar 自身处理 */
    val SEND_MESSAGE: Key = Key.Enter

    /** Shift+Enter: 换行(覆盖 enterToSend 行为,Shift 修饰表示换行而非发送) */
    val NEWLINE: Key = Key.Enter

    /** Ctrl+K: 聚焦搜索框(若无内嵌搜索框则打开外部搜索页) */
    val FOCUS_SEARCH: Key = Key.K

    /** Ctrl+N: 新建对话 */
    val NEW_CHAT: Key = Key.N

    /** Ctrl+,: 打开设置(对齐 VS Code / Slack 等桌面应用习惯) */
    val OPEN_SETTINGS: Key = Key.Comma

    /** Ctrl+Shift+C: 复制最后一条 AI 回复到剪贴板 */
    val COPY_LAST_REPLY: Key = Key.C

    /** Esc: 关闭对话框 / 退出当前页(等价于系统返回) */
    val CLOSE: Key = Key.Escape
}

/**
 * 检测当前是否有物理键盘连接。
 *
 * 通过 [Configuration.keyboard] 判断:
 *  - [Configuration.KEYBOARD_NOKEYS]:无物理键盘(纯触摸设备)
 *  - [Configuration.KEYBOARD_QWERTY] / [Configuration.KEYBOARD_12KEY]:有物理键盘
 *
 * 在 ChromeOS / Android Desktop / 外接蓝牙键盘场景下,`Configuration.keyboard`
 * 会从 `KEYBOARD_NOKEYS` 切换为 `KEYBOARD_QWERTY`,且 [LocalConfiguration] 在配置
 * 变更(插拔键盘)时自动重组,因此 `remember(configuration)` 即可保证状态同步。
 *
 * 返回 [State] 而非裸 `Boolean`,便于在 `Modifier.onKeyEvent` 链中按需读取最新值。
 *
 * 注意:Android 11+ 系统在键盘可见性切换时会调用 Activity 的 `onConfigurationChanged`,
 * 但 Compose 的 `LocalConfiguration` 仅在 Activity 重启或 `applyResourceConfigurations`
 * 刷新后才更新。对大多数场景足够;若需更精细的实时检测,可结合 `WindowInsets`
 * 的 `ime` 状态,但不在本任务范围内。
 */
@Composable
fun rememberHasPhysicalKeyboard(): State<Boolean> {
    val configuration = LocalConfiguration.current
    return remember(configuration.keyboard) {
        mutableStateOf(configuration.keyboard != Configuration.KEYBOARD_NOKEYS)
    }
}

/**
 * P2-13: 桌面快捷键启用判定。
 *
 * 同时满足两个条件才启用快捷键:
 *  1. 窗口宽度分级为 [WindowWidthClass.Expanded](平板横屏 / 桌面)
 *  2. 检测到物理键盘(非纯触摸设备)
 *
 * 在手机竖屏 + 软键盘场景下,两个条件均不满足,快捷键自动禁用,
 * 不会与软键盘的 IME Action 冲突。
 */
@Composable
fun rememberDesktopShortcutsEnabled(): Boolean {
    val widthClass = rememberWindowWidthClass()
    val hasKeyboard by rememberHasPhysicalKeyboard()
    return widthClass == WindowWidthClass.Expanded && hasKeyboard
}
