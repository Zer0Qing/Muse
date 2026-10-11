package io.zer0.muse.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.ui.common.icons.MuseIcons

/**
 * v2.6.0: 消息快捷按钮全量动作池（Via 浏览器式定制菜单）。
 *
 * 设计：消息下方的快捷排不再是一组固定开关，而是「全量动作池 + 有序启用列表」——
 * 设置页（聊天设置 → 消息快捷按钮 → 自定义）里用户从池中挑选动作并排序，
 * 消息下方按该顺序渲染；行尾固定提供「更多」进入完整长按面板。
 *
 * 全量池 = 原六开关（复制/思考/翻译/分享/重新生成/删除）+ 长按「更多」面板全部动作
 * （引用/批注/委托/分支/多选/消息信息/收藏/朗读/编辑/转发）。
 * 每个动作的可显示条件（消息角色/内容状态）由渲染侧判定，
 * 这里只描述"是什么、长什么样、默认排位"。
 *
 * @param id 稳定标识，持久化到 ChatPreferences.quickActionOrder
 * @param defaultInRow 默认启用（未配置时的初始排位）
 */
enum class QuickAction(
    val id: String,
    val labelRes: Int,
    val icon: ImageVector,
    val defaultInRow: Boolean = false,
    val defaultRank: Int = Int.MAX_VALUE,
) {
    COPY("copy", io.zer0.muse.R.string.action_copy, MuseIcons.copy, defaultInRow = true, defaultRank = 0),
    REASONING("reasoning", io.zer0.muse.R.string.msg_info_reasoning, MuseIcons.atom, defaultRank = 10),
    TRANSLATE("translate", io.zer0.muse.R.string.chat_translate_action, MuseIcons.languages, defaultInRow = true, defaultRank = 20),
    SHARE("share", io.zer0.muse.R.string.chat_share_action, MuseIcons.share, defaultInRow = true, defaultRank = 30),
    REGENERATE("regenerate", io.zer0.muse.R.string.chat_regenerate_cd, MuseIcons.refresh, defaultInRow = true, defaultRank = 40),
    DELETE("delete", io.zer0.muse.R.string.chat_delete_message, MuseIcons.trash, defaultRank = 50),
    QUOTE("quote", io.zer0.muse.R.string.message_action_quote, MuseIcons.reply),
    ANNOTATE("annotate", io.zer0.muse.R.string.message_action_annotate, MuseIcons.edit),
    DELEGATE("delegate", io.zer0.muse.R.string.chat_delegate_action, MuseIcons.users),
    FORK("fork", io.zer0.muse.R.string.chat_fork_action, MuseIcons.gitMerge),
    MULTI_SELECT("multi_select", io.zer0.muse.R.string.chat_select_messages, MuseIcons.square),
    INFO("info", io.zer0.muse.R.string.msg_info_title, MuseIcons.info),
    FAVORITE("favorite", io.zer0.muse.R.string.chat_favorite_add, MuseIcons.star),
    TTS("tts", io.zer0.muse.R.string.chat_tts_play, MuseIcons.play),
    EDIT("edit", io.zer0.muse.R.string.action_edit, MuseIcons.edit),
    FORWARD("forward", io.zer0.muse.R.string.chat_forward_action, MuseIcons.swapHorizontal),
    ;

    companion object {
        /** 默认排布：未配置 quickActionOrder 时的回退（对应存量六开关默认值）。 */
        val DEFAULT_ORDER: List<QuickAction> =
            entries.filter { it.defaultInRow }.sortedBy { it.defaultRank }

        fun fromId(id: String): QuickAction? = entries.firstOrNull { it.id == id }
    }
}
