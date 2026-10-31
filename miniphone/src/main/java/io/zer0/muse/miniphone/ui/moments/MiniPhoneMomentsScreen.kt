package io.zer0.muse.miniphone.ui.moments

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.muse.miniphone.ui.chat.MiniPhoneChatAvatar
import io.zer0.muse.miniphone.ui.chat.MiniPhoneGalleryViewer
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 朋友圈页入口:取自己的 ViewModel,把返回键交给系统。
 *
 * 两种模式各用各的 ViewModel:[onlyMine] 只查本机用户的动态(我页的「我的朋友圈」),
 * 否则看全部。以模式作 ViewModel 键 —— 从两个入口分别进来不能共用一份时间线窗口。
 *
 * 页面状态只在这一处被收集,下面全是无状态组件 —— 测试喂一份 [MomentsUiState] 就能渲染整页。
 */
@Composable
internal fun MiniPhoneMomentsRoute(onlyMine: Boolean = false, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: MiniPhoneMomentsViewModel = koinViewModel(key = if (onlyMine) MINE_KEY else ALL_KEY) { parametersOf(onlyMine) }
    val state by viewModel.uiState.collectAsState()
    BackHandler(onBack = onBack)
    MiniPhoneMomentsScreen(
        state = state,
        onBack = onBack,
        actions = MomentActions(
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onPublish = viewModel::publish,
            onToggleLike = viewModel::toggleLike,
            onComment = viewModel::comment,
            onDelete = viewModel::deleteMoment,
            onDeleteComment = viewModel::deleteComment,
        ),
        modifier = modifier,
        onlyMine = onlyMine,
    )
}

/**
 * 页面上的全部回调。
 *
 * 聚成一份而不是逐个当参数:这一页的动作有六个,散着传会把参数表撑得比内容还长,
 * 加一个动作(改可见范围、转发)时也不必再动所有调用方。
 */
@Immutable
internal class MomentActions(
    val onRefresh: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onPublish: (String, List<Uri>, Boolean) -> Unit = { _, _, _ -> },
    val onToggleLike: (String) -> Unit = {},
    val onComment: (String, String?, String) -> Unit = { _, _, _ -> },
    val onDelete: (String) -> Unit = {},
    val onDeleteComment: (String, String) -> Unit = { _, _ -> },
)

/**
 * 朋友圈页:顶栏 + 封面 + 时间线,最上面压着发布页与看图页。
 *
 * 无状态:状态与回调都从外面来。[composing] 与 [viewingUri] 是纯视图状态
 * (发布页开没开、在看哪张图),关页即弃,不进 [MomentsUiState]。
 *
 * @param onlyMine 「我的朋友圈」模式:标题换成自己的、空态提示改成让人发一条。
 *   别人的动态已经在查询层就拦掉了,这里不再做一次内存过滤。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MiniPhoneMomentsScreen(
    state: MomentsUiState,
    onBack: () -> Unit,
    actions: MomentActions,
    modifier: Modifier = Modifier,
    onlyMine: Boolean = false,
) {
    val colors = MiniPhoneTheme.colors
    var composing by rememberSaveable { mutableStateOf(false) }
    // 图集查看:那一组图与从第几张开始(零基)。两个值分开存,saveable 能直接带着走
    var viewingImages by rememberSaveable { mutableStateOf(listOf<String>()) }
    var viewingIndex by rememberSaveable { mutableIntStateOf(0) }
    val viewing = viewingImages.isNotEmpty()
    // 看图页盖在最上层时,返回键先关它
    BackHandler(enabled = viewing) { viewingImages = emptyList() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneMomentsTags.ROOT),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            MomentsTopBar(onBack = onBack, title = if (onlyMine) MINE_PAGE_TITLE else PAGE_TITLE, onCompose = { composing = true })
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = actions.onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                MomentsTimeline(
                    state = state,
                    actions = actions,
                    onlyMine = onlyMine,
                    onOpenImage = { images, index ->
                        viewingImages = images
                        viewingIndex = index
                    },
                )
            }
        }
        if (composing) {
            MomentPublishScreen(
                onClose = { composing = false },
                onPublish = { content, uris, onlySelf ->
                    actions.onPublish(content, uris, onlySelf)
                    composing = false
                },
            )
        }
        if (viewing) {
            MiniPhoneGalleryViewer(
                uris = viewingImages,
                initialIndex = viewingIndex,
                onDismiss = { viewingImages = emptyList() },
            )
        }
    }
}

/** 顶栏:返回、标题、右上角的发布入口。 */
@Composable
private fun MomentsTopBar(onBack: () -> Unit, title: String, onCompose: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.pageHeaderHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(TopBarButtonSize)
                    .clip(RoundedCornerShape(TopBarCorner))
                    .clickable(onClick = onBack)
                    .testTag(MiniPhoneMomentsTags.BACK_BUTTON),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Back,
                    contentDescription = BACK_DESCRIPTION,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(TopBarIconSize),
                )
            }
            Text(
                text = title,
                style = typography.title.copy(color = colors.textPrimary),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            )
            Box(
                modifier = Modifier
                    .size(TopBarButtonSize)
                    .clip(RoundedCornerShape(TopBarCorner))
                    .clickable(onClick = onCompose)
                    .testTag(MiniPhoneMomentsTags.COMPOSE_ENTRY),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Pen,
                    contentDescription = COMPOSE_DESCRIPTION,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(TopBarIconSize),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(dimens.hairline).background(colors.divider))
    }
}

