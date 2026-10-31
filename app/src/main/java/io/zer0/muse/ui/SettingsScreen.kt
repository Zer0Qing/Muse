package io.zer0.muse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseSearchBar
import io.zer0.muse.ui.common.form.MuseSettingsIcon
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseLargeTitleHeader
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.museModalScrim
import io.zer0.muse.ui.common.surface.museSafeTopInsetPadding
import io.zer0.muse.ui.settings.SettingsCatalog
import io.zer0.muse.ui.settings.SettingsEntryAction
import io.zer0.muse.ui.settings.SettingsGroupKind
import io.zer0.muse.ui.settings.SettingsGroupSpec
import io.zer0.muse.ui.settings.SettingsSearchCatalog
import io.zer0.muse.ui.settings.SettingsSearchRecord
import io.zer0.muse.ui.settings.filterSettingsSearch
import io.zer0.muse.ui.settings.rememberSettingsActionHost
import io.zer0.muse.ui.settings.settingsBreadcrumb
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge

/** 首页分类行的测试标签；测试按它数分类行、断言首页没有子页条目。 */
internal const val SETTINGS_GROUP_ROW_TAG: String = "settings-group-row"

/**
 * 设置首页：12 个一级分类，每类一行（图标 + 分类名 + 一句话说明 + 右箭头）。
 *
 * 条目（能点进去的页面 / 开关）都在分类页里：原先 48 项平铺在同一个滚动列表里，
 * 页面和开关长得一模一样，用户分不清哪个是入口哪个是状态。分类与条目的顺序、标题
 * 全部来自 [SettingsCatalog]，搜索索引也取自同一份清单，三处不会各自漂移。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit = {},
    onOpenGroup: (SettingsGroupKind) -> Unit = {},
    onNavigate: (Any) -> Unit = {},
) {
    val actionHost = rememberSettingsActionHost()
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    SettingsHomeList(
        onBack = onBack,
        onOpenAccount = onOpenAccount,
        onOpenGroup = onOpenGroup,
        onOpenSearch = { isSearching = true },
    )

    if (isSearching) {
        SettingsSearchPalette(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onDismiss = {
                isSearching = false
                searchQuery = ""
            },
            onSelect = { record ->
                isSearching = false
                searchQuery = ""
                when (val action = record.action) {
                    is SettingsEntryAction.Navigate -> onNavigate(action.route)
                    // 开关没有独立页面，落到它所在的分类页，用户在那里翻。
                    is SettingsEntryAction.Toggle -> onOpenGroup(record.group)
                    is SettingsEntryAction.Action -> actionHost.run(action.id)
                }
            },
        )
    }
}

/**
 * 设置首页列表：大标题 + 吸顶搜索栏 + 账户卡 + 14 个分类行。
 *
 * v2.0.1: 大标题（item 0）随滚动推出，搜索栏（item 1）到达顶部后吸顶；吸顶瞬间内部
 * 实色搜索栏淡出、外层玻璃搜索栏（Haze 胶囊内背景模糊）接管。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SettingsHomeList(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenGroup: (SettingsGroupKind) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val settingsListState = rememberLazyListState()
    val isSearchBarStuck by remember {
        derivedStateOf { settingsListState.firstVisibleItemIndex >= 1 }
    }
    // 吸顶过渡进度（0 = 实色常态，1 = 玻璃吸顶）；内部占位与外层玻璃共用，保证交叉淡化同步。
    val searchStuckProgress by animateFloatAsState(
        targetValue = if (isSearchBarStuck) 1f else 0f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
        label = "searchStuckProgress",
    )
    val surfaceHazeState = remember { HazeState() }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        // v2.0.1: 搜索已改为浮层，不做顶部工具栏切换。
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = settingsListState,
                modifier = Modifier
                    .fillMaxSize()
                    // 列表自持状态栏内边距（大标题随列表滚动、搜索栏吸顶于状态栏下方）。
                    .museSafeTopInsetPadding()
                    // 作为 Haze 背景模糊的内容源（吸顶玻璃搜索栏读取列表内容）。
                    .hazeSource(state = surfaceHazeState),
                contentPadding = PaddingValues(
                    // 吸顶后 stickyHeader 脱离内容流，占位栏不再撑开空间，
                    // 列表项会直接从搜索栏下方钻过去。这里按吸顶进度补一份
                    // 等高 padding，保证内容始终停留在搜索栏之下。
                    top = innerPadding.calculateTopPadding() +
                        (MusePaddings.searchBarHeight + MusePaddings.contentGap) * searchStuckProgress,
                    bottom = innerPadding.calculateBottomPadding() + MusePaddings.sectionGap,
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.cardGap),
            ) {
                item(key = "settings_large_header") {
                    MuseLargeTitleHeader(
                        title = stringResource(R.string.settings_screen_title),
                        onBack = onBack,
                    )
                }
                stickyHeader(key = "settings_search_bar") {
                    SettingsStickySearchBar(progress = searchStuckProgress, onOpenSearch = onOpenSearch)
                }
                item(key = "account") {
                    io.zer0.muse.ui.account.AccountCard(
                        onClick = onOpenAccount,
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    )
                }
                item(key = "settings_groups") {
                    SettingsGroupCards(onOpenGroup = onOpenGroup)
                }
            }

            // 吸顶玻璃搜索栏（覆盖层）——列表滚到顶后接管：只在胶囊形状内对下方内容做
            // 背景模糊（Haze），胶囊之外无遮罩；内部占位搜索栏同步淡出。
            if (searchStuckProgress > 0.01f) {
                MuseSearchBar(
                    text = stringResource(R.string.settings_search_hint),
                    onClick = onOpenSearch,
                    glass = true,
                    hazeState = surfaceHazeState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .museSafeTopInsetPadding()
                        // 底部多留一份内容间距，避免胶囊下沿紧贴分隔线。
                        .padding(
                            start = MusePaddings.screen,
                            end = MusePaddings.screen,
                            top = 6.dp,
                            bottom = MusePaddings.contentGap,
                        )
                        .alpha(searchStuckProgress),
                )
            }
        }
    }
}

/** 占位搜索栏：随内容滚动，吸顶后由外层玻璃搜索栏接管。 */
@Composable
private fun SettingsStickySearchBar(progress: Float, onOpenSearch: () -> Unit) {
    // 吸顶时这层会脱离内容流，下方条目从它下面穿过，因此需要一层背景遮住。
    // 背景只铺胶囊本体高度（不含上下 6dp 呼吸位），避免顶栏白带过厚。
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MusePaddings.searchBarHeight, max = MusePaddings.searchBarHeight)
                .padding(vertical = 6.dp)
                .background(MaterialTheme.colorScheme.background.copy(alpha = progress)),
        )
        MuseSearchBar(
            text = stringResource(R.string.settings_search_hint),
            onClick = onOpenSearch,
            modifier = Modifier
                .padding(horizontal = MusePaddings.screen, vertical = 6.dp)
                .alpha(1f - progress),
        )
    }
}

