package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.LinearScrollGesture
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.61-B: 使用教程页 — 面向新手的图文引导。
 *
 * 把用户当成完全不懂技术的小白,用通俗语言讲解 Muse 的各项功能。
 * 分为八个章节,每章用圆角卡片(MuseShapes.large)包裹,风格对齐系统设置。
 * 禁止 emoji,禁止 Android 原生方块风格。
 *
 * v1.0.16: 右侧增加章节快速跳转竖条,点击对应章节序号可快速滚动定位。
 *
 * v1.0.17:
 *  - 顶部增加搜索框(MuseTextField),输入关键词过滤匹配小节(标题+正文)。
 *  - 章节卡片支持折叠/展开,默认第一章展开,其余折叠;展开图标使用 MuseIcons.chevronDown/Right。
 *  - 跳转条改为显示章节首字(开/配/日/高/个/数/常),选中态用 onSurface 黑白风格。
 *  - 用 rememberSaveable 保存最后查看的章节索引,进入页面自动滚动到上次查看位置。
 *
 * v2.x 重构(用户反馈"右侧滑动条一块一块跳、不跟随内容"):
 *  - 原实现 LazyColumn 每章一个 item(展开后一章极长),指示器按"可见章节"整块高亮,
 *    且点击只滚到章节顶部 → 体感一块一块、点击不精确。
 *  - 现改为"章节头 + 每个小节"均为独立 LazyColumn item(扁平列表):
 *    · 指示器高亮 = 当前可见 item 对应的小节,滚动连续跟随;
 *    · 点击指示点 = 精确滚动到该小节(折叠章节则先展开再定位);
 *    · 指示器自身随滚动自动迁移,始终把当前点保持在可视区。
 *  - 视觉同步简化:章节头独立卡片;小节逐节紧凑卡片,分隔清晰。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTutorialPage(onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // 章节元数据(图标 + 标题资源 id + 小节列表)。
    val chapters = remember {
        listOf(
            TutorialChapterData(
                icon = MuseIcons.rocket,
                titleRes = R.string.settings_tutorial_ch1_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch1_s1_title, R.string.settings_tutorial_ch1_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s2_title, R.string.settings_tutorial_ch1_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s3_title, R.string.settings_tutorial_ch1_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s4_title, R.string.settings_tutorial_ch1_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s5_title, R.string.settings_tutorial_ch1_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s6_title, R.string.settings_tutorial_ch1_s6_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s7_title, R.string.settings_tutorial_ch1_s7_content),
                    TutorialSection(R.string.settings_tutorial_ch1_s8_title, R.string.settings_tutorial_ch1_s8_content),
                ),
            ),
            TutorialChapterData(
                icon = MuseIcons.key,
                titleRes = R.string.settings_tutorial_ch2_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch2_s1_title, R.string.settings_tutorial_ch2_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s2_title, R.string.settings_tutorial_ch2_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s3_title, R.string.settings_tutorial_ch2_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s4_title, R.string.settings_tutorial_ch2_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s5_title, R.string.settings_tutorial_ch2_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s6_title, R.string.settings_tutorial_ch2_s6_content),
                    TutorialSection(R.string.settings_tutorial_ch2_s7_title, R.string.settings_tutorial_ch2_s7_content),
                ),
            ),
            TutorialChapterData(
                icon = MuseIcons.chat,
                titleRes = R.string.settings_tutorial_ch3_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch3_s1_title, R.string.settings_tutorial_ch3_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s2_title, R.string.settings_tutorial_ch3_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s3_title, R.string.settings_tutorial_ch3_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s4_title, R.string.settings_tutorial_ch3_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s5_title, R.string.settings_tutorial_ch3_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s6_title, R.string.settings_tutorial_ch3_s6_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s7_title, R.string.settings_tutorial_ch3_s7_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s8_title, R.string.settings_tutorial_ch3_s8_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s9_title, R.string.settings_tutorial_ch3_s9_content),
                    TutorialSection(R.string.settings_tutorial_ch3_s10_title, R.string.settings_tutorial_ch3_s10_content),
                ),
            ),
            TutorialChapterData(
                icon = MuseIcons.stars,
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
                    TutorialSection(R.string.settings_tutorial_ch4_s11_title, R.string.settings_tutorial_ch4_s11_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s12_title, R.string.settings_tutorial_ch4_s12_content),
                    TutorialSection(R.string.settings_tutorial_ch4_s13_title, R.string.settings_tutorial_ch4_s13_content),
                ),
            ),
            TutorialChapterData(
                icon = MuseIcons.palette,
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
                icon = MuseIcons.database,
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
                icon = MuseIcons.help,
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
            TutorialChapterData(
                icon = MuseIcons.plug,
                titleRes = R.string.settings_tutorial_ch8_title,
                sections = listOf(
                    TutorialSection(R.string.settings_tutorial_ch8_s1_title, R.string.settings_tutorial_ch8_s1_content),
                    TutorialSection(R.string.settings_tutorial_ch8_s2_title, R.string.settings_tutorial_ch8_s2_content),
                    TutorialSection(R.string.settings_tutorial_ch8_s3_title, R.string.settings_tutorial_ch8_s3_content),
                    TutorialSection(R.string.settings_tutorial_ch8_s4_title, R.string.settings_tutorial_ch8_s4_content),
                    TutorialSection(R.string.settings_tutorial_ch8_s5_title, R.string.settings_tutorial_ch8_s5_content),
                    TutorialSection(R.string.settings_tutorial_ch8_s6_title, R.string.settings_tutorial_ch8_s6_content),
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

    // v2.x: 扁平 item 列表(章节头 + 展开章节的小节),供 LazyColumn 平铺渲染。
    val flatItems = remember(chapters, expandedChapters) {
        buildList {
            chapters.forEachIndexed { ci, ch ->
                add(TutorialFlatItem.ChapterHeader(ci))
                if (ci in expandedChapters) {
                    ch.sections.indices.forEach { si -> add(TutorialFlatItem.Section(ci, si)) }
                }
            }
        }
    }

    // 进入页面时自动滚动到上次查看的章节(v2.x: 定位到该章节头所在的 flat index)。
    LaunchedEffect(Unit) {
        if (!isSearching && lastViewedChapter in 1 until chapterCount) {
            val headerIndex = flatItems.indexOfFirst {
                it is TutorialFlatItem.ChapterHeader && it.chapterIndex == lastViewedChapter
            }
            if (headerIndex >= 0) listState.scrollToItem(headerIndex)
        }
    }

    // 监听当前可见章节,持久化保存索引(搜索时不更新,避免污染)。
    LaunchedEffect(listState, isSearching, flatItems) {
        if (!isSearching) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .collect { itemIndex ->
                    val item = flatItems.getOrNull(itemIndex.coerceIn(0, (flatItems.size - 1).coerceAtLeast(0)))
                    val chapterIndex = when (item) {
                        is TutorialFlatItem.ChapterHeader -> item.chapterIndex
                        is TutorialFlatItem.Section -> item.chapterIndex
                        else -> lastViewedChapter
                    }.coerceIn(0, chapterCount - 1)
                    if (chapterIndex != lastViewedChapter) {
                        lastViewedChapter = chapterIndex
                    }
                }
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 顶部搜索框 — 始终作为第 0 项
                item {
                    MuseTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.settings_tutorial_search_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = MuseIcons.search,
                                contentDescription = null,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                MuseTactileButton(
                                    icon = MuseIcons.x,
                                    onClick = { searchQuery = "" },
                                    contentDescription = stringResource(R.string.quick_notes_clear_search),
                                    iconSize = MuseIconSizes.iconSmall,
                                )
                            }
                        } else {
                            null
                        },
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
                                    text = stringResource(R.string.settings_tutorial_search_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    } else {
                        items(filteredSections, key = { "${it.chapterIndex}:${it.sectionTitle}" }) { section ->
                            SearchResultCard(section = section)
                        }
                    }
                } else {
                    // 完整章节视图 — v2.x: 平铺到小节(章节头 + 逐节独立 item),
                    // 指示器才能随滚动连续跟随、点击精确到节。
                    items(flatItems, key = { it.key }) { item ->
                        when (item) {
                            is TutorialFlatItem.ChapterHeader -> {
                                val chapter = chapters[item.chapterIndex]
                                TutorialChapterHeader(
                                    icon = chapter.icon,
                                    titleRes = chapter.titleRes,
                                    isExpanded = item.chapterIndex in expandedChapters,
                                    onToggleExpand = {
                                        expandedChapters = if (item.chapterIndex in expandedChapters) {
                                            expandedChapters - item.chapterIndex
                                        } else {
                                            expandedChapters + item.chapterIndex
                                        }
                                    },
                                )
                            }
                            is TutorialFlatItem.Section -> {
                                val section = chapters[item.chapterIndex].sections[item.sectionIndex]
                                TutorialSectionCard(
                                    titleRes = section.titleRes,
                                    contentRes = section.contentRes,
                                )
                            }
                        }
                    }
                }
            }

            // v2.5.9: 右侧热区 — 长按激活后上下滑动线性滚动（不再画可视轨道）。
            if (!isSearching) {
                LinearScrollGesture(
                    listState = listState,
                    modifier =
                    Modifier
                        .fillMaxHeight()
                        .padding(end = 4.dp, top = innerPadding.calculateTopPadding()),
                )
            }
        }
    }
}

/**
 * v2.x: 教程章节头卡片 — 图标 + 标题 + 展开状态。独立 LazyColumn item。
 */
@Composable
private fun TutorialChapterHeader(icon: ImageVector, titleRes: Int, isExpanded: Boolean, onToggleExpand: () -> Unit) {
    val title = stringResource(titleRes)
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 16.dp, vertical = 14.dp),
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
                imageVector = if (isExpanded) MuseIcons.chevronDown else MuseIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * v2.x: 教程小节卡片 — 标题(主色) + 正文。独立 LazyColumn item。
 */
@Composable
private fun TutorialSectionCard(titleRes: Int, contentRes: Int) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(contentRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** v2.x: 教程页扁平列表项(章节头 / 小节)。 */
private sealed interface TutorialFlatItem {
    val key: String

    data class ChapterHeader(val chapterIndex: Int) : TutorialFlatItem {
        override val key: String get() = "tutorial_ch_$chapterIndex"
    }

    data class Section(val chapterIndex: Int, val sectionIndex: Int) : TutorialFlatItem {
        override val key: String get() = "tutorial_sec_${chapterIndex}_$sectionIndex"
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
