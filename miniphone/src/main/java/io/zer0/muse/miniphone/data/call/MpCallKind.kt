package io.zer0.muse.miniphone.data.call

/**
 * 通话种类：语音 / 视频。落库用 [wire]，界面与引擎用枚举本身。
 *
 * 公开而不是 internal：聊天页顶栏的拨号动作要把这个类型交给外壳，
 * 外壳 [io.zer0.muse.miniphone.ui.shell.MiniPhoneShell] 是模块对外的入口。
 */
enum class MpCallKind(val wire: String) {
    VOICE("voice"),
    VIDEO("video"),
    ;

    companion object {
        fun fromWire(wire: String): MpCallKind = entries.firstOrNull { it.wire == wire } ?: VOICE
    }
}
