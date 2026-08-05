package io.zer0.muse.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch

/**
 * v1.61-B: 使用教程页 — 面向新手的图文引导。
 *
 * 把用户当成完全不懂技术的小白,用通俗语言讲解 Muse 的各项功能。
 * 分为七个章节,每章用圆角卡片(MuseShapes.large)包裹,风格对标 iOS 设置。
 * 禁止 emoji,禁止 Android 原生方块风格。
 *
 * v1.0.16: 右侧增加章节快速跳转竖条,点击对应章节序号可快速滚动定位。
 *
 * v1.0.17:
 *  - 顶部增加搜索框(MuseTextField),输入关键词过滤匹配小节(标题+正文)。
 *  - 章节卡片支持折叠/展开,默认第一章展开,其余折叠;展开图标使用 TablerIcons.ChevronDown/Right。
 *  - 跳转条改为显示章节首字(开/配/日/高/个/数/常),选中态用 onSurface 黑白风格。
 *  - 用 rememberSaveable 保存最后查看的章节索引,进入页面自动滚动到上次查看位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTutorialPage(
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 章节元数据(图标 + 标题资源 id + 小节列表)。
    val chapters = remember {
        listOf(
            TutorialChapterData(
                icon = TablerIcons.Rocket,
                titleRes = R.string.settings_tutorial_ch1_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch1_s1_title, R.string.settings_tutorial_ch1_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s2_title, R.string.settings_tutorial_ch1_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s3_title, R.string.settings_tutorial_ch1_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s4_title, R.string.settings_tutorial_ch1_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s5_title, R.string.settings_tutorial_ch1_s5_content),
                ),
            ),
            TutorialChapterData(
                icon = TablerIcons.Key,
                titleRes = R.string.settings_tutorial_ch2_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch2_s1_title, R.string.settings_tutorial_ch2_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s2_title, R.string.settings_tutorial_ch2_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s3_title, R.string.settings_tutorial_ch2_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s4_title, R.string.settings_tutorial_ch2_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s5_title, R.string.settings_tutorial_ch2_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s6_title, R.string.settings_tutorial_ch2_s6_content),
                ),
            ),
            TutorialChapterData(
                icon = TablerIcons.MessageCircle,
                titleRes = R.string.settings_tutorial_ch3_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch3_s1_title, R.string.settings_tutorial_ch3_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s2_title, R.string.settings_tutorial_ch3_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s3_title, R.string.settings_tutorial_ch3_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s4_title, R.string.settings_tutorial_ch3_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s5_title, R.string.settings_tutorial_ch3_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s6_title, R.string.settings_tutorial_ch3_s6_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s7_title, R.string.settings_tutorial_ch3_s7_content),
                ),
            ),
            TutorialChapterData(
                icon = TablerIcons.Stars,
                titleRes = R.string.settings_tutorial_ch4_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch4_s1_title, R.string.settings_tutorial_ch4_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s2_title, R.string.settings_tutorial_ch4_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s3_title, R.string.settings_tutorial_ch4_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s4_title, R.string.settings_tutorial_ch4_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s5_title, R.string.settings_tutorial_ch4_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s6_title, R.string.settings_tutorial_ch4_s6_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s7_title, R.string.settings_tutorial_ch4_s7_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s8_title, R.string.settings_tutorial_ch4_s8_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s9_title, R.string.settings_tutorial_ch4_s9_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s10_title, R.string.settings_tutorial_ch4_s10_content),
                ),
            ),
            TutorialChapterData(
                icon = TablerIcons.Palette,
                titleRes = R.string.settings_tutorial_ch5_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch5_s1_title, R.string.settings_tutorial_ch5_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch5_s2_title, R.string.settings_tutorial_ch5_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch5_s3_title, R.string.settings_tutorial_ch5_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch5_s4_title, R.string.settings_tutorial_ch5_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch5_s5_title, R.string.settings_tutorial_ch5_s5_content),
                ),
            ),
            TutorialChapterData(
                icon = TablerIcons.Database,
                titleRes = R.string.settings_tutorial_ch6_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch6_s1_title, R.string.settings_tutorial_ch6_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch6_s2_title, R.string.settings_tutorial_ch6_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch6_s3_title, R.string.settings_tutorial_ch6_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch6_s4_title, R.string.settings_tutorial_ch6_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch6_s5_title, R.string.settings_tutorial_ch6_s5_content),
                ),
            ),
            TutorialChapterData(
                icon = TablerIcons.Help,
                titleRes = R.string.settings_tutorial_ch7_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch7_s1_title, R.string.settings_tutorial_ch7_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch7_s2_title, R.string.settings_tutorial_ch7_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch7_s3_title, R.string.settings_tutorial_ch7_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch7_s4_title, R.string.settings_tutorial_ch7_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch7_s5_title, R.string.settings_tutorial_ch7_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch7_s6_title, R.string.settings_tutorial_ch7_s6_content),
                ),
            ),
        )
    }
    val chapterCount = chapters.size

    // 预加载所有小节字符串(标题+正文),用于搜索过滤。
    val searchableSections = remember(context, chapters) {
        chapters.flatMapIndexed { chapterIndex, chapter ->
            chapter.sections.map { section ->
                SearchableSection(
                    chapterIndex = chapterIndex,
                    chapterTitle = context.getString(chapter.titleRes),
                    chapterIcon = chapter.icon,
                    sectionTitle = context.getString(section.titleRes),
                    content = context.getString(section.contentRes),
                )
            }
        }
    }

    // v1.0.18: 扁平化所有小节为跳转点列表,每个小节对应跳转条上的一个点。
    // 点击点会展开所在章节并滚动到该章节。
    val sectionJumpItems = remember(context, chapters) {
        chapters.flatMapIndexed { chapterIndex, chapter ->
            val chapterTitle = context.getString(chapter.titleRes)
            chapter.sections.mapIndexed { sectionIndex, section ->
                SectionJumpItem(
                    chapterIndex = chapterIndex,
                    sectionIndex = sectionIndex,
                    chapterTitle = chapterTitle,
                    sectionTitle = context.getString(section.titleRes),
                )
            }
        }
    }

    // 搜索状态(本会话内有效,退出页面即重置)。
    var searchQuery by remember { mutableStateOf("") }
    val isSearching = searchQuery.isNotBlank()

    // 章节展开状态:默认第一章展开,其余折叠。
    var expandedChapters by remember { mutableStateOf<Set<Int>>(setOf(0)) }

    // 最后查看的章节索引(跨会话持久化)。
    var lastViewedChapter by rememberSaveable { mutableStateOf(0) }

    // 搜索过滤结果。
    val filteredSections = if (isSearching) {
        searchableSections.filter { section ->
            section.sectionTitle.contains(searchQuery, ignoreCase = true) ||
                section.content.contains(searchQuery, ignoreCase = true)
        }
    } else {
        emptyList()
    }

    // 进入页面时自动滚动到上次查看的章节(LazyColumn 第 0 项为搜索框,故章节索引需 +1)。
    LaunchedEffect(Unit) {
        if (!isSearching && lastViewedChapter in 1 until chapterCount) {
            listState.scrollToItem(lastViewedChapter + 1)
        }
    }

    // 监听当前可见章节,持久化保存索引(搜索时不更新,避免污染)。
    LaunchedEffect(listState, isSearching) {
        if (!isSearching) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .collect { itemIndex ->
                    val chapterIndex = (itemIndex - 1).coerceIn(0, chapterCount - 1)
                    if (chapterIndex != lastViewedChapter) {
                        lastViewedChapter = chapterIndex
                    }
                }
        }
    }

    Scaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.settings_tutorial_title),
                onBack = onBack,
                largeTitle = true,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // 左侧:章节内容列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = MusePaddings.screen),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen,
                ),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
            ) {
                // 顶部搜索框 — 始终作为第 0 项
                item {
                    MuseTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = "搜索教程内容...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = TablerIcons.Search,
                                contentDescription = null,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = TablerIcons.X,
                                        contentDescription = stringResource(R.string.quick_notes_clear_search),
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                }
                            }
                        } else null,
                        singleLine = true,
                    )
                }

                if (isSearching) {
                    // 搜索结果视图 — 扁平小节列表
                    if (filteredSections.isEmpty()) {
                        item {
                            Surface(
                                shape = MuseShapes.large,
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "未找到匹配内容",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    } else {
                        items(filteredSections) { section ->
                            SearchResultCard(section = section)
                        }
                    }
                } else {
                    // 完整章节视图
                    itemsIndexed(chapters) { index, chapter ->
                        val isExpanded = index in expandedChapters
                        TutorialChapter(
                            icon = chapter.icon,
                            titleRes = chapter.titleRes,
                            sections = chapter.sections,
                            isExpanded = isExpanded,
                            onToggleExpand = {
                                expandedChapters = if (index in expandedChapters) {
                                    expandedChapters - index
                                } else {
                                    expandedChapters + index
                                }
                            },
                        )
                    }
                }
            }

            // v1.0.18: 右侧小节级跳转竖条(每个小节一个点,可滚动,搜索时隐藏)
            if (!isSearching) {
                SectionQuickJumpRail(
                    sectionItems = sectionJumpItems,
                    listState = listState,
                    onItemClick = { chapterIndex, _ ->
                        scope.launch {
                            // 确保目标章节展开(不折叠其他已展开的章节)
                            if (chapterIndex !in expandedChapters) {
                                expandedChapters = expandedChapters + chapterIndex
                            }
                            // +1 偏移:LazyColumn 第 0 项为搜索框
                            listState.animateScrollToItem(chapterIndex + 1)
                        }
                    },
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(end = 4.dp, top = innerPadding.calculateTopPadding()),
                )
            }
        }
    }
}

/**
 * v1.0.18: 右侧小节级跳转竖条 — 每个小节一个点,可垂直滚动。
 *
 * 替代原章节首字跳转条(7 个大圆点),改为细粒度的小节跳转:
 *  - 每个小节一个点(5dp 圆点),点数 = 全部小节数(约 40+)
 *  - 用 LazyColumn 渲染,超出屏幕高度时可垂直滑动
 *  - 当前可见章节的所有小节点高亮(onSurface 色 + 7dp),其余为 surfaceVariant
 *  - 点击点:展开所在章节 + 滚动到该章节
 *
 * 设计参考:iOS 通讯录字母索引条 / Play Books 章节进度条。
 */
