package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
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
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.largeCard
import org.koin.compose.koinInject

/**
 * v2.0.1: 连接中心 — 一页看全"MUSE 连接了什么"（渠道 / MCP / 插件 / 工具权限）。
 *
 * 每项一张导航卡（实时状态摘要 + 跳转现有设置页），对齐 Meta Muse 的连接管理体验：
 * 连接与授权集中可见，而不散落在各个设置子页里。
 */
@Composable
fun ConnectionCenterScreen(
    onBack: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenToolPermissions: () -> Unit,
) {
    val channelManager: io.zer0.muse.channel.ChannelManager = koinInject()
    val mcpRegistry: io.zer0.muse.mcp.McpRegistry = koinInject()
    val channels by channelManager.channels.collectAsStateWithLifecycle(initialValue = emptyList())
    val mcpServers by mcpRegistry.servers.collectAsStateWithLifecycle(initialValue = emptyList())

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
                        icon = Icons.Outlined.Chat,
                        title = stringResource(R.string.connection_channels_title),
                        subtitle = stringResource(R.string.connection_channels_subtitle, channels.size),
                        onClick = onOpenChannels,
                    )
                }
                item {
                    ConnectionEntryCard(
                        icon = Icons.Outlined.Share,
                        title = stringResource(R.string.connection_mcp_title),
                        subtitle = stringResource(R.string.connection_mcp_subtitle, mcpServers.size),
                        onClick = onOpenMcp,
                    )
                }
                item {
                    ConnectionEntryCard(
                        icon = Icons.Outlined.Extension,
                        title = stringResource(R.string.connection_plugins_title),
                        subtitle = stringResource(R.string.connection_plugins_subtitle),
                        onClick = onOpenPlugins,
                    )
                }
                item {
                    ConnectionEntryCard(
                        icon = Icons.Outlined.Security,
                        title = stringResource(R.string.connection_tools_title),
                        subtitle = stringResource(R.string.connection_tools_subtitle),
                        onClick = onOpenToolPermissions,
                    )
                }
            }
        }
    }
}

/** v2.0.1: 连接中心导航卡 — 图标 + 标题 + 状态摘要 + 箭头。 */
@Composable
private fun ConnectionEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
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
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
