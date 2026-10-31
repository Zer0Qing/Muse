package io.zer0.muse.miniphone.ui.moments

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.moment.MP_LOCAL_ACTOR_ID
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.moment.MpMomentsRepository
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * 朋友圈页的状态与动作。
 *
 * 时间线是「已加载的一段窗口」,不是整表订阅:游标就是已加载条数,每页 [PAGE_SIZE] 条,
 * 与私信列表同一套做法。窗口只在 [timelineLock] 里挪动 —— 刷新、翻页同时进来时,
 * 先到的那次把窗口换掉,后到的按新的已加载条数继续接。
 *
 * 下拉刷新顺带让角色发一条动态:这是模块内两处角色驱动之一(另一处是 [io.zer0.muse.miniphone.work.MpMomentWorker]),
 * 失败就静默收手,界面上不出现错误、也不补一条空动态。手刷成功也算一次生成 ——
 * 时间戳写给后台任务的防抖账,否则 90 分钟内可能被定时任务再补一条。
 *
 * @param settings 后台任务的开关与防抖账本
 * @param material 生成素材：最近聊过什么、上一条发过什么、现在几点
 * @param onlyMine 只看自己发的动态:时间线只查本机用户的,下拉刷新也不再去叫角色生成
 * @param random 挑角色的随机源:每次刷新随机取一个启用角色,测试可钉死
 */