@Composable
private fun SectionQuickJumpRail(
    sectionItems: List<SectionJumpItem>,
    listState: LazyListState,
    onItemClick: (chapterIndex: Int, sectionIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 推算当前可见章节(LazyColumn 第 0 项为搜索框,章节 item 从 1 开始)
    val maxChapterIndex = sectionItems.maxOfOrNull { it.chapterIndex } ?: 0
    val visibleChapterIndex = (listState.firstVisibleItemIndex - 1)
        .coerceIn(0, maxChapterIndex)

    Surface(
        modifier = modifier,
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            items(sectionItems.size) { index ->
                val item = sectionItems[index]
                val isCurrent = item.chapterIndex == visibleChapterIndex
                SectionJumpDot(
                    isCurrent = isCurrent,
                    onClick = { onItemClick(item.chapterIndex, item.sectionIndex) },
                )
            }
        }
    }
}

/**
 * 单个小节跳转点 — 小圆点。
 *  - 默认 5dp 圆点 + surfaceVariant 色
 *  - 当前可见章节的小节点:7dp + onSurface 色
 *  - 点击区域 20dp(符合无障碍最小触控目标)
 */
@Composable
private fun SectionJumpDot(
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val dotSize = if (isCurrent) 7.dp else 5.dp
    val dotColor = if (isCurrent) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .size(20.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .background(color = dotColor, shape = CircleShape),
        )
    }
}

