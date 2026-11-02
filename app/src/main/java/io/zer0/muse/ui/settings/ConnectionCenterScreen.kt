package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.largeCard
import org.koin.compose.koinInject

/**
 * 扩展与连接中心 — 一页看全「MUSE 接了什么、能干什么」
 * （渠道 / MCP / 插件 / 技能与技能包 / 工具权限）。
 *
 * 为什么把技能放进来：用户想"给 Muse 加点东西"时，原本要先自己判断该去插件页还是技能页，
 * 而技能在设置里根本没有入口 —— 中枢页缺哪一类，那一类就只能靠背路径名找到。
 *
 * 这里只做"可见 + 跳转"，各类详情仍在原来的页面：把两套列表并成一套的收益是"找得到"，
 * 代价是重写两个千行页面并让老深链失效 —— 不划算。
 */
@Composable
fun ConnectionCenterScreen(
    onBack: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenToolPermissions: () -> Unit,
) {
    val channelManager: io.zer0.muse.channel.ChannelManager = koinInject()
    val mcpRegistry: io.zer0.muse.mcp.McpRegistry = koinInject()
    val skillRepository: io.zer0.muse.data.skill.SkillRepository = koinInject()
    val channels by channelManager.channels.collectAsStateWithLifecycle(initialValue = emptyList())
    val mcpServers by mcpRegistry.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val skills by skillRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val (skillCount, packageCount) = skillAndPackageCounts(skills)

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.connection_center_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // v2.0.1 fix（真机崩溃）: OPPO 等边到边路径下 Scaffold content 可能收到无限高度约束，
        // 直接 Column+verticalScroll / Lazy 系会抛 IllegalStateException。
        // 沿用设置域既有防抖模式：BoxWithConstraints + boundedSettingsScrollHeight 收口有限高度。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val boundedHeight = boundedSettingsScrollHeight(
                parentMaxHeight = maxHeight,
                windowHeight = LocalConfiguration.current.screenHeightDp.dp,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = boundedHeight),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = MusePaddings.screen,
                    end = MusePaddings.screen,
                    top = 12.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    ConnectionEntryCard(
                        icon = MuseIcons.chat,
                        title = stringResource(R.string.connection_channels_title),
                        subtitle = stringResource(R.string.connection_channels_subtitle, channels.size),
                        onClick = onOpenChannels,
                    )
                }
                item {
                    ConnectionEntryCard(
                        icon = MuseIcons.share,
                        title = stringResource(R.string.connection_mcp_title),
                        subtitle = stringResource(R.string.connection_mcp_subtitle, mcpServers.size),
                        onClick = onOpenMcp,
                    )
                }
                item {
                    ConnectionEntryCard(
                        icon = MuseIcons.puzzle,
                        title = stringResource(R.string.connection_plugins_title),
                        subtitle = stringResource(R.string.connection_plugins_subtitle, pluginInstalledCount(skills)),
                        onClick = onOpenPlugins,
                    )
                }
                item {
                    ConnectionEntryCard(
                        icon = MuseIcons.bolt,
                        title = stringResource(R.string.connection_skills_title),
                        subtitle = stringResource(R.string.connection_skills_subtitle, skillCount, packageCount),
                        onClick = onOpenSkills,
                    )
                }
                item {
                    ConnectionEntryCard(
                        icon = MuseIcons.shield,
                        title = stringResource(R.string.connection_tools_title),
                        subtitle = stringResource(R.string.connection_tools_subtitle),
                        onClick = onOpenToolPermissions,
                    )
                }
            }
        }
    }
}

/**
 * 技能数与技能包数的统计。
 *
 * 技能包的判据只有一处：`implementationKotlin` 的 `prompt_file:` 前缀
 * （由 [io.zer0.muse.tools.SkillImporter.promptFilePath] 判）。不再新造一份前缀判断 ——
 * 两处判"什么算技能包"，早晚有一处认、另一处不认。
 *
 * 内置技能不计：中枢页要反映的是"用户自己加了什么"，内置的再多也不是用户资产。
 */
internal fun skillAndPackageCounts(skills: List<io.zer0.muse.data.skill.SkillEntity>): Pair<Int, Int> {
    val builtInIds = io.zer0.muse.tools.SkillExecutor.BUILT_IN_SKILLS.map { it.id }.toSet()
    val custom = skills.filterNot { it.id in builtInIds }
    return custom.size to custom.count {
        io.zer0.muse.tools.SkillImporter.promptFilePath(it.implementationKotlin) != null
    }
}

/**
 * 已安装插件数（以插件工具在技能表里的 `plugin_` 前缀为准）。
 *
 * 这里刻意不做更精确的口径：中枢页的职责是"让人知道有多少、点进去看细节"，
 * 为了一个数字把 PluginManager 的注册表加载逻辑复制进来，只会多一处会过期的真相。
 */
internal fun pluginInstalledCount(skills: List<io.zer0.muse.data.skill.SkillEntity>): Int =
    skills.count { it.id.startsWith("plugin_") }

/** 连接中心导航卡 — 图标 + 标题 + 状态摘要 + 箭头。 */
@Composable
private fun ConnectionEntryCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    MuseSurface(
        onClick = onClick,
        shape = MuseShapes.largeCard,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = MuseIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