/**
 * 时间线:封面打头,下面是动态卡片。
 *
 * 封面放在列表里而不是固定在上方:往下翻时它跟着走,内容区不被永久吃掉一百多像素。
 */
@Composable
private fun MomentsTimeline(state: MomentsUiState, actions: MomentActions, onlyMine: Boolean, onOpenImage: (List<String>, Int) -> Unit) {
    val dimens = MiniPhoneTheme.dimens
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag(MiniPhoneMomentsTags.LIST),
    ) {
        item(key = COVER_KEY, contentType = COVER_KEY) {
            MomentsCover(
                coverImageUri = state.coverImageUri,
                selfName = state.selfName,
                selfAvatarUri = state.selfAvatarUri,
            )
        }
        if (state.moments.isEmpty()) {
            item(key = EMPTY_KEY, contentType = EMPTY_KEY) {
                MomentsEmptyState(isLoaded = state.isLoaded, onlyMine = onlyMine)
            }
        }
        items(items = state.moments, key = { moment -> moment.id }, contentType = { MOMENT_CONTENT_TYPE }) { moment ->
            MomentCardView(
                moment = moment,
                onToggleLike = { actions.onToggleLike(moment.id) },
                onOpenImage = onOpenImage,
                onComment = { parentId, content -> actions.onComment(moment.id, parentId, content) },
                onDelete = { actions.onDelete(moment.id) },
                modifier = Modifier.padding(horizontal = dimens.pageMargin, vertical = CardVerticalGap),
                onDeleteComment = { commentId -> actions.onDeleteComment(moment.id, commentId) },
            )
        }
        if (state.isLoadingMore) {
            item(key = LOADING_KEY, contentType = LOADING_KEY) { ListFootNote(text = LOADING_TEXT) }
        }
    }
    LoadMoreEffect(listState = listState, hasMore = state.hasMore, onLoadMore = actions.onLoadMore)
}

/** 滚到列表末尾附近时,要下一页。 */
@Composable
private fun LoadMoreEffect(listState: LazyListState, hasMore: Boolean, onLoadMore: () -> Unit) {
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { loadMore() }
    }
}

/**
 * 封面:有图用图,没图用本模块的渐变。
 *
 * 头像与昵称压在下沿右侧 —— 朋友圈的身份牌不在屏幕顶上,而在自己的地盘上。
 * 高度取模块尺寸阶梯里的封面高度,不在这里写一个数。
 */
@Composable
private fun MomentsCover(coverImageUri: String?, selfName: String, selfAvatarUri: String?) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimens.coverHeight)
            .background(colors.surface)
            .testTag(MiniPhoneMomentsTags.COVER),
    ) {
        if (coverImageUri != null) {
            AsyncImage(
                model = coverImageUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(colors.accent.copy(alpha = COVER_GRADIENT_ALPHA), colors.background))),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selfName.ifBlank { DEFAULT_SELF_NAME },
                style = typography.title.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(MiniPhoneMomentsTags.SELF_NAME),
            )
            Spacer(Modifier.width(10.dp))
            MiniPhoneChatAvatar(
                name = selfName,
                avatarUri = selfAvatarUri,
                size = dimens.avatarLarge,
                modifier = Modifier.testTag(MiniPhoneMomentsTags.SELF_AVATAR),
            )
        }
    }
}

