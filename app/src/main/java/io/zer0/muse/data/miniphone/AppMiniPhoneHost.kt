package io.zer0.muse.data.miniphone

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.BuildConfig
import io.zer0.muse.asr.OneShotAsrTranscriber
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.UserProfile
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.moment.MiniContextBuilder
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.transformer.stripThinkTags
import io.zer0.muse.ui.speech.VoiceMessageArchiver
import io.zer0.muse.ui.speech.TtsSynthResult
import io.zer0.muse.ui.speech.VoiceSynthesizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * 角色的旁路文本生成端口。
 *
 * [AppMiniPhoneHost] 只做「按角色组装素材」与「清理模型输出」两件事，
 * 一次完整的 LLM 调用交给本端口；生产实现见 [MomentChainTextGenerator]。
 * 抽成端口是为了单测能注入假实现，不必真发网络请求（与 [VoiceSynthesizer] 同一个理由）。
 */
fun interface MiniPhoneTextGenerator {

    /**
     * @param assistant 角色身份（人设 / 世界书 / 提示词注入绑定的来源）
     * @param material 交给模型的素材正文
     * @param maxTokens 本次生成的 token 上限
     * @return 模型原始输出（think 标签尚未剥离）；失败返回 null
     */
    suspend fun generate(assistant: AssistantEntity?, material: String, maxTokens: Int): String?
}

/**
 * [MiniPhoneTextGenerator] 的宿主实现：走小手机既有的一条生成链路。
 *
 * 与旁路生成链路内部同形 —— [MiniContextBuilder] 组装人格
 * （基础人格 / 世界书 / 记忆 / 用户画像 / 提示词注入 / think 标签剥离），再交给
 * [ChatService.completeText] 一次性取完整回复。旁路生成没有会话历史，所以不碰
 * `ChatViewModel` 与对话相关 Transformer。
 *
 * 采样参数跟角色走：角色绑了模型就用它自己的供应商与模型，温度 / topP 取角色设置，
 * 没绑或绑定的模型已被删掉时回落到默认模型。
 *
 * [chatService] 为空（模型未配置）时一律返回 null，不抛异常。
 */
class MomentChainTextGenerator(
    private val chatService: ChatService?,
    private val contextBuilder: MiniContextBuilder,
    private val settings: SettingsRepository,
) : MiniPhoneTextGenerator {

    override suspend fun generate(assistant: AssistantEntity?, material: String, maxTokens: Int): String? {
        val service = chatService ?: return null
        val messages = resultOf { contextBuilder.buildMessages(assistant = assistant, userContent = material) }
            .onError { msg, t -> Logger.w(TAG, "组装角色上下文失败: ${t?.message ?: msg}") }
            .getOrNull()
            ?: return null
        val binding = resolveBinding(assistant)
        return resultOf {
            service.completeText(
                messages = messages,
                model = binding?.model,
                providerConfig = binding?.providerConfig,
                temperature = assistant?.temperature?.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
                    ?: DEFAULT_TEMPERATURE,
                topP = assistant?.topP?.coerceIn(MIN_TOP_P, MAX_TOP_P),
                maxTokens = maxTokens,
            ).text
        }.onError { msg, t ->
            Logger.w(TAG, "旁路文本生成失败: ${t?.message ?: msg}")
        }.getOrNull()
    }

    /**
     * 角色自己绑的模型与供应商。
     *
     * 绑了 `providerId` 就精确命中那一家；只绑了 `modelId` 时先看激活供应商，再跨供应商找。
     * 解析不到（没绑 / 供应商或模型被删）返回 null，调用方交回给默认模型。
     */
    private suspend fun resolveBinding(assistant: AssistantEntity?): ModelBinding? {
        val modelId = assistant?.modelId?.takeIf { it.isNotBlank() } ?: return null
        val providerId = assistant.providerId?.takeIf { it.isNotBlank() }
        val providers = resultOf { settings.providersFlow.first() }
            .onError { msg, t -> Logger.w(TAG, "读取供应商列表失败: ${t?.message ?: msg}") }
            .getOrNull()
            .orEmpty()
        val model = findModel(providers, providerId, modelId)
        if (model == null) {
            Logger.w(TAG, "角色绑定的模型不可用(provider=${providerId ?: "-"}, model=$modelId)，回退默认模型")
            return null
        }
        val provider = providers.firstOrNull { it.id == model.providerId }
            ?: providerId?.let { id -> providers.firstOrNull { it.id == id } }
        if (provider == null) {
            Logger.w(TAG, "角色绑定的模型没有可用供应商(provider=${providerId ?: "-"})，回退默认模型")
            return null
        }
        return ModelBinding(model = model, providerConfig = provider)
    }

    /** 按 providerId → 激活供应商 → 全部供应商的顺序找模型。 */
    private suspend fun findModel(providers: List<ProviderConfig>, providerId: String?, modelId: String): Model? {
        if (providerId != null) {
            providers.firstOrNull { it.id == providerId }
                ?.models
                ?.firstOrNull { it.id == modelId }
                ?.let { return it }
        }
        val activeProviderId = resultOf { settings.activeProviderIdFlow.first() }.getOrNull()
        return providers.firstOrNull { it.id == activeProviderId }
            ?.models
            ?.firstOrNull { it.id == modelId }
            ?: providers.firstNotNullOfOrNull { provider -> provider.models.firstOrNull { it.id == modelId } }
    }

    private data class ModelBinding(val model: Model, val providerConfig: ProviderConfig)

    private companion object {
        private const val TAG = "MomentChainTextGenerator"
        private const val DEFAULT_TEMPERATURE = 0.8f
        private const val MIN_TEMPERATURE = 0f
        private const val MAX_TEMPERATURE = 2f
        private const val MIN_TOP_P = 0f
        private const val MAX_TOP_P = 1f
    }
}

