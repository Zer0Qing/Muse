package io.zer0.muse.miniphone.ui.shell

import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons

/**
 * 小手机的四个主界面。
 *
 * id 是稳定标识:桌面图标、底部 tab、测试用的标签都从它派生,改文案不动 id。
 */
enum class MiniPhoneTab(
    val id: String,
    val title: String,
) {
    MESSAGES("messages", "消息"),
    CONTACTS("contacts", "通讯录"),
    DISCOVER("discover", "发现"),
    ME("me", "我"),
}

/**
 * 桌面图标表里的一项。
 *
 * 图标直接写在本模块的 [MiniPhoneIcons] 里,不放资源 id —— 图标是画出来的矢量,
 * 换主题色时靠 tint 染色,不需要按深浅色各放一套。
 */
data class MiniPhoneApp(
    val tab: MiniPhoneTab,
    val icon: ImageVector,
) {
    val id: String get() = tab.id

    val label: String get() = tab.title
}

/**
 * 桌面首屏的图标表。
 *
 * 首版只放四个主入口;后续相册、日记、天气接进来时按四列网格继续往这张表里加,
 * 桌面布局不需要改。
 */
internal object MiniPhoneApps {
    val launcher: List<MiniPhoneApp> = MiniPhoneTab.entries.map { MiniPhoneApp(it, iconOf(it)) }

    /** 桌面图标网格的列数。 */
    const val COLUMNS: Int = 4

    /** tab → 图标。用 when 而不是查表,少一个 tab 时编译期就会报出来。 */
    fun iconOf(tab: MiniPhoneTab): ImageVector = when (tab) {
        MiniPhoneTab.MESSAGES -> MiniPhoneIcons.Message
        MiniPhoneTab.CONTACTS -> MiniPhoneIcons.Contacts
        MiniPhoneTab.DISCOVER -> MiniPhoneIcons.Discover
        MiniPhoneTab.ME -> MiniPhoneIcons.Me
    }
}

/**
 * 界面元素的稳定标签,供自动化测试定位。
 *
 * 与文案分开:改标题、换语言都不会让测试失效。
 */
internal object MiniPhoneTags {
    const val PAGE_TITLE = "mp-page-title"
    const val HOME_BUTTON = "mp-home-button"
    const val STATUS_CLOCK = "mp-status-clock"
    const val EMPTY_THREADS = "mp-empty-threads"

    /** 空会话列表里的「去通讯录」按钮。 */
    const val EMPTY_THREADS_ACTION = "mp-empty-threads-action"
    const val EMPTY_CONTACTS = "mp-empty-contacts"

    /** 消息页顶栏的「建群」入口,与它打开的发起群聊页。 */
    const val GROUP_CREATE_ENTRY = "mp-group-create-entry"
    const val GROUP_CREATE_PAGE = "mp-group-create-page"
    const val GROUP_CREATE_BACK = "mp-group-create-back"
    const val GROUP_CREATE_DONE = "mp-group-create-done"
    const val GROUP_CREATE_COUNT = "mp-group-create-count"
    const val EMPTY_GROUP_CREATE = "mp-empty-group-create"
    const val THREAD_ROW = "mp-thread-row"
    const val MOMENTS_ENTRY = "mp-moments-entry"
    const val AUTO_MOMENT_SWITCH = "mp-auto-moment-switch"
    const val CHAT_PROACTIVE_SWITCH = "mp-chat-proactive-switch"
    const val CONTACT_HEADER = "mp-contact-header"
    const val CONTACT_INDEX = "mp-contact-index"

    /** 会话长按弹出的菜单与删除确认。 */
    const val THREAD_MENU = "mp-thread-menu"
    const val THREAD_DELETE_DIALOG = "mp-thread-delete-dialog"
    const val THREAD_DELETE_CONFIRM = "mp-thread-delete-confirm"
    const val THREAD_DELETE_CANCEL = "mp-thread-delete-cancel"

    /** 我页的三个入口:我的朋友圈、清空聊天记录、版本信息。 */
    const val MY_MOMENTS_ROW = "mp-my-moments-row"
    const val CLEAR_CHATS_ROW = "mp-clear-chats-row"
    const val CLEAR_CHATS_DIALOG = "mp-clear-chats-dialog"
    const val CLEAR_CHATS_CONFIRM = "mp-clear-chats-confirm"
    const val CLEAR_CHATS_CANCEL = "mp-clear-chats-cancel"
    const val VERSION_ROW = "mp-version-row"
    const val VERSION_LABEL = "mp-version-label"

    fun tab(tab: MiniPhoneTab): String = "mp-tab-${tab.id}"

    fun launcher(app: MiniPhoneApp): String = "mp-launcher-${app.id}"

    fun thread(threadId: String): String = "mp-thread-$threadId"

    fun unread(threadId: String): String = "mp-unread-$threadId"

    fun contactHeader(key: String): String = "mp-contact-header-$key"

    fun contact(characterId: String): String = "mp-contact-$characterId"

    fun contactIndex(key: String): String = "mp-contact-index-$key"

    /** 发起群聊页的一行与它的选择圈。 */
    fun groupPick(characterId: String): String = "mp-group-pick-$characterId"

    fun groupPickMark(characterId: String): String = "mp-group-pick-mark-$characterId"

    fun threadMenuAction(action: ThreadMenuAction): String = "mp-thread-menu-${action.name.lowercase()}"
}
