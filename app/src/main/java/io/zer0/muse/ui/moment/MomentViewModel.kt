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
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.images
import io.zer0.muse.data.moment.MomentGenerator
import io.zer0.muse.data.moment.MomentMessage
import io.zer0.muse.data.moment.MomentRepository
import io.zer0.muse.data.moment.images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * v1.0.72: 朋友圈 ViewModel。
 *
 * v1.0.73: 多助手(发布/点赞/评论)+ 发布带图 + 封面换图。
 * v1.0.74: 消息中心(赞/评列表 + 未读红点 + 横幅通知)+ 多图发布 + 个人主页数据。
 */
class MomentViewModel(
    application: Application,
    private val repository: MomentRepository,
    private val chatService: ChatService?,
    private val factStore: FactStore?,
    private val generator: MomentGenerator,
    private val assistantRepository: AssistantRepository,
) : AndroidViewModel(application) {

    private val TAG = "MomentVM"

    data class MomentUiState(
        val moments: List<MomentEntity> = emptyList(),
        val comments: Map<String, List<MomentCommentEntity>> = emptyMap(),
        val isLoading: Boolean = true,
        /** v1.0.73: 用户资料(朋友圈头像/名字同步个人资料)。 */
        val userAvatarUri: String? = null,
        val userName: String = "我",
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
    )

    private val _state = MutableStateFlow(MomentUiState())
    val state: StateFlow<MomentUiState> = _state.asStateFlow()

    private val settings: SettingsRepository by lazy {
        org.koin.java.KoinJavaComponent.get(SettingsRepository::class.java)
    }

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val moments = withContext(Dispatchers.IO) {
                repository.getAll(100)
            }
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
            _state.value = MomentUiState(
                moments = moments,
                comments = commentsMap,
                isLoading = false,
                userAvatarUri = profile?.avatarUri?.takeIf { it.isNotBlank() },
                userName = profile?.userNickName?.takeIf { it.isNotBlank() } ?: "我",
                assistants = assistants,
                coverImage = cover,
                wallpaper = wallpaper,
                messages = messages,
                unreadMomentsCount = moments.count { it.createdAt > lastRead },
                unreadMessagesCount = messages.count { it.createdAt > msgLastRead },
            )
        }
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

    /** 用户发布(可带多图)。发布后随机助手点赞 + 评论,横幅通知。 */
    fun publish(content: String, images: List<String>) {
        if (content.isBlank() && images.isEmpty()) return
        viewModelScope.launch {
            val moment = repository.insertUserMoment(content.trim(), images)
            if (moment != null) {
                load()
                reactToUserMoment(moment)
            }
        }
    }

    /** 用户发布后:随机 1-2 个助手点赞 + 1 个助手评论(评论看图 VLM)。 */
    private suspend fun reactToUserMoment(moment: MomentEntity) {
        val assistants = resultOf { assistantRepository.getAll() }.getOrNull() ?: emptyList()
        if (assistants.isEmpty()) return

        // 随机 1-2 个助手点赞
        val likers = assistants.shuffled(Random).take(Random.nextInt(1, 3))
        var updated = moment
        likers.forEach { liker ->
            updated = repository.likeBy(
                updated,
                likerType = "assistant",
                likerId = liker.id,
                likerName = liker.name,
            )
        }
        if (updated.likes != moment.likes) {
            _state.value = _state.value.copy(
                moments = _state.value.moments.map { if (it.id == moment.id) updated else it },
            )
        }

        // 1 个助手评论(带图时 VLM 看图;刚发动态必回,不选择性跳过)
        val commenter = assistants[Random.nextInt(assistants.size)]
        val reply = generator.generateReply(
            momentContent = moment.content,
            userComment = "(看了你的动态)",
            assistant = commenter,
            images = moment.images().take(4),
            allowSkip = false,
        )
        if (!reply.isNullOrBlank()) {
            val comment = repository.insertComment(
                momentId = moment.id,
                sender = "assistant",
                content = reply,
                senderId = commenter.id,
                senderName = commenter.name,
            )
            updateComments(moment.id, comment)
        }

        // 横幅通知(延迟一点,让用户先看到动态发出去)
        kotlinx.coroutines.delay(2500)
        val likeText = if (likers.isNotEmpty()) "${likers.joinToString("、") { it.name }} 赞了你" else ""
        val commentText = if (!reply.isNullOrBlank()) "${commenter.name} 评论了你" else ""
        val text = listOf(likeText, commentText).filter { it.isNotBlank() }.joinToString(" · ")
        if (text.isNotBlank()) {
            _state.value = _state.value.copy(banner = text)
        }
    }

    /** 点赞/取消点赞(用户身份)。 */
    fun toggleLike(moment: MomentEntity) {
        viewModelScope.launch {
            val (updated, liked) = repository.toggleLike(
                moment,
                likerType = "user",
                likerId = "user",
                likerName = "我",
            )
            _state.value = _state.value.copy(
                moments = _state.value.moments.map { if (it.id == moment.id) updated else it },
            )
        }
    }

    /** 用户评论(随机助手回复一轮)。 */
    fun addComment(moment: MomentEntity, text: String) {
        viewModelScope.launch {
            // 用户评论入列
            val userComment = repository.insertComment(moment.id, "user", text, senderId = null, senderName = "我")
            updateComments(moment.id, userComment)

            // 随机助手回复(失败不阻塞)
            val reply = generateReply(moment, text)
            if (reply != null) {
                val aiComment = repository.insertComment(
                    moment.id,
                    "assistant",
                    reply.first,
                    senderId = reply.second?.id,
                    senderName = reply.second?.name,
                )
                updateComments(moment.id, aiComment)
            }
        }
    }

    /** 删除动态。 */
    fun deleteMoment(moment: MomentEntity) {
        viewModelScope.launch {
            repository.deleteMoment(moment.id)
            _state.value = _state.value.copy(
                moments = _state.value.moments.filterNot { it.id == moment.id },
                comments = _state.value.comments - moment.id,
            )
        }
    }

    private fun updateComments(momentId: String, comment: MomentCommentEntity) {
        _state.value = _state.value.copy(
            comments = _state.value.comments.toMutableMap().apply {
                put(momentId, (this[momentId] ?: emptyList()) + comment)
            },
        )
    }

    /** 随机助手生成评论回复。返回 (文本, 助手)。 */
    private suspend fun generateReply(
        moment: MomentEntity,
        userComment: String,
    ): Pair<String, io.zer0.muse.data.assistant.AssistantEntity?>? {
        val assistants = resultOf { assistantRepository.getAll() }.getOrNull() ?: emptyList()
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

    /** 换朋友圈封面。 */
    fun setCoverImage(dataUri: String) {
        viewModelScope.launch {
            resultOf { settings.saveMomentsCoverImage(dataUri) }
                .onError { msg, t -> Logger.w(TAG, "保存封面失败: ${t?.message ?: msg}") }
            _state.value = _state.value.copy(coverImage = dataUri)
        }
    }

    /** 换小手机桌面壁纸。 */
    fun setWallpaper(dataUri: String) {
        viewModelScope.launch {
            resultOf { settings.saveMiniPhoneWallpaper(dataUri) }
                .onError { msg, t -> Logger.w(TAG, "保存壁纸失败: ${t?.message ?: msg}") }
            _state.value = _state.value.copy(wallpaper = dataUri)
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
