package io.zer0.muse.miniphone.ui.chat

/**
 * 聊天页元素的稳定标签,供自动化测试定位。
 *
 * 与文案分开:改名字、改提示语都不会让测试失效。
 */
internal object MiniPhoneChatTags {
    const val PEER_NAME = "mp-chat-peer-name"
    const val TOP_AVATAR = "mp-chat-top-avatar"
    const val BACK_BUTTON = "mp-chat-back"
    const val MESSAGE_LIST = "mp-chat-list"
    const val INPUT_FIELD = "mp-chat-input"
    const val SEND_BUTTON = "mp-chat-send"
    const val PICK_IMAGE = "mp-chat-pick-image"

    /** 语音输入键:空闲时开始录音,录音中变成「结束」。 */
    const val MIC_BUTTON = "mp-chat-mic"

    /** 录音中显示已录秒数的地方。 */
    const val RECORDING_SECONDS = "mp-chat-recording-seconds"

    /** 录音中的取消入口。 */
    const val CANCEL_RECORDING = "mp-chat-cancel-recording"

    /** 转写中的提示条。 */
    const val VOICE_STATUS = "mp-chat-voice-status"
    const val SELF_AVATAR = "mp-chat-self-avatar"
    const val IMAGE_VIEWER = "mp-chat-image-viewer"

    /** 图集查看器与它顶部的「3 / 9」。 */
    const val IMAGE_GALLERY = "mp-chat-image-gallery"
    const val IMAGE_GALLERY_COUNTER = "mp-chat-image-gallery-counter"
    const val TYPING = "mp-chat-typing"
    const val ACTIVITY = "mp-chat-activity"
    const val VOICE_PLAY_ICON = "mp-chat-voice-icon-play"
    const val VOICE_PAUSE_ICON = "mp-chat-voice-icon-pause"

    /** 群聊:顶栏人数、群成员页与页里的每一行。 */
    const val GROUP_TOP_BAR = "mp-chat-group-top-bar"
    const val GROUP_MEMBER_COUNT = "mp-chat-group-member-count"
    const val GROUP_MEMBERS_PAGE = "mp-chat-group-members"
    const val GROUP_MEMBERS_BACK = "mp-chat-group-members-back"
    const val GROUP_MEMBERS_COUNT = "mp-chat-group-members-count"

    /** 群名:一行入口、改名的弹层与它的输入框、保存、取消。 */
    const val GROUP_TITLE_ROW = "mp-chat-group-title-row"
    const val GROUP_TITLE_VALUE = "mp-chat-group-title-value"
    const val GROUP_TITLE_EDIT_DIALOG = "mp-chat-group-title-dialog"
    const val GROUP_TITLE_EDIT_FIELD = "mp-chat-group-title-field"
    const val GROUP_TITLE_EDIT_SAVE = "mp-chat-group-title-save"
    const val GROUP_TITLE_EDIT_CANCEL = "mp-chat-group-title-cancel"

    /** 加人:入口、选人页与它的返回/完成/人数行。 */
    const val GROUP_ADD_ENTRY = "mp-chat-group-add"
    const val GROUP_ADD_PAGE = "mp-chat-group-add-page"
    const val GROUP_ADD_BACK = "mp-group-add-member-back"
    const val GROUP_ADD_DONE = "mp-group-add-member-done"
    const val GROUP_ADD_COUNT = "mp-group-add-member-count"

    /** 移出成员:长按菜单、确认框。 */
    const val MEMBER_MENU_REMOVE = "mp-chat-member-menu-remove"
    const val MEMBER_REMOVE_DIALOG = "mp-chat-member-remove-dialog"
    const val MEMBER_REMOVE_CONFIRM = "mp-chat-member-remove-confirm"
    const val MEMBER_REMOVE_CANCEL = "mp-chat-member-remove-cancel"

    /** 解散:入口、确认框。 */
    const val GROUP_DISBAND_ENTRY = "mp-chat-group-disband"
    const val GROUP_DISBAND_DIALOG = "mp-chat-group-disband-dialog"
    const val GROUP_DISBAND_CONFIRM = "mp-chat-group-disband-confirm"
    const val GROUP_DISBAND_CANCEL = "mp-chat-group-disband-cancel"

    /** 长按消息弹出的操作浮层。 */
    const val MENU = "mp-chat-menu"

    /** 群里「正在输入」那一行上方的人名。 */
    const val TYPING_NAME = "mp-chat-typing-name"

    /** 离开底部后浮起的「N 条新消息」。 */
    const val NEW_MESSAGES_PILL = "mp-chat-new-messages"

    /** 删除前的那一次确认:对话框、确认键、取消键。 */
    const val DELETE_DIALOG = "mp-chat-delete-dialog"
    const val DELETE_CONFIRM = "mp-chat-delete-confirm"
    const val DELETE_CANCEL = "mp-chat-delete-cancel"

    fun message(id: String): String = "mp-chat-message-$id"

    fun bubble(id: String): String = "mp-chat-bubble-$id"

    fun image(id: String): String = "mp-chat-image-$id"

    fun imageBroken(id: String): String = "mp-chat-image-broken-$id"

    fun voice(id: String): String = "mp-chat-voice-$id"

    fun voiceUnavailable(id: String): String = "mp-chat-voice-unavailable-$id"

    fun timeDivider(at: Long): String = "mp-chat-time-$at"

    /** 群里气泡上方的发言人名字。 */
    fun senderName(messageId: String): String = "mp-chat-sender-$messageId"

    /** 群成员页的一行。 */
    fun member(characterId: String): String = "mp-chat-member-$characterId"

    fun expand(id: String): String = "mp-chat-expand-$id"

    /** 气泡旁的发送状态:发送中的浅字与发送失败的标记共用这一个标签。 */
    fun status(id: String): String = "mp-chat-status-$id"

    fun menuAction(action: ChatMenuAction): String = "mp-chat-menu-${action.name.lowercase()}"
}
