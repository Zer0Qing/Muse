package io.zer0.muse.miniphone.ui.misc

/**
 * 衣橱、群红包、节日、铃声四个页面的稳定定位标签，供自动化测试使用。
 *
 * 每个页面一个嵌套对象，标签前缀与模块名对应，便于用 grep 定位；
 * 参数化标签统一用 `-$id` / `-$kind` 结尾，保证同一页不同条目互不撞名。
 */
internal object MiniPhoneMiscTags {
    /** 衣橱换装页。 */
    object Wardrobe {
        const val PAGE = "mp-wardrobe-page"
        const val EMPTY = "mp-wardrobe-empty"
        const val BACK = "mp-wardrobe-back"
        const val NAME_INPUT = "mp-wardrobe-name-input"
        const val URI_INPUT = "mp-wardrobe-uri-input"
        const val ADD = "mp-wardrobe-add"
        const val LIST = "mp-wardrobe-list"

        fun character(id: String): String = "mp-wardrobe-character-$id"

        fun kind(kind: String): String = "mp-wardrobe-kind-$kind"

        fun item(id: String): String = "mp-wardrobe-item-$id"

        fun equip(id: String): String = "mp-wardrobe-equip-$id"

        fun delete(id: String): String = "mp-wardrobe-delete-$id"
    }

    /** 群红包页。 */
    object RedPacket {
        const val PAGE = "mp-redpacket-page"
        const val EMPTY = "mp-redpacket-empty"
        const val BACK = "mp-redpacket-back"
        const val AMOUNT_INPUT = "mp-redpacket-amount-input"
        const val NOTE_INPUT = "mp-redpacket-note-input"
        const val SEND = "mp-redpacket-send"

        fun packet(id: String): String = "mp-redpacket-packet-$id"

        fun claim(id: String): String = "mp-redpacket-claim-$id"
    }

    /** 节日与纪念日页。 */
    object Holiday {
        const val PAGE = "mp-holiday-page"
        const val EMPTY = "mp-holiday-empty"
        const val BACK = "mp-holiday-back"
        const val NAME_INPUT = "mp-holiday-name-input"
        const val DATE_INPUT = "mp-holiday-date-input"
        const val YEAR_INPUT = "mp-holiday-year-input"
        const val NOTE_INPUT = "mp-holiday-note-input"
        const val ADD = "mp-holiday-add"
        const val MODE_RECURRING = "mp-holiday-mode-recurring"
        const val MODE_ONCE = "mp-holiday-mode-once"
        const val LIST = "mp-holiday-list"

        fun event(id: String): String = "mp-holiday-event-$id"

        fun delete(id: String): String = "mp-holiday-delete-$id"
    }

    /** 通话铃声页。 */
    object Ringtone {
        const val PAGE = "mp-ringtone-page"
        const val EMPTY = "mp-ringtone-empty"
        const val BACK = "mp-ringtone-back"
        const val GENERAL = "mp-ringtone-general"
        const val NAME_INPUT = "mp-ringtone-name-input"
        const val URI_INPUT = "mp-ringtone-uri-input"
        const val ADD = "mp-ringtone-add"

        fun character(id: String): String = "mp-ringtone-character-$id"

        fun kind(kind: String): String = "mp-ringtone-kind-$kind"

        fun item(id: String): String = "mp-ringtone-item-$id"

        fun delete(id: String): String = "mp-ringtone-delete-$id"
    }
}
