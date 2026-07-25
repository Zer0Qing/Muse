package io.zer0.muse.ui.settings

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.IosTopBar
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTutorialPage(
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // v1.0.16: 在 Composable 上下文中预读取所有章节字符串(含标题),
    // 用于右侧竖条显示当前章节标题 + 内容列表。
    val chapterCount = 7

    Scaffold(
        topBar = {
            IosTopBar(
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
                tutorialChapters()
            }

            // v1.0.16: 右侧章节快速跳转竖条
            ChapterQuickJumpRail(
                chapterCount = chapterCount,
                listState = listState,
                onItemClick = { index ->
                    scope.launch {
                        listState.animateScrollToItem(index)
                    }
                },
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(end = 4.dp, top = innerPadding.calculateTopPadding()),
            )
        }
    }
}

/**
 * v1.0.16: 右侧章节快速跳转竖条。
 *
 * 竖直排列的章节序号点(1-7),点击滚动到对应章节。
 * 当前可见章节对应的点高亮(primary 色),其余为 surfaceVariant。
 * 设计对标 iOS 通讯录右侧字母索引条。
 */
@Composable
private fun ChapterQuickJumpRail(
    chapterCount: Int,
    listState: LazyListState,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleChapterIndex = if (listState.firstVisibleItemIndex < chapterCount) {
        listState.firstVisibleItemIndex
    } else 0

    Surface(
        modifier = modifier,
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (index in 0 until chapterCount) {
                val isCurrent = index == visibleChapterIndex
                ChapterJumpDot(
                    number = (index + 1).toString(),
                    isCurrent = isCurrent,
                    onClick = { onItemClick(index) },
                )
            }
        }
    }
}

/**
 * 单个章节跳转点 — 圆形,显示章节序号。
 */
@Composable
private fun ChapterJumpDot(
    number: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isCurrent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .padding(vertical = 2.dp, horizontal = 2.dp)
            .size(28.dp)
            .background(color = backgroundColor, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
        )
    }
}

/** 教程章节内容。 */
private fun LazyListScope.tutorialChapters() {
    // 第一章:开始使用(新手必读)
    item {
        TutorialChapter(
            icon = Icons.Outlined.RocketLaunch,
            titleRes = R.string.settings_tutorial_ch1_title,
            sections = listOf(
                TutorialSection(R.string.settings_tutorial_ch1_s1_title, R.string.settings_tutorial_ch1_s1_content),
                TutorialSection(R.string.settings_tutorial_ch1_s2_title, R.string.settings_tutorial_ch1_s2_content),
                TutorialSection(R.string.settings_tutorial_ch1_s3_title, R.string.settings_tutorial_ch1_s3_content),
                TutorialSection(R.string.settings_tutorial_ch1_s4_title, R.string.settings_tutorial_ch1_s4_content),
                TutorialSection(R.string.settings_tutorial_ch1_s5_title, R.string.settings_tutorial_ch1_s5_content),
            ),
        )
    }

    // 第二章:配置 AI 模型(重要)
    item {
        TutorialChapter(
            icon = Icons.Outlined.Key,
            titleRes = R.string.settings_tutorial_ch2_title,
            sections = listOf(
                TutorialSection(R.string.settings_tutorial_ch2_s1_title, R.string.settings_tutorial_ch2_s1_content),
                TutorialSection(R.string.settings_tutorial_ch2_s2_title, R.string.settings_tutorial_ch2_s2_content),
                TutorialSection(R.string.settings_tutorial_ch2_s3_title, R.string.settings_tutorial_ch2_s3_content),
                TutorialSection(R.string.settings_tutorial_ch2_s4_title, R.string.settings_tutorial_ch2_s4_content),
                TutorialSection(R.string.settings_tutorial_ch2_s5_title, R.string.settings_tutorial_ch2_s5_content),
                TutorialSection(R.string.settings_tutorial_ch2_s6_title, R.string.settings_tutorial_ch2_s6_content),
            ),
        )
    }

    // 第三章:日常聊天
    item {
        TutorialChapter(
            icon = Icons.AutoMirrored.Outlined.Chat,
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
        )
    }

    // 第四章:高级功能
    item {
        TutorialChapter(
            icon = Icons.Outlined.AutoAwesome,
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
        )
    }

    // 第五章:个性化设置
    item {
        TutorialChapter(
            icon = Icons.Outlined.Palette,
            titleRes = R.string.settings_tutorial_ch5_title,
            sections = listOf(
                TutorialSection(R.string.settings_tutorial_ch5_s1_title, R.string.settings_tutorial_ch5_s1_content),
                TutorialSection(R.string.settings_tutorial_ch5_s2_title, R.string.settings_tutorial_ch5_s2_content),
                TutorialSection(R.string.settings_tutorial_ch5_s3_title, R.string.settings_tutorial_ch5_s3_content),
                TutorialSection(R.string.settings_tutorial_ch5_s4_title, R.string.settings_tutorial_ch5_s4_content),
                TutorialSection(R.string.settings_tutorial_ch5_s5_title, R.string.settings_tutorial_ch5_s5_content),
            ),
        )
    }

    // 第六章:数据管理
    item {
        TutorialChapter(
            icon = Icons.Outlined.Storage,
            titleRes = R.string.settings_tutorial_ch6_title,
            sections = listOf(
                TutorialSection(R.string.settings_tutorial_ch6_s1_title, R.string.settings_tutorial_ch6_s1_content),
                TutorialSection(R.string.settings_tutorial_ch6_s2_title, R.string.settings_tutorial_ch6_s2_content),
                TutorialSection(R.string.settings_tutorial_ch6_s3_title, R.string.settings_tutorial_ch6_s3_content),
                TutorialSection(R.string.settings_tutorial_ch6_s4_title, R.string.settings_tutorial_ch6_s4_content),
                TutorialSection(R.string.settings_tutorial_ch6_s5_title, R.string.settings_tutorial_ch6_s5_content),
            ),
        )
    }

    // 第七章:常见问题
    item {
        TutorialChapter(
            icon = Icons.AutoMirrored.Outlined.HelpOutline,
            titleRes = R.string.settings_tutorial_ch7_title,
            sections = listOf(
                TutorialSection(R.string.settings_tutorial_ch7_s1_title, R.string.settings_tutorial_ch7_s1_content),
                TutorialSection(R.string.settings_tutorial_ch7_s2_title, R.string.settings_tutorial_ch7_s2_content),
                TutorialSection(R.string.settings_tutorial_ch7_s3_title, R.string.settings_tutorial_ch7_s3_content),
                TutorialSection(R.string.settings_tutorial_ch7_s4_title, R.string.settings_tutorial_ch7_s4_content),
                TutorialSection(R.string.settings_tutorial_ch7_s5_title, R.string.settings_tutorial_ch7_s5_content),
                TutorialSection(R.string.settings_tutorial_ch7_s6_title, R.string.settings_tutorial_ch7_s6_content),
            ),
        )
    }
}

/**
 * 教程章节卡片 — 图标 + 标题 + 多个小节。
 * 使用 MuseShapes.large 圆角(18dp),对标 iOS 设置分组卡片风格。
 */
@Composable
private fun TutorialChapter(
    icon: ImageVector,
    titleRes: Int,
    sections: List<TutorialSection>,
) {
    val title = stringResource(titleRes)
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
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

/** 教程小节 — 标题资源 id + 正文资源 id。 */
private class TutorialSection(
    val titleRes: Int,
    val contentRes: Int,
)
