package io.zer0.muse.miniphone.api

import io.zer0.muse.miniphone.data.db.MpActorType
import kotlinx.coroutines.flow.Flow

/**
 * 角色在小手机里的表现形态。
 *
 * 只带界面需要的四个字段;角色的完整数据留在宿主侧,本模块不持有。
 */
data class MpCharacter(
    val id: String,
    val name: String,
    val avatarUri: String? = null,
    val tagline: String? = null,
)

/**
 * 本机用户在小手机里的资料。
 *
 * 除昵称外都允许缺省(未设置头像/签名/壁纸时界面自行兜底)。
 */
data class MpProfile(
    val nickname: String,
    val avatarUri: String? = null,
    val signature: String? = null,
    val wallpaperUri: String? = null,
    val coverImageUri: String? = null,
)

/**
 * 角色「正在做什么」。
 *
 * 宿主执行工具时只交出这一枚枚举:工具名、参数、返回值都不出宿主,
 * 模块据此显示一句拟人化文案,不知道也不关心背后调了什么。
 */
enum class MpActivityKind {
    /** 在外部找东西(搜索、翻资料)。 */
    SEARCHING,

    /** 在回忆与用户有关的事。 */
    REMEMBERING,

    /** 在看日程、提醒。 */
    CALENDAR,

    /** 在看天气。 */
    WEATHER,

    /** 在翻笔记。 */
    NOTES,

    /** 其余说不清的动作。 */
    OTHER,
}

/** 一轮生成里发给模块的事件。 */
sealed interface MpReplyEvent {
    /** 角色此刻在做某件事;界面显示对应的拟人化文案,等文本落地后自动收起。 */
    data class Activity(val kind: MpActivityKind) : MpReplyEvent

    /** 角色说出的最终文本。 */
    data class Text(val content: String) : MpReplyEvent

    /**
     * 角色发来的一段语音。
     *
     * @param uri 宿主侧的语音存档引用(本地音频文件),界面据此播放
     * @param durationMs 时长(毫秒);0 表示宿主探测不出,界面显示为未知
     */
    data class Voice(val uri: String, val durationMs: Long) : MpReplyEvent

    /** 这一轮没有结果。 */
    data object Failed : MpReplyEvent
}

/** 会话里已经说过的一轮话;[sender] 只区分谁说,内容就是落库时的正文。 */
data class MpChatTurn(
    val sender: MpActorType,
    val content: String,
)

/**
 * 小手机与宿主之间的唯一出入口。
 *
 * 本模块不依赖宿主应用,需要角色、生成能力、语音能力时一律走这个接口,由宿主实现并注入。
 *
 * 约定:可失败的方法不向本模块抛异常。生成类方法失败发 [MpReplyEvent.Failed] 后正常结束,
 * 其余方法失败返回 `null`,调用方按「没有结果」处理即可。
 */
interface MiniPhoneHost {
    /** 通讯录里的角色列表。宿主侧无数据时返回空列表而不是失败。 */
    fun observeCharacters(): Flow<List<MpCharacter>>

    /** 本机用户资料。宿主侧尚未设置时返回一份空资料(昵称为空串)。 */
    fun observeProfile(): Flow<MpProfile>

    /**
     * 让某个角色就 [history] 接着往下说一轮。
     *
     * 冷流:每收集一次跑一轮完整生成。正常的流形是若干 [MpReplyEvent.Activity]
     * 之后跟一条或多条 [MpReplyEvent.Text] / [MpReplyEvent.Voice];没有结果时只有一条 [MpReplyEvent.Failed]。
     * 无论哪种情况,流都会正常结束,不抛异常。
     *
     * 一轮回复允许分多段发:宿主把模型输出里 `[语音]……[/语音]` 标记内的段落按出现顺序合成语音发
     * [MpReplyEvent.Voice],其余段落发 [MpReplyEvent.Text];标记本身不进任何文本,没有中文的段落
     * 不合成、按文本发。事件顺序与模型输出的先后一致,单轮最多 5 段语音。
     */
    fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent>

    /** 让某个角色发一条动态;失败返回 null。 */
    suspend fun composeMoment(characterId: String, hint: String?): String?

    /**
     * 生成一条角色主动发起的私信内容;无法生成时返回 null。
     *
     * 与 [composeMoment] 的区别在语义而不在链路:动态是「发给所有人看的近况」,
     * 私信是「直接对用户开口的一句话」—— 后者要求第一人称、一两句、不带旁白与解释。
     */
    suspend fun composeProactiveMessage(characterId: String, hint: String?): String?

    /**
     * 把文本合成语音。
     *
     * @return `first` 为音频文件 uri,`second` 为时长(毫秒);失败返回 null。
     */
    suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>?

    /**
     * 把用户录的一段 WAV 转成文字。
     *
     * 录音、编码、上传格式都由宿主决定 —— 模块只把字节交出去,不知道识别走的是哪个服务。
     * 不实现就等于不支挂语音输入:宿主必须显式给出结论,不留默认值。
     *
     * @return 去掉首尾空白的文本;识别不出来、未配置或请求失败都返回 null
     */
    suspend fun transcribeVoice(wavBytes: ByteArray): String?

    /**
     * 宿主应用的版本名(展示用,例如 `2.6.12`)。
     *
     * 只用于「我」页的版本信息。默认空串:没报版本的宿主(含测试里的替身)不显示这一段,
     * 界面不会因此填一个假版本号。
     */
    fun appVersionName(): String = ""

    /**
     * 当前 ASR 配置是否支持把整段音频转文字(录音输入依赖它)。
     *
     * 结论必须与 [transcribeVoice] 实际会不会走通一致:返回 true 之后用户按下麦克风、
     * 录完就应当是一次真实的请求,而不是一个注定失败的调用。界面据此把麦克风置灰。
     */
    suspend fun supportsVoiceTranscription(): Boolean
}
