package io.zer0.muse.miniphone.di

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.chat.MpConversationActions
import io.zer0.muse.miniphone.data.chat.MpConversationStore
import io.zer0.muse.miniphone.data.chat.MpGroupActions
import io.zer0.muse.miniphone.data.chat.MpGroupChatStore
import io.zer0.muse.miniphone.data.chat.auto.MpProactiveChatRunner
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.media.miniPhoneImageStore
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.moment.MpMomentsRepository
import io.zer0.muse.miniphone.data.moment.auto.MpMomentAutoRunner
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import io.zer0.muse.miniphone.data.settings.miniPhoneMomentAutoStore
import io.zer0.muse.miniphone.ui.chat.MiniPhoneChatViewModel
import io.zer0.muse.miniphone.ui.chat.MiniPhoneVoicePlaybackController
import io.zer0.muse.miniphone.ui.moments.MiniPhoneMomentsViewModel
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShellViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 小手机模块的 Koin 装配。
 *
 * 数据库、DAO、以及 [MiniPhoneHost] 的占位实现。真实宿主能力由宿主应用用
 * `override<MiniPhoneHost> { ... }` 覆盖这里的占位定义。
 */
val miniPhoneModule: Module = module {

    // ── 数据库 ──
    single { MiniPhoneDb.create(androidContext()) }

    // ── DAO ──
    single { get<MiniPhoneDb>().profileDao() }
    single { get<MiniPhoneDb>().threadDao() }
    single { get<MiniPhoneDb>().threadMemberDao() }
    single { get<MiniPhoneDb>().messageDao() }
    single { get<MiniPhoneDb>().momentDao() }
    single { get<MiniPhoneDb>().momentLikeDao() }
    single { get<MiniPhoneDb>().momentCommentDao() }
    single { get<MiniPhoneDb>().diaryDao() }
    single { get<MiniPhoneDb>().albumDao() }

    // ── 图片落盘:选中的图复制进私有目录,库里只存路径 ──
    single { miniPhoneImageStore(androidContext()) }

    // ── 语音播放:模块内唯一一个播放器,所有语音条共用(同一时刻只播一条) ──
    single { MiniPhoneVoicePlaybackController() }

    // ── 朋友圈:发布、点赞、评论、删除都走这一个读写口 ──
    single { MpMomentsRepository(momentDao = get(), likeDao = get(), commentDao = get(), imageStore = get()) }

    // ── 后台自动发动态的开关与上次生成时间(模块自己的 DataStore,不动 Room schema) ──
    single<MpMomentAutoStore> { miniPhoneMomentAutoStore(androidContext()) }

    // ── 角色自发内容的素材:最近聊过什么、上一条发过什么、现在几点(动态与私信共用) ──
    single {
        MpCharacterMaterial(
            threadDao = get(),
            messageDao = get(),
            momentDao = get(),
        )
    }

    // ── 后台自动发动态:一轮的完整流程,Worker 只负责把它叫起来 ──
    single { MpMomentAutoRunner(host = get(), repository = get(), store = get(), material = get()) }

    // ── 角色主动私信:一轮的完整流程(判定 → 生成 → 落库 → 未读 +1) ──
    single { MpProactiveChatRunner(host = get(), threadDao = get(), messageDao = get(), store = get(), material = get()) }

    // ── 会话列表的写操作:置顶 / 清未读 / 删会话 / 清空 ──
    single<MpConversationActions> {
        MpConversationStore(db = get(), threadDao = get(), messageDao = get(), memberDao = get())
    }

    // ── 建群与群成员管理:建群/改名/加人/踢人/解散 ──
    single<MpGroupActions> {
        MpGroupChatStore(db = get(), threadDao = get(), memberDao = get(), messageDao = get())
    }

    // ── 宿主能力(占位,待宿主覆盖) ──
    single<MiniPhoneHost> { PlaceholderMiniPhoneHost() }

    // ── 界面状态 ──
    viewModel { MiniPhoneShellViewModel(threadDao = get(), store = get(), groupStore = get(), host = get(), settings = get()) }
    viewModel { params ->
        MiniPhoneMomentsViewModel(
            repository = get(),
            host = get(),
            settings = get(),
            material = get(),
            onlyMine = params.get(),
        )
    }
    viewModel { params ->
        MiniPhoneChatViewModel(
            threadId = params.get(),
            messageDao = get(),
            threadDao = get(),
            memberDao = get(),
            host = get(),
            imageStore = get(),
            groupStore = get(),
        )
    }
}

/**
 * 宿主实现接入前的占位:永远「没有数据」,不抛异常。
 */
private class PlaceholderMiniPhoneHost : MiniPhoneHost {
    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(emptyList())

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = ""))

    /** 占位宿主没有宿主应用可报版本。 */
    override fun appVersionName(): String = ""

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    /** 占位宿主没有识别能力:录音转写一律当作没听清。 */
    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    /** 占位宿主没有识别能力,界面应当把麦克风置灰。 */
    override suspend fun supportsVoiceTranscription(): Boolean = false
}