/**
 * 一次文本生成的内部步骤：找角色、拼素材、调生成端口、清理输出。
 *
 * 这些成员原先都平铺在 [AppMiniPhoneHost] 里，宿主被接口方法、语音与映射挤得看不清主线；
 * 拆出来之后宿主只留接口方法与委托，这里也只管"生成一段文本"这一条链，不持有可写状态。
 *
 * 生成类方法一律不抛异常：失败归一成 null，由宿主决定发 [MpReplyEvent.Failed] 还是返回空。
 */
internal class MiniPhoneTextComposer(
    private val assistantRepository: AssistantRepository,
    private val textGenerator: MiniPhoneTextGenerator,
    private val contextBuilder: MiniContextBuilder,
) {

    /** 读取角色；读不到（不存在 / 异常）都返回 null。 */
    suspend fun findAssistant(characterId: String): AssistantEntity? =
        resultOf { assistantRepository.getById(characterId) }
            .onError { msg, t -> Logger.w(TAG, "读取角色失败: id=$characterId, ${t?.message ?: msg}") }
            .getOrNull()

    /** 动态素材：动态口吻，单条正文，不配图。 */
    suspend fun composeMoment(assistant: AssistantEntity, hint: String?): String? =
        generate(assistant, momentMaterial(hint), MAX_TOKENS_MOMENT)?.let(::cleanOutput)

    /** 主动私信素材：同一个角色人格、同一个生成链路，只把任务换成「直接开口说一句话」。 */
    suspend fun composeProactiveMessage(assistant: AssistantEntity, hint: String?): String? =
        generate(assistant, proactiveChatMaterial(hint), MAX_TOKENS_PROACTIVE_CHAT)?.let(::cleanOutput)

    /** 私信素材：把模块交来的对话拼成「人格系统提示 + 最近几轮」。 */
    suspend fun buildConversation(assistant: AssistantEntity, history: List<MpChatTurn>): List<UIMessage> {
        // 系统提示不是角色说过的话,图片/语音行没有文本 —— 两者都不进上下文
        val turns = history
            .filter { it.sender != MpActorType.SYSTEM && it.content.isNotBlank() }
            .takeLast(MAX_HISTORY_TURNS)
        val lastUser = turns.lastOrNull { it.sender == MpActorType.USER } ?: return emptyList()
        // 复用同一条人格组装链:世界书 / 提示词注入按最后一条用户消息取关键词;
        // 私信是唯一有语音出口的路径,顺带把语音标记约定加进系统指令
        val built = resultOf {
            contextBuilder.buildMessages(
                assistant = assistant,
                userContent = lastUser.content,
                voiceMessageEnabled = true,
            )
        }
            .onError { msg, t -> Logger.w(TAG, "组装角色上下文失败: ${t?.message ?: msg}") }
            .getOrNull()
            ?: return emptyList()
        return built.takeWhile { it.role == MessageRole.SYSTEM } + turns.map { it.toUIMessage() }
    }

    /** 剥掉 think 标签、系统标记与包裹引号;剥完为空视为没有结果。 */
    fun cleanOutput(raw: String): String? =
        stripSystemMarks(stripThinkTags(raw)).trim()
            .removeSurrounding("\"")
            .trim()
            .takeIf { it.isNotBlank() }

    /**
     * 剥掉模型自作主张加的系统标记:MOOD 整行、`[mood]…[/mood]` / `<mood>…</mood>` 段、
     * 行尾 `（心情：…）` 这类括号尾巴。只删明确的标记,不动正文。
     */
    private fun stripSystemMarks(text: String): String {
        if (text.isBlank()) return text
        var result = text
        if (MOOD_BLOCK.containsMatchIn(result)) {
            result = MOOD_BLOCK.replace(result, "")
        }
        if (MOOD_LINE.containsMatchIn(result)) {
            result = result.lineSequence()
                .filterNot { line -> MOOD_LINE.matches(line.trim()) }
                .joinToString("\n")
        }
        if (MOOD_TAIL.containsMatchIn(result)) {
            result = MOOD_TAIL.replace(result, "")
        }
        return result
    }

    /** 超时（[GENERATION_TIMEOUT_MS]）与失败都归一成 null，不向调用方抛。 */
    private suspend fun generate(assistant: AssistantEntity, material: String, maxTokens: Int): String? {
        // 实际额度取「角色 maxTokens」与「场景上限」的较小者:角色填了多少都不该把一句话放成几百 token
        val budget = assistant.maxTokens?.takeIf { it > 0 }?.let { minOf(it, maxTokens) } ?: maxTokens
        return resultOf {
            withTimeoutOrNull(GENERATION_TIMEOUT_MS) { textGenerator.generate(assistant, material, budget) }
        }.onError { msg, t ->
            Logger.w(TAG, "角色文本生成失败: ${t?.message ?: msg}")
        }.getOrNull()
    }

    private fun MpChatTurn.toUIMessage(): UIMessage = UIMessage(
        role = if (sender == MpActorType.USER) MessageRole.USER else MessageRole.ASSISTANT,
        content = content,
    )

    /** 动态素材：动态口吻，单条正文，不配图。 */
    private fun momentMaterial(hint: String?): String = buildString {
        appendLine("<moment_task>")
        appendLine("请以你自己的身份发一条朋友圈动态。")
        appendHint(hint)
        appendLine("</moment_task>")
        appendLine()
        appendLine("<style>")
        appendLine("- 口语自然，30-80 字，像真人在分享自己的生活。")
        appendLine("- 这不是回复任何人，是你自己发的动态。")
        appendLine("- 不要输出 MOOD、情绪值、好感度、状态栏、系统标签、括号动作、旁白、时间戳、引号、#话题。")
        appendLine("- 不要客服腔、不要 AI 自我介绍。")
        appendLine("- 只输出正文本身，不要前缀、解释或配图说明。")
        appendLine("</style>")
    }

    /** 主动私信素材:同一个角色人格、同一个生成链路,只把任务换成「直接开口说一句话」。 */
    private fun proactiveChatMaterial(hint: String?): String = buildString {
        appendLine("<chat_task>")
        appendLine("请主动给用户发一条私信。")
        appendHint(hint)
        appendLine("</chat_task>")
        appendLine()
        appendLine("<style>")
        appendLine("- 以角色第一人称，直接对用户开口，一到两句话。")
        appendLine("- 这不是回复任何人，是你主动开口说的话。")
        appendLine("- 不要输出 MOOD、情绪值、好感度、状态栏、系统标签、括号动作、旁白、时间戳、引号、#话题。")
        appendLine("- 不要 markdown、不要客服腔。")
        appendLine("- 只输出正文本身。")
        appendLine("</style>")
    }

    /** 模块拼好的素材原样接进去：上下文在前，最后一句是这一条要写什么。 */
    private fun StringBuilder.appendHint(hint: String?) {
        hint?.takeIf { it.isNotBlank() }?.lineSequence()?.forEach { line -> appendLine(line) }
    }

    private companion object {
        private const val TAG = "MiniPhoneTextComposer"

        /** 一句话生成的上限：超时当失败处理（旁路生成不该拖住界面）。 */
        private const val GENERATION_TIMEOUT_MS = 15_000L

        private const val MAX_TOKENS_MOMENT = 200

        /** 主动私信只要一两句:额度压小,避免写成一整封信。 */
        private const val MAX_TOKENS_PROACTIVE_CHAT = 120

        /** 私信只带最近几轮:更早的内容对一句话回复没有帮助,只会吃上下文预算。 */
        private const val MAX_HISTORY_TURNS = 20

        /** MOOD 开头的整行:模型把状态栏写成了正文的一部分。 */
        private val MOOD_LINE = Regex("""^\s*MOOD\s*[:：].*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

        /** `[mood]…[/mood]` 与 `<mood>…</mood>` 包裹的段。 */
        private val MOOD_BLOCK = Regex("""[\[<]\s*mood\s*[\]>][\s\S]*?[\[<]\s*/\s*mood\s*[\]>]""", RegexOption.IGNORE_CASE)

        /** 行尾/句末的「（心情：…）」括号尾巴。 */
        private val MOOD_TAIL = Regex("""[（(]\s*心情\s*[:：][^）)\n]*[）)]""")
    }
}

/**
 * 小手机宿主侧的实现：通讯录取现有助手、用户资料取设置里的用户画像，
 * 生成走 [MiniPhoneTextGenerator]，语音走宿主已有的 TTS 合成与语音存档。
 *
 * 边界（与 [MiniPhoneHost] 的约定一致）：
 * - 只读宿主数据，不写任何一张表；
 * - 生成类方法不抛异常：私信走 [MiniPhoneToolTurnRunner]，失败发 [MpReplyEvent.Failed]；
 * - 语音是唯一会落盘的产物，且只经 [VoiceMessageArchiver] 写进语音存档目录。
 *
 * 文本生成的具体步骤在 [MiniPhoneTextComposer] 里，本类只保留接口实现、语音与映射。
 *
 * @param cacheDir TTS 临时文件的落地目录（合成完成后必定删除）
 */
class AppMiniPhoneHost(
    private val assistantRepository: AssistantRepository,
    private val settingsRepository: SettingsRepository,
    textGenerator: MiniPhoneTextGenerator,
    contextBuilder: MiniContextBuilder,
    private val toolTurnRunner: MiniPhoneToolTurnRunner,
    private val voiceSynthesizer: VoiceSynthesizer,
    private val voiceStore: VoiceMessageArchiver,
    private val cacheDir: File,
) : MiniPhoneHost {

    private val textComposer = MiniPhoneTextComposer(
        assistantRepository = assistantRepository,
        textGenerator = textGenerator,
        contextBuilder = contextBuilder,
    )

    /** 用户录音的一次性转写:与流式识别共用同一份语音设置,不共用采集链路。 */
    private val voiceTranscriber = OneShotAsrTranscriber(settingsRepository)

    override fun observeCharacters(): Flow<List<MpCharacter>> =
        assistantRepository.observeAll
            .map { assistants -> assistants.map { it.toMpCharacter() } }
            .catch { t ->
                Logger.w(TAG, "读取角色列表失败，按空通讯录处理: ${t.message}")
                emit(emptyList())
            }

    override fun observeProfile(): Flow<MpProfile> =
        settingsRepository.userProfileFlow
            .map { it.toMpProfile() }
            .catch { t ->
                Logger.w(TAG, "读取用户资料失败，按空资料处理: ${t.message}")
                emit(MpProfile(nickname = ""))
            }

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        val assistant = textComposer.findAssistant(characterId)
        val messages = assistant?.let { textComposer.buildConversation(it, history) }
        if (assistant == null || messages.isNullOrEmpty()) {
            emit(MpReplyEvent.Failed)
            return@flow
        }
        toolTurnRunner.reply(
            messages = messages,
            temperature = assistant.temperature,
            maxTokens = assistant.maxTokens,
        ).collect { event ->
            if (event !is MpReplyEvent.Text) {
                emit(event)
                return@collect
            }
            // 文本要拆:标记内的段落合成语音,其余照常当文本发;剥完是空的就是「这句没说出口」
            val script = textComposer.cleanOutput(event.content)
            if (script == null) emit(MpReplyEvent.Failed) else emitVoiceScript(script) { synthesizeVoice(it, null) }
        }
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? {
        val assistant = textComposer.findAssistant(characterId) ?: return null
        return textComposer.composeMoment(assistant, hint)
    }

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? {
        val assistant = textComposer.findAssistant(characterId) ?: return null
        return textComposer.composeProactiveMessage(assistant, hint)
    }

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? {
        if (text.isBlank()) return null
        val tempFile = File(cacheDir, "$VOICE_TEMP_PREFIX${UUID.randomUUID()}$VOICE_TEMP_EXTENSION")
        return try {
            // 取消原样抛出（resultOf 内部重抛 CancellationException），其余异常归一成 null
            resultOf { synthesizeToArchive(text, emotion, tempFile) }
                .onError { msg, t -> Logger.w(TAG, "语音合成流程异常: ${t?.message ?: msg}") }
                .getOrNull()
        } finally {
            // 成功/失败/取消三条路径都删临时文件（存档已是独立副本）
            tempFile.delete()
        }
    }

    /**
     * 用户录的一段语音转文字。
     *
     * 只在用户已经配好语音识别（手填或跟随供应商）时才有结果;读设置、解析供应商、
     * 发请求、解析响应全在 [OneShotAsrTranscriber] 里,这里只做转发,不让模块看见任何协议细节。
     */
    override suspend fun transcribeVoice(wavBytes: ByteArray): String? =
        voiceTranscriber.transcribe(wavBytes)

    /**
     * 录音输入能不能用:判据与转写器实际会不会发请求完全一致。
     *
     * 注释与解析都复用 [OneShotAsrTranscriber]:不在这里重写一套条件,
     * 否则两边的「支持」与「真的能转」早晚会走偏。
     */
    override suspend fun supportsVoiceTranscription(): Boolean = voiceTranscriber.supportsTranscription()

    /** 宿主版本名:取自应用自己的构建信息,不在这里写死。 */
    override fun appVersionName(): String = BuildConfig.VERSION_NAME

    /** 合成 → 落盘 → 探测时长。任一步失败返回 null。 */
    private suspend fun synthesizeToArchive(text: String, emotion: String?, tempFile: File): Pair<String, Long>? {
        val outcome = resultOf { voiceSynthesizer.synthesizeTo(text, tempFile, emotion) }
            .onError { msg, t -> Logger.w(TAG, "语音合成失败: ${t?.message ?: msg}") }
            .getOrNull()
        // 回落系统音色也算合成出来了（音频可用），只有彻底失败才放弃
        if (outcome == null || outcome is TtsSynthResult.Failure) return null
        // key 稳定：同文本同情绪重复合成命中同一存档文件
        val uri = voiceStore.persist(tempFile, text + VOICE_KEY_SEPARATOR + (emotion ?: "")) ?: return null
        // 时长只给界面显示用，探测不出来（0）也照常返回
        return uri to voiceStore.probeDurationMs(tempFile)
    }

    private fun AssistantEntity.toMpCharacter(): MpCharacter = MpCharacter(
        id = id,
        name = name,
        avatarUri = avatarImageUrl.takeIf { it.isNotBlank() },
        tagline = taglineOf(),
    )

    /** 一句话人设：优先人格定义首行，其次整段人设首行；都没有就是空串。 */
    private fun AssistantEntity.taglineOf(): String {
        val source = personaDefinition.ifBlank { systemPrompt }
        return source.lineSequence()
            .map { it.trim().trimStart('#', '-', '*', '>').trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(TAGLINE_MAX_CHARS)
            ?: ""
    }

    private fun UserProfile.toMpProfile(): MpProfile = MpProfile(
        nickname = userNickName?.trim().orEmpty(),
        avatarUri = avatarUri?.takeIf { it.isNotBlank() },
        signature = bio?.trim()?.takeIf { it.isNotBlank() },
        // 壁纸与动态封面由小手机自己维护，宿主侧暂不提供
        wallpaperUri = "",
        coverImageUri = "",
    )

    private companion object {
        private const val TAG = "AppMiniPhoneHost"

        private const val TAGLINE_MAX_CHARS = 60

        /** 与既有语音消息一致的临时文件命名与扩展名。 */
        private const val VOICE_TEMP_PREFIX = "miniphone-voice-"
        private const val VOICE_TEMP_EXTENSION = ".wav"

        /** 语音存档 key 的分隔符：与 `send_voice` 同构，保证同文本同情绪命中同一份音频。 */
        private const val VOICE_KEY_SEPARATOR = "\u0000"
    }
}
