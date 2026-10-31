package io.zer0.muse.data.miniphone

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.UserProfile
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.moment.MiniContextBuilder
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.ui.speech.VoiceMessageArchiver
import io.zer0.muse.ui.speech.TtsSynthResult
import io.zer0.muse.ui.speech.VoiceSynthesizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File

/**
 * 小手机宿主实现的行为护栏。
 *
 * 生成端口、TTS 端口、语音存档端口全部注入假实现 —— 单测不连模型、不起 TTS 引擎，
 * 只验宿主自己做了哪些映射、哪些裁剪、失败时会不会白落盘。
 */
class AppMiniPhoneHostTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `observeCharacters 把助手映射成通讯录条目`() = runBlocking {
        val repo = mockk<AssistantRepository>(relaxed = true)
        every { repo.observeAll } returns flowOf(
            listOf(
                AssistantEntity(
                    id = "a1",
                    name = "阿绫",
                    avatarImageUrl = "/data/avatar/a1.png",
                    personaDefinition = "# 温柔的插画师\n喜欢夜里画画",
                ),
                AssistantEntity(id = "a2", name = "无头像助手"),
            ),
        )

        val characters = host(repo = repo).observeCharacters().first()

        assertEquals("两个助手应映射成两个角色", 2, characters.size)
        assertEquals("a1", characters[0].id)
        assertEquals("阿绫", characters[0].name)
        assertEquals("/data/avatar/a1.png", characters[0].avatarUri)
        assertEquals("一句话人设取人格定义首行", "温柔的插画师", characters[0].tagline)
        assertEquals("a2", characters[1].id)
        assertNull("没设头像应为 null", characters[1].avatarUri)
        assertEquals("没有人设给空串", "", characters[1].tagline)
    }

    @Test
    fun `observeProfile 取用户画像的昵称头像与签名`() = runBlocking {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.userProfileFlow } returns flowOf(
            UserProfile(
                userNickName = " 子奇 ",
                avatarUri = "/data/avatar/me.png",
                bio = "夜里写代码的人",
            ),
        )

        val profile = host(settings = settings).observeProfile().first()

        assertEquals("子奇", profile.nickname)
        assertEquals("/data/avatar/me.png", profile.avatarUri)
        assertEquals("夜里写代码的人", profile.signature)
        assertEquals("壁纸暂不接宿主", "", profile.wallpaperUri)
        assertEquals("封面暂不接宿主", "", profile.coverImageUri)
    }

    @Test
    fun `respond 把活动事件原样透传并把文本剥干净`() = runBlocking {
        val runner = mockk<MiniPhoneToolTurnRunner>()
        every { runner.reply(any(), any(), any()) } returns flowOf(
            MpReplyEvent.Activity(MpActivityKind.SEARCHING),
            MpReplyEvent.Text("<think>先想想</think>\"刚查到了。\""),
        )

        val events = host(runner = runner, contextBuilder = conversationBuilder())
            .respond("a1", listOf(MpChatTurn(MpActorType.USER, "帮我看看天气")))
            .toList()

        assertEquals(
            listOf(MpReplyEvent.Activity(MpActivityKind.SEARCHING), MpReplyEvent.Text("刚查到了。")),
            events,
        )
    }

    @Test
    fun `respond 用系统提示加历史拼出模型输入并滤掉系统行`() = runBlocking {
        val runner = mockk<MiniPhoneToolTurnRunner>()
        val captured = slot<List<UIMessage>>()
        every { runner.reply(capture(captured), any(), any()) } returns flowOf(MpReplyEvent.Text("在"))

        val events = host(runner = runner, contextBuilder = conversationBuilder())
            .respond(
                "a1",
                listOf(
                    MpChatTurn(MpActorType.USER, "在吗"),
                    MpChatTurn(MpActorType.SYSTEM, "对方没有回应"),
                    MpChatTurn(MpActorType.CHARACTER, "在"),
                    MpChatTurn(MpActorType.USER, "帮我看看天气"),
                ),
            )
            .toList()

        assertEquals(listOf(MpReplyEvent.Text("在")), events)
        val sent = captured.captured
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            sent.map { it.role },
        )
        assertEquals("系统提示来自人格组装链", "你是阿绫", sent.first().content)
        assertEquals("历史按原样接在后面", "帮我看看天气", sent.last().content)
    }

    @Test
    fun `模型没给出文本时 respond 发 Failed`() = runBlocking {
        val runner = mockk<MiniPhoneToolTurnRunner>()
        every { runner.reply(any(), any(), any()) } returns flowOf(MpReplyEvent.Text("<think>只有思考</think>"))

        val events = host(runner = runner, contextBuilder = conversationBuilder())
            .respond("a1", listOf(MpChatTurn(MpActorType.USER, "在吗")))
            .toList()

        assertEquals(listOf(MpReplyEvent.Failed), events)
    }

    @Test
    fun `respond 向人格组装链申请语音标记约定`() = runBlocking {
        val runner = mockk<MiniPhoneToolTurnRunner>()
        every { runner.reply(any(), any(), any()) } returns flowOf(MpReplyEvent.Text("在"))
        val voiceEnabled = slot<Boolean>()
        val builder = mockk<MiniContextBuilder>(relaxed = true)
        coEvery {
            builder.buildMessages(assistant = any(), userContent = any(), sessionId = any(), capture(voiceEnabled))
        } answers {
            listOf(
                UIMessage(role = MessageRole.SYSTEM, content = "你是阿绫"),
                UIMessage(role = MessageRole.USER, content = secondArg()),
            )
        }

        host(runner = runner, contextBuilder = builder)
            .respond("a1", listOf(MpChatTurn(MpActorType.USER, "在吗")))
            .toList()

        // 语音标记只在私信路径有意义:宿主必须主动要,否则模型永远不会输出标记
        assertTrue("私信回复应开启语音标记约定", voiceEnabled.captured)
    }

    @Test
    fun `composeMoment 走朋友圈口吻并且不注入动态评论文案`() = runBlocking {
        val generator = mockk<MiniPhoneTextGenerator>(relaxed = true)
        val captured = slot<String>()
        coEvery { generator.generate(any(), capture(captured), any()) } returns "<think>x</think>今天风很大，晾的衣服飞了。"

        val body = host(generator = generator).composeMoment("a1", "今天的天气")

        assertEquals("今天风很大，晾的衣服飞了。", body)
        assertTrue("素材应说明是发朋友圈: ${captured.captured}", captured.captured.contains("朋友圈"))
        assertTrue("素材应带上 hint: ${captured.captured}", captured.captured.contains("今天的天气"))
    }

    @Test
    fun `朋友圈与私信的素材都压住系统标记`() = runBlocking {
        val captured = slot<String>()
        val generator = mockk<MiniPhoneTextGenerator>(relaxed = true)
        coEvery { generator.generate(any(), capture(captured), any()) } returns "正文"
        val host = host(generator = generator)

        host.composeMoment("a1", null)
        assertBannedMarks(captured.captured)
        assertTrue("动态要说明是自己发的: ${captured.captured}", captured.captured.contains("不是回复任何人"))
        assertTrue("动态要求 30-80 字", captured.captured.contains("30-80"))

        host.composeProactiveMessage("a1", null)
        assertBannedMarks(captured.captured)
        assertTrue("私信要说明是自己开口: ${captured.captured}", captured.captured.contains("你主动开口说的话"))
        assertTrue("私信只要一两句", captured.captured.contains("一到两句话"))
    }

    @Test
    fun `角色 maxTokens 不会把一句话放成几百 token`() = runBlocking {
        val captured = mutableListOf<Int>()
        val generator = mockk<MiniPhoneTextGenerator>(relaxed = true)
        coEvery { generator.generate(any(), any(), capture(captured)) } returns "正文"
        val repo = mockk<AssistantRepository>(relaxed = true)
        every { repo.observeAll } returns flowOf(emptyList())
        coEvery { repo.getById("a1") } returns AssistantEntity(id = "a1", name = "阿绫", maxTokens = 4_000)
        coEvery { repo.getById("a2") } returns AssistantEntity(id = "a2", name = "阿远", maxTokens = 40)
        val host = host(repo = repo, generator = generator)

        host.composeMoment("a1", null)
        host.composeMoment("a2", null)

        assertEquals(listOf(MOMENT_TOKEN_CAP, 40), captured)
    }

    @Test
    fun `cleanOutput 剥掉行尾的心情括号`() {
        assertEquals("今天风很大，晾的衣服飞了。", composer().cleanOutput("今天风很大，晾的衣服飞了。\n（心情：平静）"))
        assertEquals("今天风很大。", composer().cleanOutput("今天风很大。(心情: 有点低）"))
    }

    @Test
    fun `cleanOutput 剥掉 MOOD 开头的整行`() {
        assertEquals("今天风很大。", composer().cleanOutput("MOOD: happy\n今天风很大。"))
        assertEquals("今天风很大。", composer().cleanOutput("今天风很大。\nMOOD：平静"))
    }

    @Test
    fun `cleanOutput 剥掉 mood 标签段`() {
        assertEquals("今天风很大。", composer().cleanOutput("今天风很大。[mood]平静[/mood]"))
        assertEquals("今天风很大。", composer().cleanOutput("<mood>emo=0.3</mood>今天风很大。"))
    }

    @Test
    fun `cleanOutput 对纯正文逐字符不变`() {
        val raw = "今天风很大，晾的衣服飞了。\n顺便把窗台擦了。\n\n还拍了一张照。"

        assertEquals(raw, composer().cleanOutput(raw))
    }

    @Test
    fun `角色不存在时 respond 发 Failed 且 composeMoment 返回 null`() = runBlocking {
        val repo = mockk<AssistantRepository>(relaxed = true)
        coEvery { repo.getById(any()) } returns null
        val generator = mockk<MiniPhoneTextGenerator>(relaxed = true)
        val runner = mockk<MiniPhoneToolTurnRunner>()
        val host = host(repo = repo, generator = generator, runner = runner)

        val events = host.respond("missing", listOf(MpChatTurn(MpActorType.USER, "在干嘛"))).toList()

        assertEquals(listOf(MpReplyEvent.Failed), events)
        assertNull(host.composeMoment("missing", null))
        coVerify(exactly = 0) { generator.generate(any(), any(), any()) }
        verify(exactly = 0) { runner.reply(any(), any(), any()) }
    }

    @Test
    fun `synthesizeVoice 在 TTS 返回 false 时不落盘并返回 null`() = runBlocking {
        val synthesizer = mockk<VoiceSynthesizer>()
        val store = mockk<VoiceMessageArchiver>(relaxed = true)
        coEvery { synthesizer.synthesizeTo(any(), any(), any()) } answers {
            secondArg<File>().writeBytes(ByteArray(TEMP_BYTES))
            TtsSynthResult.Failure("合成失败")
        }
        val cacheDir = tempDir.newFolder("cache")

        val result = voiceHost(synthesizer = synthesizer, store = store, cacheDir = cacheDir)
            .synthesizeVoice("晚安", "soft")

        assertNull(result)
        coVerify(exactly = 0) { store.persist(any(), any()) }
        assertTrue("临时文件应删干净", cacheDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `synthesizeVoice 成功时返回存档引用与时长并透传情绪`() = runBlocking {
        val synthesizer = mockk<VoiceSynthesizer>()
        val store = mockk<VoiceMessageArchiver>(relaxed = true)
        coEvery { synthesizer.synthesizeTo(any(), any(), "soft") } answers {
            secondArg<File>().writeBytes(ByteArray(TEMP_BYTES))
            TtsSynthResult.Success
        }
        coEvery { store.persist(any(), any()) } returns ARCHIVED_URI
        every { store.probeDurationMs(any()) } returns DURATION_MS
        val cacheDir = tempDir.newFolder("cache")

        val result = voiceHost(synthesizer = synthesizer, store = store, cacheDir = cacheDir)
            .synthesizeVoice("晚安", "soft")

        assertEquals(ARCHIVED_URI, result?.first)
        assertEquals(DURATION_MS, result?.second)
        coVerify(exactly = 1) { store.persist(any(), "晚安" + KEY_SEPARATOR + "soft") }
        assertTrue("临时文件应删干净", cacheDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `MomentChainTextGenerator 把素材交给 Persona 链路后再调 completeText`() = runBlocking {
        val chatService = mockk<ChatService>()
        val contextBuilder = mockk<MiniContextBuilder>(relaxed = true)
        val assistant = AssistantEntity(id = "a1", name = "阿绫")
        val builtMessages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "你是阿绫"),
            UIMessage(role = MessageRole.USER, content = "素材"),
        )
        coEvery { contextBuilder.buildMessages(assistant = assistant, userContent = "素材") } returns builtMessages
        val captured = slot<List<UIMessage>>()
        coEvery {
            chatService.completeText(messages = capture(captured), temperature = 0.8f, maxTokens = 160)
        } returns ChatCompletion(text = "好的")

        val text = MomentChainTextGenerator(chatService, contextBuilder, mockk(relaxed = true))
            .generate(assistant, "素材", 160)

        assertEquals("好的", text)
        assertSame("必须交出上下文构建器的消息列表", builtMessages, captured.captured)
    }

    @Test
    fun `MomentChainTextGenerator 用角色绑定的模型与采样参数`() = runBlocking {
        val chatService = mockk<ChatService>()
        val contextBuilder = mockk<MiniContextBuilder>(relaxed = true)
        coEvery { contextBuilder.buildMessages(assistant = any(), userContent = any()) } returns
            listOf(UIMessage(role = MessageRole.USER, content = "素材"))
        val boundModel = Model(id = "m-bound", providerId = "p1")
        val boundProvider = ProviderConfig(id = "p1", displayName = "供应商一", models = listOf(boundModel))
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.providersFlow } returns flowOf(listOf(boundProvider))
        every { settings.activeProviderIdFlow } returns flowOf("p1")
        val assistant = AssistantEntity(
            id = "a1",
            name = "阿绫",
            modelId = "m-bound",
            providerId = "p1",
            temperature = 0.35f,
            topP = 0.9f,
            maxTokens = 400,
        )
        val capturedModel = slot<Model>()
        val capturedProvider = slot<ProviderConfig>()
        coEvery {
            chatService.completeText(
                messages = any(),
                model = capture(capturedModel),
                providerConfig = capture(capturedProvider),
                temperature = 0.35f,
                topP = 0.9f,
                maxTokens = 160,
            )
        } returns ChatCompletion(text = "好的")

        val text = MomentChainTextGenerator(chatService, contextBuilder, settings)
            .generate(assistant, "素材", 160)

        assertEquals("好的", text)
        assertEquals("模型来自角色绑定", "m-bound", capturedModel.captured.id)
        assertEquals("供应商来自角色绑定", "p1", capturedProvider.captured.id)
    }

    @Test
    fun `MomentChainTextGenerator 在绑定模型不存在时回退默认模型`() = runBlocking {
        val chatService = mockk<ChatService>()
        val contextBuilder = mockk<MiniContextBuilder>(relaxed = true)
        coEvery { contextBuilder.buildMessages(assistant = any(), userContent = any()) } returns
            listOf(UIMessage(role = MessageRole.USER, content = "素材"))
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.providersFlow } returns flowOf(emptyList())
        every { settings.activeProviderIdFlow } returns flowOf(null)
        val assistant = AssistantEntity(id = "a1", name = "阿绫", modelId = "deleted-model", providerId = "gone")
        coEvery {
            chatService.completeText(
                messages = any(),
                model = null,
                providerConfig = null,
                temperature = 0.8f,
                topP = null,
                maxTokens = 160,
            )
        } returns ChatCompletion(text = "好的")

        val text = MomentChainTextGenerator(chatService, contextBuilder, settings)
            .generate(assistant, "素材", 160)

        assertEquals("好的", text)
        // 绑定解析不到时不传 model/providerConfig:交回给默认模型
        coVerify(exactly = 1) {
            chatService.completeText(
                messages = any(),
                model = null,
                providerConfig = null,
                temperature = 0.8f,
                topP = null,
                maxTokens = 160,
            )
        }
    }

    @Test
    fun `MomentChainTextGenerator 在模型未配置时返回 null`() = runBlocking {
        val contextBuilder = mockk<MiniContextBuilder>(relaxed = true)

        assertNull(MomentChainTextGenerator(null, contextBuilder, mockk(relaxed = true)).generate(null, "素材", 160))
    }

    @Test
    fun `宿主模块排在 miniphone 模块之后即覆盖占位实现`() {
        val host = host()
        val koinApp = koinApplication {
            modules(
                io.zer0.muse.miniphone.di.miniPhoneModule,
                module { single<MiniPhoneHost> { host } },
            )
        }

        try {
            assertSame("后加载的宿主定义必须胜出", host, koinApp.koin.get<MiniPhoneHost>())
        } finally {
            koinApp.close()
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    /** 生成链路的单元步骤:仓库与端口都用替身,只测拼素材与清输出。 */
    private fun composer(): MiniPhoneTextComposer = MiniPhoneTextComposer(
        assistantRepository = mockk(relaxed = true),
        textGenerator = mockk(relaxed = true),
        contextBuilder = mockk(relaxed = true),
    )

    /** 素材里必须出现的禁止项:模型不听话时也靠提示词压住。 */
    private fun assertBannedMarks(material: String) {
        BANNED_MARKS.forEach { mark ->
            assertTrue("素材缺少禁止项「$mark」: $material", material.contains(mark))
        }
        assertTrue("素材要求只输出正文: $material", material.contains("只输出正文"))
    }

    /** 只有一条用户消息时的人格组装结果:一句系统提示 + 这条消息。 */
    private suspend fun conversationBuilder(): MiniContextBuilder {
        val builder = mockk<MiniContextBuilder>(relaxed = true)
        coEvery {
            builder.buildMessages(assistant = any(), userContent = any(), sessionId = any(), voiceMessageEnabled = any())
        } answers {
            listOf(
                UIMessage(role = MessageRole.SYSTEM, content = "你是阿绫"),
                UIMessage(role = MessageRole.USER, content = secondArg()),
            )
        }
        return builder
    }

    private fun host(
        repo: AssistantRepository = defaultRepo(),
        settings: SettingsRepository = mockk(relaxed = true),
        generator: MiniPhoneTextGenerator = mockk(relaxed = true),
        contextBuilder: MiniContextBuilder = mockk(relaxed = true),
        runner: MiniPhoneToolTurnRunner = mockk(relaxed = true),
        cacheDir: File = tempDir.root,
    ): AppMiniPhoneHost = AppMiniPhoneHost(
        assistantRepository = repo,
        settingsRepository = settings,
        textGenerator = generator,
        contextBuilder = contextBuilder,
        toolTurnRunner = runner,
        voiceSynthesizer = mockk(relaxed = true),
        voiceStore = mockk(relaxed = true),
        cacheDir = cacheDir,
    )

    /** 语音用例专用的宿主:只关心 TTS 端口与存档端口的配合。 */
    private fun voiceHost(
        synthesizer: VoiceSynthesizer,
        store: VoiceMessageArchiver,
        cacheDir: File,
    ): AppMiniPhoneHost = AppMiniPhoneHost(
        assistantRepository = defaultRepo(),
        settingsRepository = mockk(relaxed = true),
        textGenerator = mockk(relaxed = true),
        contextBuilder = mockk(relaxed = true),
        toolTurnRunner = mockk(relaxed = true),
        voiceSynthesizer = synthesizer,
        voiceStore = store,
        cacheDir = cacheDir,
    )

    /** 默认角色仓库：只有一个助手，任何 id 都能取到。 */
    private fun defaultRepo(): AssistantRepository {
        val repo = mockk<AssistantRepository>(relaxed = true)
        val assistant = AssistantEntity(id = "a1", name = "阿绫")
        every { repo.observeAll } returns flowOf(listOf(assistant))
        coEvery { repo.getById(any()) } returns null
        coEvery { repo.getById("a1") } returns assistant
        return repo
    }

    private companion object {
        const val TEMP_BYTES = 64
        const val ARCHIVED_URI = "file:///data/voice_messages/abc.wav"
        const val DURATION_MS = 1_500L
        const val KEY_SEPARATOR = "\u0000"

        /** 与宿主内部的朋友圈场景上限一致:30-80 字不需要更多额度。 */
        const val MOMENT_TOKEN_CAP = 200

        val BANNED_MARKS = listOf(
            "MOOD",
            "情绪值",
            "好感度",
            "状态栏",
            "系统标签",
            "括号动作",
            "旁白",
            "时间戳",
            "引号",
            "#话题",
        )
    }
}
