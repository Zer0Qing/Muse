package io.zer0.muse.ui.moment

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.ai.ChatService
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentGenerator
import io.zer0.muse.data.moment.MomentInteractionEngine
import io.zer0.muse.data.moment.MomentMessage
import io.zer0.muse.data.moment.MomentRepository
import io.zer0.muse.data.moment.images
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * v1.0.72: 朋友圈 ViewModel。
 *
 * v1.0.73: 多助手(发布/点赞/评论)+ 发布带图 + 封面换图。
 * v1.0.74: 消息中心(赞/评列表 + 未读红点 + 横幅通知)+ 多图发布 + 个人主页数据。
 * v1.0.75: 互动引擎(助手异步点赞/评论)+ 防死循环。
 */
class MomentViewModel(
    application: Application,
    private val repository: MomentRepository,
    private val chatService: ChatService?,
    private val factStore: FactStore?,
    private val generator: MomentGenerator,
    private val assistantRepository: AssistantRepository,
    private val interactionEngine: MomentInteractionEngine,
    private val sessionRepository: SessionRepository,
) : AndroidViewModel(application) {

    private val TAG = "MomentVM"

    /**
     * U-24: 手动"立即生成动态"的反馈类型(UI 映射为 toast/snackbar 文案)。
     *  - SUCCESS: 已生成一条动态
     *  - NO_MATERIAL: 近期记忆为空,缺少可作素材的内容
     *  - LLM_FAILED: 有素材但 LLM 未产出(服务不可用/生成失败)
     */
    enum class MomentGenerateNotice { SUCCESS, NO_MATERIAL, LLM_FAILED }

    data class MomentUiState(
        val moments: List<MomentEntity> = emptyList(),
        val comments: Map<String, List<MomentCommentEntity>> = emptyMap(),
        val isLoading: Boolean = true,
        /** v1.0.73: 用户资料(朋友圈头像/名字同步个人资料)。 */
        val userAvatarUri: String? = null,
        val userName: String = "",
        /** v1.0.73: 助手 id → 实体(头像/名字/emoji)。 */
        val assistants: Map<String, io.zer0.muse.data.assistant.AssistantEntity> = emptyMap(),
        /** v1.0.73: 朋友圈封面背景(data URI/URL;null = 渐变)。 */
        val coverImage: String? = null,
        /** v1.0.73: 小手机桌面壁纸。 */
        val wallpaper: String? = null,
        /** v1.0.74: 消息列表(用户动态收到的赞/评,倒序)。 */
        val messages: List<MomentMessage> = emptyList(),
        /** v1.0.74: 朋友圈未读动态数。 */
        val unreadMomentsCount: Int = 0,
        /** v1.0.74: 消息未读数。 */
        val unreadMessagesCount: Int = 0,
        /** v1.0.74: 横幅通知文案(UI 显示后清空)。 */
        val banner: String? = null,
        /** 用户收藏的朋友圈动态 id。 */
        val favoriteMomentIds: Set<String> = emptySet(),
        /** U-24: 是否正在手动生成动态(驱动"立即生成"按钮 loading 态)。 */
        val isGeneratingNow: Boolean = false,
        /** U-24: 手动生成结果通知(UI 展示后通过 [consumeGenerateNotice] 清空)。 */
        val generateNotice: MomentGenerateNotice? = null,
        /** MEM-03: 加载失败错误信息(非 null 时列表显示错误态 + 重试)。 */
        val error: String? = null,
        /** v1.138: 小手机私信空间会话列表(仅 isMin iPhone=true)。 */
        val miniPhoneSessions: List<io.zer0.muse.data.session.SessionEntity> = emptyList(),
    )

    private val _state = MutableStateFlow(MomentUiState())
    val state: StateFlow<MomentUiState> = _state.asStateFlow()

    private val settings: SettingsRepository by lazy {
        org.koin.java.KoinJavaComponent.get(SettingsRepository::class.java)
    }
    // v1.xxx: 朋友圈调度器(手动"立即生成"走 generateNow,与定时生成同一套走法)
    private val scheduler: io.zer0.muse.schedule.MomentScheduler by lazy {
        org.koin.java.KoinJavaComponent.get(io.zer0.muse.schedule.MomentScheduler::class.java)
    }

    init {
        // v1.0.90: 收集互动引擎的结果,弹横幅（"XX 赞了你 · XX 评论了你"）
        viewModelScope.launch {
            interactionEngine.notices.collect { notice ->
                val context = getApplication<Application>()
                val parts = buildList {
                    if (notice.likerNames.isNotEmpty()) {
                        add(context.getString(R.string.moment_banner_liked_by, notice.likerNames.joinToString(context.getString(R.string.moment_separator_and))))
                    }
                    if (notice.commenterNames.isNotEmpty()) {
                        add(context.getString(R.string.moment_banner_commented_by, notice.commenterNames.joinToString(context.getString(R.string.moment_separator_and))))
                    }
                }
                val text = when {
                    parts.isNotEmpty() -> parts.joinToString(context.getString(R.string.moment_separator_dot))
                    notice.replyFailed -> context.getString(io.zer0.muse.R.string.err_moment_comment_failed)
                    else -> null
                }
                if (text != null) {
                    _state.value = _state.value.copy(banner = text)
                }
            }
        }
        viewModelScope.launch {
            // MEM-03: 实时流异常不再静默 — 记录日志,数据兜底走 load()
            runCatching {
                repository.observeMoments(100).collectLatest { moments ->
                    val comments = repository.getCommentsBatch(moments.map { it.id })
                    _state.value = _state.value.copy(
                        moments = moments,
                        comments = comments,
                    )
                }
            }.onFailure { e ->
                Logger.w(TAG, "朋友圈实时流中断: ${e.message}")
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            // MEM-03: 加载失败进入错误态(UI 可重试),不再静默卡 loading
            resultOf { withContext(Dispatchers.IO) { repository.getAll(100) } }
                .onSuccess { moments -> loadSuccess(moments) }
                .onError { msg, t ->
                    Logger.w(TAG, "朋友圈加载失败: ${t?.message ?: msg}")
                    _state.value = _state.value.copy(isLoading = false, error = msg)
                }
        }
    }

    private suspend fun loadSuccess(moments: List<MomentEntity>) {
        // 审计修复 (6.6): 批量加载评论(一次查询替代 N+1)
        val commentsMap = repository.getCommentsBatch(moments.map { it.id })
        // 用户资料 + 助手列表 + 封面 + 壁纸 + 消息
        val profile = resultOf { settings.getUserProfile() }.getOrNull()
        val assistants = resultOf { assistantRepository.getAll() }.getOrNull()
            ?.associateBy { it.id } ?: emptyMap()
        val cover = resultOf { settings.momentsCoverImageFlow.firstOrNull() }.getOrNull()
        val wallpaper = resultOf { settings.miniPhoneWallpaperFlow.firstOrNull() }.getOrNull()
        val rawMessages = repository.getUserMessages()
        // v1.0.74 fix: 消息头像用用户给助手选的头像(此前全默认渐变首字)
        val messages = rawMessages.map { msg ->
            if (msg.actorAvatar.isNullOrBlank()) {
                val avatar = assistants.values.firstOrNull { it.name == msg.actorName }
                    ?.avatarImageUrl?.takeIf { u -> u.isNotBlank() }
                if (avatar != null) msg.copy(actorAvatar = avatar) else msg
            } else {
                msg
            }
        }
        val lastRead = settings.momentsLastReadAtFlow.firstOrNull() ?: 0L
        val msgLastRead = settings.momentMessagesLastReadAtFlow.firstOrNull() ?: 0L
        val favoriteMomentIds = settings.momentFavoriteIdsFlow.firstOrNull() ?: emptySet()
        // U-24: load 会重建整个 UiState,需保留"立即生成"的 loading 态与结果通知,
        // 否则 generateNow 里异步调用的 load 会把它们抹掉
        val generatingNow = _state.value.isGeneratingNow
        val generateNotice = _state.value.generateNotice
        val senderUser = getApplication<Application>().getString(R.string.moment_sender_user)
        val miniPhoneSessions = resultOf { sessionRepository.getMiniPhoneSessions() }.getOrNull() ?: emptyList()
        _state.value = MomentUiState(
            moments = moments,
            comments = commentsMap,
            isLoading = false,
            userAvatarUri = profile?.avatarUri?.takeIf { it.isNotBlank() },
            userName = profile?.userNickName?.takeIf { it.isNotBlank() } ?: senderUser,
            assistants = assistants,
            coverImage = cover,
            wallpaper = wallpaper,
            messages = messages,
            unreadMomentsCount = moments.count { it.createdAt > lastRead },
            unreadMessagesCount = messages.count { it.createdAt > msgLastRead },
            favoriteMomentIds = favoriteMomentIds,
            isGeneratingNow = generatingNow,
            generateNotice = generateNotice,
            miniPhoneSessions = miniPhoneSessions,
        )
    }

    /** 进入朋友圈列表 = 已读(清除动态未读红点)。 */
    fun markMomentsRead() {
        viewModelScope.launch {
            resultOf { settings.markMomentsRead() }.onError { msg, t -> Logger.w(TAG, "mark read: ${t?.message ?: msg}") }
            _state.value = _state.value.copy(unreadMomentsCount = 0)
        }
    }

    /** 进入消息中心 = 已读。 */
    fun markMessagesRead() {
        viewModelScope.launch {
            resultOf { settings.markMomentMessagesRead() }.onError { msg, t -> Logger.w(TAG, "mark msg read: ${t?.message ?: msg}") }
            _state.value = _state.value.copy(unreadMessagesCount = 0)
        }
    }

    /** 清除横幅通知。 */
    fun consumeBanner() {
        _state.value = _state.value.copy(banner = null)
    }

    /** 立即生成一条 AI Moment(用户点"立即生成"触发,复用调度器的 generateNow)。
     *  U-24: 进入 loading,结束后通过 [MomentGenerateNotice] 细分反馈(成功/无素材/LLM 未产出)。
     *  v1.0.75: 手动生成不触发互动,避免刷屏。
     */
    fun generateNow() {
        if (_state.value.isGeneratingNow) return  // 防止连点重复生成
        viewModelScope.launch {
            _state.value = _state.value.copy(isGeneratingNow = true, generateNotice = null)
            // generateNow 返回 Boolean: true=已产出并写入,false=未产出(无素材或 LLM 失败)
            val ok = resultOf { scheduler.generateNow() }.getOrNull() ?: false
            if (ok) load()
            val notice = when {
                ok -> MomentGenerateNotice.SUCCESS
                // 无素材: 近期记忆为空,LLM 只能写空泛内容,判定为无法生成
                !hasMaterial() -> MomentGenerateNotice.NO_MATERIAL
                else -> MomentGenerateNotice.LLM_FAILED
            }
            _state.value = _state.value.copy(isGeneratingNow = false, generateNotice = notice)
        }
    }

    /** 是否存在可作朋友圈素材的近期记忆(FactStore)。读取失败不能误判为"无素材",按有素材处理。 */
    private suspend fun hasMaterial(): Boolean {
        val facts = resultOf { factStore?.getAll("main") }.getOrNull()
        if (facts == null) {
            // 记忆读取失败: 不据此误判"无素材",走 LLM_FAILED 分支
            Logger.w(TAG, "读取记忆素材失败,无法判定是否有素材")
            return true
        }
        return facts.isNotEmpty()
    }

    /** 清除"立即生成"结果通知(U-24,UI 展示后调用,防止重复弹)。 */
    fun consumeGenerateNotice() {
        _state.value = _state.value.copy(generateNotice = null)
    }

    /** 用户发布(可带多图)。发布后异步触发助手互动(v1.0.75)。 */
    fun publish(content: String, images: List<String>) {
        if (content.isBlank() && images.isEmpty()) return
        viewModelScope.launch {
            val moment = repository.insertUserMoment(content.trim(), images)
            if (moment != null) {
                load()
                interactionEngine.triggerOnUserPublish(moment, source = "user_publish")
            } else {
                // P2-12: 发布失败不再静默(此前 insertUserMoment 返回 null 时仅被无视)
                Logger.w(TAG, "用户发布动态失败: content=${content.take(30)}")
                io.zer0.muse.ui.common.feedback.MuseToast.show(
                    getApplication<Application>().getString(io.zer0.muse.R.string.err_moment_publish_failed),
                )
            }
        }
    }

    /** 点赞/取消点赞(用户身份)。 */
    fun toggleLike(moment: MomentEntity) {
        viewModelScope.launch {
            try {
                val senderUser = getApplication<Application>().getString(R.string.moment_sender_user)
                val (updated, _) = repository.toggleLike(
                    moment,
                    likerType = "user",
                    likerId = "user",
                    likerName = senderUser,
                )
                _state.update { state ->
                    state.copy(
                        moments = state.moments.map { item ->
                            if (item.id == moment.id) updated else item
                        },
                    )
                }
            } catch (t: Throwable) {
                if (t is kotlin.coroutines.cancellation.CancellationException) throw t
                Logger.w(TAG, "用户点赞失败: ${t.message}", t)
            }
        }
    }

    /** 用户评论(v1.0.75: 异步触发作者回复,不再同步阻塞)。 */
    fun addComment(moment: MomentEntity, text: String) {
        viewModelScope.launch {
            val senderUser = getApplication<Application>().getString(R.string.moment_sender_user)
            // 用户评论入列
            val userComment = repository.insertComment(moment.id, "user", text, senderId = null, senderName = senderUser)
                ?: run {
                    // P2-12: 评论失败不再静默
                    Logger.w(TAG, "用户评论失败: moment=${moment.id}")
                    io.zer0.muse.ui.common.feedback.MuseToast.show(
                        getApplication<Application>().getString(io.zer0.muse.R.string.err_moment_comment_failed),
                    )
                    return@launch
                }
            updateComments(moment.id, userComment)

            // v1.0.75: 异步触发作者回复(仅当作者是助手时)
            interactionEngine.triggerOnUserComment(moment, userComment = text)
        }
    }

    /** 删除动态。 */
    fun deleteMoment(moment: MomentEntity) {
        if (moment.senderType != "user" && moment.source != "user") return
        viewModelScope.launch {
            if (!repository.deleteMoment(moment.id)) {
                Logger.w(TAG, "用户删除动态失败: ${moment.id}")
                return@launch
            }
            val favorites = settings.momentFavoriteIdsFlow.firstOrNull() ?: emptySet()
            if (moment.id in favorites) settings.saveMomentFavoriteIds(favorites - moment.id)
            _state.update { state ->
                state.copy(
                    moments = state.moments.filterNot { item -> item.id == moment.id },
                    comments = state.comments - moment.id,
                )
            }
        }
    }

    /** 收藏/取消收藏动态。 */
    fun toggleFavorite(momentId: String) {
        viewModelScope.launch {
            try {
                val current = settings.momentFavoriteIdsFlow.firstOrNull() ?: emptySet()
                val next = if (momentId in current) current - momentId else current + momentId
                settings.saveMomentFavoriteIds(next)
                _state.update { it.copy(favoriteMomentIds = next) }
            } catch (t: Throwable) {
                if (t is kotlin.coroutines.cancellation.CancellationException) throw t
                Logger.w(TAG, "收藏动态失败: ${t.message}", t)
            }
        }
    }

    private fun updateComments(momentId: String, comment: MomentCommentEntity) {
        _state.update { state ->
            state.copy(
                comments = state.comments.toMutableMap().apply {
                    put(momentId, (this[momentId] ?: emptyList()) + comment)
                },
            )
        }
    }

    /** 随机助手生成评论回复。返回 (文本, 助手)。 */
    private suspend fun generateReply(
        moment: MomentEntity,
        userComment: String,
    ): Pair<String, io.zer0.muse.data.assistant.AssistantEntity?>? {
        // P2-6: 停用的助手不参与朋友圈评论回复
        val assistants = (resultOf { assistantRepository.getAll() }.getOrNull() ?: emptyList()).filter { it.enabled }
        // v1.0.74 fix: 用户主动评论是明确互动,必须回复(allowSkip=false),
        // 此前默认 true 导致模型输出"不回复"被过滤,用户"测试请回我一下"没回
        if (assistants.isEmpty()) {
            val text = generator.generateReply(moment.content, userComment, null, moment.images().take(4), allowSkip = false)
                ?: return null
            return text to null
        }
        val assistant = assistants[Random.nextInt(assistants.size)]
        val text = generator.generateReply(
            moment.content,
            userComment,
            assistant,
            moment.images().take(4),
            allowSkip = false,
        )
        if (text != null) return text to assistant
        Logger.w(TAG, "评论回复生成失败(用户主动评论未回): ${moment.content.take(20)}")
        return null
    }

    /** 换朋友圈封面。
     *  P3-7: save 返回实际落库值(data URI 已解码落文件,返回文件路径),
     *  立即展示用落库后的路径,而非原始 base64。
     */
    fun setCoverImage(dataUri: String) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                resultOf { settings.saveMomentsCoverImage(dataUri) }
                    .onError { msg, t -> Logger.w(TAG, "保存封面失败: ${t?.message ?: msg}") }
                    .getOrNull()
            }
            _state.value = _state.value.copy(coverImage = saved ?: dataUri)
        }
    }

    /** 换小手机桌面壁纸。 */
    fun setWallpaper(dataUri: String) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                resultOf { settings.saveMiniPhoneWallpaper(dataUri) }
                    .onError { msg, t -> Logger.w(TAG, "保存壁纸失败: ${t?.message ?: msg}") }
                    .getOrNull()
            }
            _state.value = _state.value.copy(wallpaper = saved ?: dataUri)
        }
    }

    /** 相册选图 → 压缩为 data URI(发布/封面/壁纸用)。 */
    suspend fun prepareImageDataUri(uri: Uri, context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            // v1.0.74 fix (前端审计 1.3): 先探测尺寸再降采样解码,避免 48MP 照片全尺寸
            // 解码瞬间 ~192MB 内存峰值 OOM(照搬 SmartImage 的 inSampleSize 范式)。
            val maxSide = 1280
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxSide || bounds.outHeight / sampleSize > maxSide) {
                sampleSize *= 2
            }
            val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?: return@withContext null
            // 压缩(采样后通常已接近目标尺寸,再做一次精确缩放)
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
            val scaled = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true,
                )
            } else {
                bitmap
            }
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            out.close()
            "data:image/jpeg;base64,$b64"
        } catch (t: Throwable) {
            if (t is kotlin.coroutines.cancellation.CancellationException) throw t
            Logger.w(TAG, "图片处理失败: ${t.message}")
            null
        }
    }
}
