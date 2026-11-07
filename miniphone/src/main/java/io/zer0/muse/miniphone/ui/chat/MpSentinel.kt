package io.zer0.muse.miniphone.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.zer0.muse.miniphone.R

/**
 * 消息正文与列表预览里代表"这一条是某种事情"而不是"这句话是谁说的"的占位记号。
 *
 * 这些记号会落库，所以它们必须是与语言无关的 ASCII 串：把「[图片]」「对方没有回应」
 * 这样的中文写进 content，切到别的界面语言时库里那行还是中文（走查 §14.3、§十五 三-N4）。
 * 记号本身从不显示——显示时经 [sentinelText] 换成当前语言的那句话。
 *
 * 存量数据里仍是旧的中文原句，读取时由 [normalize] 折回同一个记号，因此不需要迁移表。
 */
object MpSentinel {
    /** 图片消息的正文占位：真正的图在 mediaUri，这一列只给列表预览用。 */
    const val IMAGE: String = "[mp:image]"

    /** 语音消息的正文占位：真正的音频在 mediaUri，这一列只给列表预览用。 */
    const val VOICE: String = "[mp:voice]"

    /** 这一轮没人接话的系统提示。 */
    const val NO_REPLY: String = "[mp:no_reply]"

    /** 旧写法 → 记号。只用于读取侧兼容，新数据一律写记号。 */
    private val LEGACY: Map<String, String> = mapOf(
        "[图片]" to IMAGE,
        "[语音]" to VOICE,
        "对方没有回应" to NO_REPLY,
    )

    /** 把库里读出的一句话归一成记号；普通文本原样返回。 */
    fun normalize(stored: String): String = LEGACY[stored] ?: stored
}

/** 记号 → 当前界面的那句话；普通文本原样返回。 */
@Composable
fun sentinelText(stored: String): String = when (MpSentinel.normalize(stored)) {
    MpSentinel.IMAGE -> stringResource(R.string.mp_msg_image)
    MpSentinel.VOICE -> stringResource(R.string.mp_msg_voice)
    MpSentinel.NO_REPLY -> stringResource(R.string.mp_msg_no_reply)
    else -> stored
}