/**
 * 首页的 14 个分类行。
 *
 * 单独成一个组合函数，测试可以脱开 DI 与搜索浮层直接渲染它，数分类行、点分类行。
 */
@Composable
internal fun SettingsGroupCards(
    onOpenGroup: (SettingsGroupKind) -> Unit,
    modifier: Modifier = Modifier,
    groups: List<SettingsGroupSpec> = SettingsCatalog.groups,
) {
    CardGroup(modifier = modifier.padding(horizontal = MusePaddings.screen)) {
        groups.forEach { group ->
            item(
                key = group.kind,
                modifier = Modifier.testTag(SETTINGS_GROUP_ROW_TAG),
                onClick = { onOpenGroup(group.kind) },
                leadingContent = { MuseSettingsIcon(group.icon) },
                headlineContent = { Text(stringResource(group.titleRes)) },
                supportingContent = { Text(stringResource(group.subtitleRes)) },
                trailingContent = { ChevronRight() },
            )
        }
    }
}

/**
 * 设置搜索浮层 — 胶囊搜索框 + 实时过滤设置项。
 *
 * 结果行显示面包屑（分类 › 条目）：搜索是「知道要找什么」的场景，只给条目名
 * 看不出它在哪一层，也不知道点进去会到哪。
 */
@Composable
private fun SettingsSearchPalette(
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (SettingsSearchRecord) -> Unit,
) {
    // 资源在组合期解析一次：过滤逻辑是纯函数，不能在里面调 stringResource。
    val groupTitles: Map<SettingsGroupKind, String> = SettingsCatalog.groups.associate { group ->
        group.kind to stringResource(group.titleRes)
    }
    val records = SettingsSearchCatalog.records
    val titles: Map<Int, String> = records.map { it.titleRes }.distinct().associateWith { stringResource(it) }
    val results = filterSettingsSearch(
        records = records,
        query = query,
        titleOf = { titles[it].orEmpty() },
        groupTitleOf = { groupTitles[it].orEmpty() },
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .museModalScrim()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.largeGap),
            ) {
                SettingsSearchField(query = query, onQueryChange = onQueryChange)
                if (query.isNotBlank()) {
                    SettingsSearchResults(
                        results = results,
                        titles = titles,
                        groupTitles = groupTitles,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

/** 搜索浮层顶部的输入胶囊；首次出现时抢焦点并弹键盘。 */
@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Dialog 是独立窗口，可能在一帧内尚未 attach，此时 requestFocus 会静默失效。
    // 改为「等帧 + 重试」，焦点真正落下后再弹键盘。
    LaunchedEffect(Unit) {
        repeat(5) { attempt ->
            withFrameNanos { }
            val focused = runCatching { focusRequester.requestFocus() }.isSuccess
            if (focused) {
                keyboard?.show()
                return@LaunchedEffect
            }
            if (attempt > 0) kotlinx.coroutines.delay(40L)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.pill,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Icon(
                imageVector = MuseIcons.search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .padding(vertical = MusePaddings.tightGap),
            )
            if (query.isNotEmpty()) {
                MuseTactileButton(
                    icon = MuseIcons.x,
                    onClick = { onQueryChange("") },
                    contentDescription = stringResource(R.string.command_palette_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = MuseIconSizes.iconSmall,
                )
            }
        }
    }
}

/** 搜索结果卡：命中数 + 面包屑行。 */
@Composable
private fun SettingsSearchResults(
    results: List<SettingsSearchRecord>,
    titles: Map<Int, String>,
    groupTitles: Map<SettingsGroupKind, String>,
    onSelect: (SettingsSearchRecord) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.contentGap),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                item(key = "count") {
                    Text(
                        text = stringResource(R.string.settings_search_result_count, results.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            horizontal = MusePaddings.contentGap,
                            vertical = MusePaddings.tightGap,
                        ),
                    )
                }
                // key 里带下标：条目清单理论上唯一，但 Compose 对重复 key 直接抛异常，
                // 带上下标后即使将来又出现重复，也只是渲染两行，不会把应用带走。
                itemsIndexed(
                    items = results,
                    key = { index, record -> index.toString() + record.titleRes + record.group.name },
                ) { _, record ->
                    SettingsSearchResultRow(
                        record = record,
                        title = titles[record.titleRes].orEmpty(),
                        groupTitle = groupTitles[record.group].orEmpty(),
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

/** 单条搜索结果：图标 + 条目标题 + 面包屑。 */
@Composable
private fun SettingsSearchResultRow(
    record: SettingsSearchRecord,
    title: String,
    groupTitle: String,
    onSelect: (SettingsSearchRecord) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(record) }
            .padding(
                horizontal = MusePaddings.contentGap,
                vertical = MusePaddings.itemGap,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        MuseSettingsIcon(record.icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = settingsBreadcrumb(groupTitle = groupTitle, entryTitle = title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