class MiniPhoneMomentsViewModel(
    private val repository: MpMomentsRepository,
    private val host: MiniPhoneHost,
    private val settings: MpMomentAutoStore,
    private val material: MpCharacterMaterial,
    private val onlyMine: Boolean = false,
    private val random: Random = Random.Default,
) : ViewModel() {

    private val mutableState = MutableStateFlow(MomentsUiState())

    val uiState: StateFlow<MomentsUiState> = mutableState.asStateFlow()

    /** 宿主给的角色名单(id → 角色),补作者头像用。 */
    private var characters: Map<String, MpCharacter> = emptyMap()

    private var selfName: String = ""

    /** 时间线窗口的闸:刷新与翻页不许同时改窗口。 */
    private val timelineLock = Mutex()

    init {
        observeHost()
        viewModelScope.launch { reloadFirstPage() }
    }

    /** 滚到底时加载更老的一页。没有更多、或上一页还在路上,直接忽略。 */
    fun loadMore() {
        val current = mutableState.value
        if (!current.hasMore || current.isLoadingMore) return
        mutableState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch { appendPage() }
    }

    /**
     * 下拉刷新:先让角色发一条动态,再重读首屏。
     *
     * 生成失败(没有角色、宿主返回 null、宿主抛了不该抛的异常)时什么都不插入,
     * 时间线照常重读一遍 —— 下拉动画收得干净,页面上也不会多出一条空动态。
     * 「我的朋友圈」模式下不生成:角色发的动态不属于这一页,这次生成只会在别处出现。
     */
    fun refresh() {
        if (mutableState.value.isRefreshing) return
        mutableState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                if (!onlyMine) generateMoment()
                reloadFirstPage()
            } finally {
                mutableState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /** 发一条自己的动态:文字 + 最多九张图;发布成功后插到时间线最上面。 */
    fun publish(content: String, imageUris: List<Uri>, visibleToSelf: Boolean = false) {
        viewModelScope.launch {
            val moment = repository.publish(
                content = content,
                imageUris = imageUris,
                authorName = selfName,
                visibleTo = if (visibleToSelf) MP_LOCAL_ACTOR_ID else "",
            ) ?: return@launch
            val row = buildRow(moment)
            mutableState.update { it.copy(moments = listOf(row) + it.moments) }
        }
    }

    /** 点赞或取消点赞,顺手把这一行的点赞人列表与计数换成库里的最新结果。 */
    fun toggleLike(momentId: String) {
        viewModelScope.launch {
            val liked = repository.toggleLike(momentId, MpActorType.USER, MP_LOCAL_ACTOR_ID, selfName)
            val likes = repository.likesOf(momentId)
            updateRow(momentId) { row ->
                row.copy(likes = likes.map { like -> like.likerName }, likedByMe = liked)
            }
        }
    }

    /**
     * 评论动态,或回复其中一条评论。
     *
     * [parentId] 为空是直接评论;[parentId] 指向某条评论时是回复它(回复的回复也一样,
     * 展示时会压回两级)。
     */
    fun comment(momentId: String, parentId: String?, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.addComment(
                momentId = momentId,
                parentId = parentId,
                senderType = MpActorType.USER,
                senderId = MP_LOCAL_ACTOR_ID,
                senderName = selfName,
                content = content,
            ) ?: return@launch
            val comments = repository.commentsOf(momentId)
            updateRow(momentId) { row -> row.copy(comments = buildCommentTree(comments)) }
        }
    }

    /** 删掉自己发的动态;别人的动态不在这里处理。 */
    fun deleteMoment(momentId: String) {
        viewModelScope.launch {
            val row = mutableState.value.moments.firstOrNull { moment -> moment.id == momentId } ?: return@launch
            if (!row.isMine) return@launch
            repository.deleteMoment(momentId)
            mutableState.update { state ->
                state.copy(moments = state.moments.filterNot { moment -> moment.id == momentId })
            }
        }
    }

    /**
     * 删一条自己写的评论:它下面挂着的回复也一起删。
     *
     * 别人的评论不处理 —— 界面上根本没给入口,这里再守一次。删完重读一次评论树,
     * 免得界面上留着一行已经不存在的回复。
     */
    fun deleteComment(momentId: String, commentId: String) {
        viewModelScope.launch {
            repository.deleteComment(momentId, commentId)
            val comments = repository.commentsOf(momentId)
            updateRow(momentId) { row -> row.copy(comments = buildCommentTree(comments)) }
        }
    }

    /** 宿主的角色名单与本机资料:一个补头像,一个补封面与昵称。 */
    private fun observeHost() {
        viewModelScope.launch {
            host.observeCharacters()
                .catch { emit(emptyList()) }
                .collect { list ->
                    characters = list.associateBy { character -> character.id }
                    applyAvatars()
                }
        }
        viewModelScope.launch {
            host.observeProfile()
                .catch { emit(MpProfile(nickname = "")) }
                .collect { profile ->
                    selfName = profile.nickname
                    mutableState.update {
                        it.copy(
                            selfName = profile.nickname,
                            selfAvatarUri = profile.avatarUri?.takeIf(String::isNotBlank),
                            coverImageUri = profile.coverImageUri?.takeIf(String::isNotBlank),
                        )
                    }
                }
        }
    }

    /** 让随机一个启用角色生成一条动态;没有角色或没生成出来就什么都不做。 */
    private suspend fun generateMoment() {
        val character = characters.values.randomOrNull(random) ?: return
        val hint = material.forMoment(character.id)
        val content = runCatching { host.composeMoment(character.id, hint) }.getOrNull() ?: return
        val moment = repository.insertFromCharacter(character, content) ?: return
        // 手刷也算一次生成:记进防抖账本,写失败只意味着少一层保护,不该拖垮这次刷新
        runCatching { settings.markGenerated(moment.createdAt) }
    }

    private suspend fun reloadFirstPage() = timelineLock.withLock {
        val page = pageOf(limit = PAGE_SIZE, offset = 0)
        val rows = buildRows(page)
        mutableState.update {
            it.copy(
                moments = rows,
                hasMore = page.size == PAGE_SIZE,
                isLoadingMore = false,
                isLoaded = true,
            )
        }
    }

    private suspend fun appendPage() = timelineLock.withLock {
        val loaded = mutableState.value.moments.size
        val page = pageOf(limit = PAGE_SIZE, offset = loaded)
        val rows = buildRows(page)
        mutableState.update {
            it.copy(
                moments = it.moments + rows,
                hasMore = page.size == PAGE_SIZE,
                isLoadingMore = false,
            )
        }
    }

    /** 一页时间线:两种模式只是查询条件不同,分页与游标完全一样。 */
    private suspend fun pageOf(limit: Int, offset: Int): List<MpMomentEntity> =
        if (onlyMine) repository.pageMine(limit, offset) else repository.page(limit, offset)

    /**
     * 一页动态补上点赞人与评论。
     *
     * 两次批量查询(点赞一张、评论一张)而不是每条各查两次:二十条动态原本要四十次往返。
     */
    private suspend fun buildRows(entities: List<MpMomentEntity>): List<MomentUi> {
        if (entities.isEmpty()) return emptyList()
        val ids = entities.map { entity -> entity.id }
        val likes = repository.likesByMomentIds(ids)
        val comments = repository.commentsByMomentIds(ids)
        return entities.map { entity ->
            buildMomentUi(
                entity = entity,
                likes = likes[entity.id].orEmpty(),
                comments = comments[entity.id].orEmpty(),
                characters = characters,
            )
        }
    }

    /** 单条动态(刚发布的、刚被点赞/评论的)用单查补齐,不必为一行动一次批量。 */
    private suspend fun buildRow(entity: MpMomentEntity): MomentUi = buildMomentUi(
        entity = entity,
        likes = repository.likesOf(entity.id),
        comments = repository.commentsOf(entity.id),
        characters = characters,
    )

    private fun updateRow(momentId: String, transform: (MomentUi) -> MomentUi) {
        mutableState.update { state ->
            state.copy(
                moments = state.moments.map { row -> if (row.id == momentId) transform(row) else row },
            )
        }
    }

    /** 角色名单到了之后补头像;行的其余字段不动。 */
    private fun applyAvatars() {
        mutableState.update { state ->
            state.copy(
                moments = state.moments.map { row ->
                    val uri = characters[row.authorId]?.avatarUri?.takeIf(String::isNotBlank)
                    if (uri == row.authorAvatarUri) row else row.copy(authorAvatarUri = uri)
                },
            )
        }
    }

    private companion object {
        /** 每页条数:一屏大约两三条动态,二十条够翻好几屏。 */
        const val PAGE_SIZE: Int = 20
    }
}
