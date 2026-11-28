package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseIconSizes
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
    val mcpStatuses by mcpRegistry.serverStatuses.collectAsStateWithLifecycle()
    val mcpReadyCount = mcpServers.count { mcpStatuses[it.id]?.tools?.isNotEmpty() == true }
    val skills by skillRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val (skillCount, packageCount) = skillAndPackageCounts(skills)

    // 有限高度收口与状态栏/导航栏避让由 SettingsSubPageScaffold 统一承担：
    // 此前这里自己拼 MusePageScaffold + BoxWithConstraints，同一套边到边防抖在两处各写一遍，
    // 迟早会有一处漏改。
    SettingsSubPageScaffold(
        title = stringResource(R.string.connection_center_title),
        onBack = onBack,
    ) {
        item(key = "connection_center_entries") {
            CardGroup {
                navigationItem(
                    key = "channels",
                    title = stringResource(R.string.connection_channels_title),
                    subtitle = stringResource(R.string.connection_channels_subtitle, channels.size),
                    onClick = onOpenChannels,
                    leadingContent = { ConnectionEntryIcon(MuseIcons.chat) },
                )
                navigationItem(
                    key = "mcp",
                    title = stringResource(R.string.connection_mcp_title),
                    subtitle = stringResource(R.string.mcp_connection_summary, mcpReadyCount, mcpServers.size),
                    onClick = onOpenMcp,
                    leadingContent = { ConnectionEntryIcon(MuseIcons.share) },
                )
                navigationItem(
                    key = "plugins",
                    title = stringResource(R.string.connection_plugins_title),
                    subtitle = stringResource(
                        R.string.connection_plugins_subtitle,
                        pluginInstalledCount(skills),
                    ),
                    onClick = onOpenPlugins,
                    leadingContent = { ConnectionEntryIcon(MuseIcons.puzzle) },
                )
                navigationItem(
                    key = "skills",
                    title = stringResource(R.string.connection_skills_title),
                    subtitle = stringResource(
                        R.string.connection_skills_subtitle,
                        skillCount,
                        packageCount,
                    ),
                    onClick = onOpenSkills,
                    leadingContent = { ConnectionEntryIcon(MuseIcons.bolt) },
                )
                navigationItem(
                    key = "tools",
                    title = stringResource(R.string.connection_tools_title),
                    onClick = onOpenToolPermissions,
                    leadingContent = { ConnectionEntryIcon(MuseIcons.shield) },
                )
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
internal fun pluginInstalledCount(skills: List<io.zer0.muse.data.skill.SkillEntity>): Int = skills.count { it.id.startsWith("plugin_") }

/** 连接中心导航行的前置图标块 — 圆底 + 主题内容色，与设置族其余导航行同一档尺寸。 */
@Composable
private fun ConnectionEntryIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(MuseIconSizes.topBarSolid)
            .clip(CircleShape)
            .background(MuseActionColors.tonalContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MuseActionColors.tonalContent,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
    }
}