/**
 * 空态:自己画的「几张卡片叠在一起」。
 *
 * 由三块描边圆角矩形叠出「这里本该有一摞动态」,配一句能照做的提示 ——
 * 空白页只写「暂无内容」等于什么都没说。
 */
@Composable
private fun MomentsEmptyState(isLoaded: Boolean, onlyMine: Boolean) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EmptyVerticalPadding)
            .testTag(MiniPhoneMomentsTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!isLoaded) return@Column
        Canvas(Modifier.size(EmptyArtSize)) {
            val stroke = Stroke(width = EmptyStrokeWidth.toPx())
            val cardWidth = size.width * EMPTY_CARD_WIDTH_RATIO
            val cardHeight = size.height * EMPTY_CARD_HEIGHT_RATIO
            val corner = CornerRadius(EmptyCorner.toPx())
            repeat(EMPTY_CARD_COUNT) { index ->
                drawRoundRect(
                    color = colors.divider,
                    topLeft = Offset(
                        size.width * EMPTY_CARD_LEFT_RATIO,
                        size.height * EMPTY_CARD_TOP_RATIO + cardHeight * index * EMPTY_CARD_STEP_RATIO,
                    ),
                    size = Size(cardWidth, cardHeight),
                    cornerRadius = corner,
                    style = stroke,
                )
            }
            drawCircle(
                color = colors.accent,
                radius = EmptyDotRadius.toPx(),
                center = Offset(
                    size.width * (EMPTY_CARD_LEFT_RATIO + EMPTY_CARD_WIDTH_RATIO * EMPTY_DOT_INSET_RATIO),
                    size.height * EMPTY_CARD_TOP_RATIO + cardHeight * EMPTY_DOT_TOP_RATIO,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (onlyMine) MINE_EMPTY_TITLE else EMPTY_TITLE,
            style = typography.body.copy(color = colors.textPrimary),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (onlyMine) MINE_EMPTY_HINT else EMPTY_HINT,
            style = typography.timestamp.copy(color = colors.textSecondary),
            textAlign = TextAlign.Center,
        )
    }
}

/** 列表页脚的一句提示(载入中)。 */
@Composable
private fun ListFootNote(text: String) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Text(
        text = text,
        style = typography.timestamp.copy(color = colors.textSecondary),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    )
}

private val TopBarButtonSize = 40.dp
private val TopBarIconSize = 20.dp
private val TopBarCorner = 8.dp
private val CardVerticalGap = 6.dp
private val EmptyArtSize = 96.dp
private val EmptyStrokeWidth = 1.5.dp
private val EmptyCorner = 6.dp
private val EmptyDotRadius = 3.dp
private val EmptyVerticalPadding = 40.dp

private const val COVER_GRADIENT_ALPHA: Float = 0.35f
private const val EMPTY_CARD_COUNT: Int = 3
private const val EMPTY_CARD_WIDTH_RATIO: Float = 0.62f
private const val EMPTY_CARD_HEIGHT_RATIO: Float = 0.3f
private const val EMPTY_CARD_LEFT_RATIO: Float = 0.2f
private const val EMPTY_CARD_TOP_RATIO: Float = 0.1f
private const val EMPTY_CARD_STEP_RATIO: Float = 0.62f
private const val EMPTY_DOT_INSET_RATIO: Float = 0.18f
private const val EMPTY_DOT_TOP_RATIO: Float = 0.28f

private const val PAGE_TITLE: String = "朋友圈"
private const val MINE_PAGE_TITLE: String = "我的朋友圈"
private const val ALL_KEY: String = "mp-moments-all"
private const val MINE_KEY: String = "mp-moments-mine"
private const val BACK_DESCRIPTION: String = "返回"
private const val COMPOSE_DESCRIPTION: String = "写一条"
private const val DEFAULT_SELF_NAME: String = "我"
private const val EMPTY_TITLE: String = "还没有动态"
private const val EMPTY_HINT: String = "下拉刷新，等角色说点什么"
private const val MINE_EMPTY_TITLE: String = "还没有自己的动态"
private const val MINE_EMPTY_HINT: String = "点右上角写一条，记下现在"
private const val LOADING_TEXT: String = "载入中……"

/** 离列表末端还剩几条时开始要下一页。 */
private const val LOAD_MORE_THRESHOLD: Int = 2

private const val COVER_KEY: String = "mp-moments-cover-item"
private const val EMPTY_KEY: String = "mp-moments-empty-item"
private const val LOADING_KEY: String = "mp-moments-loading-item"
private const val MOMENT_CONTENT_TYPE: String = "mp-moment"