/**
 * 教程章节卡片 — 图标 + 标题 + 多个小节。
 * 使用 MuseShapes.large 圆角(18dp),对标 iOS 设置分组卡片风格。
 *
 * v1.0.17:
 *  - 支持 isExpanded 折叠/展开。
 *  - 折叠时只显示章节标题行(含展开图标),展开时显示完整小节列表。
 *  - 使用 animateContentSize() 添加展开/折叠动画。
 */
@Composable
private fun TutorialChapter(
    icon: ImageVector,
    titleRes: Int,
    sections: List<TutorialSection>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val title = stringResource(titleRes)
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
        ) {
            // 标题行(可点击切换展开/折叠)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (isExpanded) TablerIcons.ChevronDown else TablerIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (isExpanded) {
                Spacer(Modifier.height(12.dp))
                sections.forEach { section ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = stringResource(section.titleRes),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(section.contentRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * v1.0.17: 搜索结果卡片 — 扁平展示匹配的小节(含所属章节标题作为上下文)。
 */
@Composable
private fun SearchResultCard(section: SearchableSection) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = section.chapterIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = section.chapterTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = section.sectionTitle,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = section.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 教程小节 — 标题资源 id + 正文资源 id。 */
private class TutorialSection(
    val titleRes: Int,
    val contentRes: Int,
)

/** v1.0.17: 章节元数据 — 图标 + 标题资源 + 小节列表。 */
private class TutorialChapterData(
    val icon: ImageVector,
    val titleRes: Int,
    val sections: List<TutorialSection>,
)

/** v1.0.17: 用于搜索的可索引小节 — 已加载字符串,可直接做 contains 过滤。 */
private class SearchableSection(
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterIcon: ImageVector,
    val sectionTitle: String,
    val content: String,
)

/** v1.0.18: 跳转条小节项 — 章节索引 + 小节索引 + 章节标题 + 小节标题。 */
private class SectionJumpItem(
    val chapterIndex: Int,
    val sectionIndex: Int,
    val chapterTitle: String,
    val sectionTitle: String,
)
