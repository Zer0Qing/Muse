package io.zer0.muse.miniphone.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.data.chat.MpConversationActions
import io.zer0.muse.miniphone.data.chat.MpGroupActions
import io.zer0.muse.miniphone.data.chat.auto.MpProactiveChatRunner
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.moment.MpMomentsRepository
import io.zer0.muse.miniphone.data.moment.auto.MpMomentAutoRunner
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import io.zer0.muse.miniphone.ui.chat.MiniPhoneVoicePlaybackController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 小手机 Koin 装配图的契约（`:memory` 的 `MemoryModuleGraphTest` 同款）。
 *
 * 为什么值得单独一类：装配错了**不会崩、也不会让任何业务测试变红** ——
 * `MuseApp` 把 `startKoin` 包在 `resultOf` 里，失败只标记安全模式，
 * 用户看到的是"功能都打不开"。这个模块里还有两条**写在注释里的单例契约**，
 * 一旦被改成 `factory` 就直接失效，而失效表现是用户可感知的：
 *
 *  - `MiniPhoneVoicePlaybackController`「模块内唯一一个播放器，所有语音条共用
 *    （同一时刻只播一条）」→ 两个实例 = **两条语音同时响**；
 *  - `MpMomentsRepository`「发布、点赞、评论、删除都走这一个读写口」→
 *    多实例 = 各自持有不同 DataStore/缓存视图，写完全后互相看不见。
 *
 * 占位宿主的"永远没有数据、不抛异常"也在这里钉住：它一旦被改成抛异常，
 * 所有没接宿主的入口会当场崩，而不是安静地显示空态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneModuleGraphTest {

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun `关键件都能解析（装配错了自己就会红在这里）`() {
        startKoinWith(miniPhoneModule)
        val koin = org.koin.java.KoinJavaComponent.getKoin()

        assertNotNull(koin.get<MiniPhoneDb>())
        assertNotNull(koin.get<MpMomentsRepository>())
        assertNotNull(koin.get<MpMomentAutoRunner>())
        assertNotNull(koin.get<MpProactiveChatRunner>())
        assertNotNull(koin.get<MpConversationActions>())
        assertNotNull(koin.get<MpGroupActions>())
        assertNotNull(koin.get<MiniPhoneHost>())
    }

    @Test
    fun `语音播放器是同一个实例 —— 否则两条语音会同时响`() {
        startKoinWith(miniPhoneModule)
        val koin = org.koin.java.KoinJavaComponent.getKoin()

        val first = koin.get<MiniPhoneVoicePlaybackController>()
        val second = koin.get<MiniPhoneVoicePlaybackController>()

        assertSame(
            "「模块内唯一一个播放器，所有语音条共用」是写在装配注释里的契约；" +
                "改成 factory 的表现就是两条语音条同时出声",
            first,
            second,
        )
    }

    @Test
    fun `朋友圈读写口与数据库都是同一个实例`() {
        startKoinWith(miniPhoneModule)
        val koin = org.koin.java.KoinJavaComponent.getKoin()

        assertSame(
            "发布/点赞/评论/删除必须走同一个读写口，否则两份视图会互相看不见对方的写",
            koin.get<MpMomentsRepository>(),
            koin.get<MpMomentsRepository>(),
        )
        assertSame(
            "两个 runner 若各自拿到不同 DB 实例，就会各持一份连接与缓存",
            koin.get<MiniPhoneDb>(),
            koin.get<MiniPhoneDb>(),
        )
        assertSame(
            "自动发动态的开关存储也要唯一（两份会各自缓存 lastRunAt）",
            koin.get<MpMomentAutoStore>(),
            koin.get<MpMomentAutoStore>(),
        )
    }

    @Test
    fun `占位宿主给的是空态而不是异常`() {
        startKoinWith(miniPhoneModule)
        val koin = org.koin.java.KoinJavaComponent.getKoin()
        val host = koin.get<MiniPhoneHost>()

        runBlocking {
            assertEquals(emptyList<Any>(), host.observeCharacters().first())
            assertEquals("", host.observeProfile().first().nickname)
            assertEquals(null, host.composeMoment("c1", null))
            assertEquals(null, host.composeProactiveMessage("c1", null))
            assertEquals(null, host.synthesizeVoice("你好", null))
            assertEquals(null, host.transcribeVoice(ByteArray(0)))
            // 没有识别能力时界面要能问出来（麦克风置灰靠它，而不是靠抛异常）
            assertEquals(false, host.supportsVoiceTranscription())
            // respond 必须给出可判定的失败事件
            val event = host.respond("c1", emptyList()).first()
            assertTrue("占位宿主应回 Failed，实际 $event", event is io.zer0.muse.miniphone.api.MpReplyEvent.Failed)
        }
        assertEquals("占位宿主没有宿主应用可报版本", "", host.appVersionName())
    }

    @Test
    fun `宿主覆盖靠后加载生效 —— 顺序反了会静默回落到占位实现`() {
        val fake = hostStub("9.9.9")
        val overrideModule = module { single<MiniPhoneHost> { fake } }

        // Koin 4.0 没有 `override<T>` DSL：同 key 定义以「模块加载顺序靠后者胜」表达覆盖
        // （见 AppKoinModule 里 miniPhoneHostModule 的注释）。所以生产里宿主模块必须排在
        // miniPhoneModule 之后 —— 顺序调换不报错、不崩溃，只是所有能力静默变回占位实现。
        startKoinWith(miniPhoneModule, overrideModule)
        val host = org.koin.java.KoinJavaComponent.getKoin().get<MiniPhoneHost>()
        assertSame("按文档顺序（占位在先、覆盖在后）覆盖必须生效", fake, host)
        assertEquals("9.9.9", host.appVersionName())

        stopKoin()

        // 反序：这一条就是给"以后有人手滑调顺序"准备的证据 —— 表现是拿回占位宿主
        startKoinWith(overrideModule, miniPhoneModule)
        val fallen = org.koin.java.KoinJavaComponent.getKoin().get<MiniPhoneHost>()
        assertTrue(
            "反序应当回落到占位宿主（这才说明顺序是隐式契约，值得被守卫住）",
            fallen !== fake,
        )
        assertEquals("占位宿主没有宿主应用可报版本", "", fallen.appVersionName())
    }

    private fun hostStub(version: String) = object : MiniPhoneHost {
        override fun observeCharacters() = kotlinx.coroutines.flow.flowOf(emptyList<io.zer0.muse.miniphone.api.MpCharacter>())
        override fun observeProfile() = kotlinx.coroutines.flow.flowOf(io.zer0.muse.miniphone.api.MpProfile(nickname = "真宿主"))
        override fun appVersionName() = version
        override fun respond(characterId: String, history: List<io.zer0.muse.miniphone.api.MpChatTurn>) =
            kotlinx.coroutines.flow.flowOf<io.zer0.muse.miniphone.api.MpReplyEvent>(io.zer0.muse.miniphone.api.MpReplyEvent.Failed)
        override suspend fun composeMoment(characterId: String, hint: String?) = "覆盖生效"
        override suspend fun composeProactiveMessage(characterId: String, hint: String?) = null
        override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null
        override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null
        override suspend fun supportsVoiceTranscription() = true
    }

    private fun startKoinWith(vararg moduleList: org.koin.core.module.Module) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.koin.core.context.startKoin {
            androidContext(context)
            modules(moduleList.toList())
        }
    }
}
