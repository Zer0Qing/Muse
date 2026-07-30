package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.tools.ToolApprovalPolicy
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v1.0.20: 工具批准管理页 — 单工具粒度的审批策略。
 *
 * 与 [io.zer0.muse.ui.ToolsScreen] 的区别:
 *  - ToolsScreen 是只读展示(工具列表 + 详情弹窗),不可控
 *  - 本页是可写管理页:每个工具可在三档策略间切换
 *    - ALWAYS_ALLOW:始终允许(等价于该工具的白名单)
 *    - ASK_EVERY_TIME:每次询问(默认)
 *    - ALWAYS_DENY:始终拒绝(等价于禁用该工具)
 *
 * 三档策略持久化到 [ToolConfigStore],由 [io.zer0.muse.tools.ToolPermissionResolver]
 * 在每次工具调用前读取并决定审批状态。
 *
 * 页面结构:
 *  1. 顶部搜索框(按工具名/描述过滤)
 *  2. 按风险等级分组:SAFE / NORMAL / HIGH
 *  3. 每个工具一行:名称 + 描述 + 三档分段控件
 *  4. 底部说明卡片:解释三档策略与会话权限模式的关系
 *
 * 风格与 [ChatSettingsPage] 一致(SettingsSubPageScaffold + SettingsGroup + MuseSegmentedControl)。
 */
@Composable
fun ToolsSettingsPage(
    onBack: () -> Unit,
) {
    val toolRegistry: ToolRegistry = koinInject()
    val toolConfigStore: ToolConfigStore = koinInject()
    val scope = rememberCoroutineScope()

    // 工具列表(同步读取 ConcurrentHashMap,无需 IO)
    val tools = remember { toolRegistry.listTools().sortedBy { it.name } }

    // 当前已持久化的策略映射(toolName → policy)
    val policies by toolConfigStore.policiesFlow.collectAsStateWithLifecycle(initialValue = emptyMap())

    // 搜索查询(本地状态,不持久化)
    var searchQuery by remember { mutableStateOf("") }

    // 按搜索词过滤工具(空查询时返回全部)
    val filteredTools = remember(searchQuery, tools) {
        if (searchQuery.isBlank()) tools
        else tools.filter { tool ->
            tool.name.contains(searchQuery, ignoreCase = true) ||
                tool.description.contains(searchQuery, ignoreCase = true)
        }
    }

    // 按风险等级分组(顺序:SAFE → NORMAL → HIGH)
    val grouped = remember(filteredTools) {
        filteredTools.groupBy { it.riskLevel }.toSortedMap(compareBy { it.ordinal })
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.tools_settings_title),
        onBack = onBack,
    ) {
        // ── 顶部搜索框 ────────────────────────────────────────────────
        item(key = "search") {
            MuseTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.tools_settings_search_hint)) },
                leadingIcon = {
                    Icon(TablerIcons.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            TablerIcons.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                singleLine = true,
            )
        }

        // ── 统计信息 ─────────────────────────────────────────────────
        item(key = "stats") {
            val allowedCount = tools.count { policies[it.name] == ToolApprovalPolicy.ALWAYS_ALLOW }
            val deniedCount = tools.count { policies[it.name] == ToolApprovalPolicy.ALWAYS_DENY }
            val askCount = tools.size - allowedCount - deniedCount
            Text(
                text = stringResource(
                    R.string.tools_settings_stats,
                    tools.size,
                    allowedCount,
                    askCount,
                    deniedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }

        // ── 按风险等级分组展示 ───────────────────────────────────────
        grouped.forEach { (riskLevel, toolsInGroup) ->
            item(key = "header_${riskLevel.name}") {
                SectionLabel(stringResource(riskLevelSectionTitle(riskLevel)))
            }
            item(key = "group_${riskLevel.name}") {
                SettingsGroup {
                    toolsInGroup.forEachIndexed { index, tool ->
                        if (index > 0) SettingsGroupDivider()
                        ToolPolicyRow(
                            toolName = tool.name,
                            toolDescription = tool.description,
                            riskLevel = tool.riskLevel,
                            currentPolicy = policies[tool.name] ?: ToolApprovalPolicy.ASK_EVERY_TIME,
                            onPolicyChange = { newPolicy ->
                                scope.launch {
                                    toolConfigStore.setPolicy(tool.name, newPolicy)
                                }
                            },
                        )
                    }
                }
            }
        }

        // ── 空态(搜索无结果)──────────────────────────────────────
        if (filteredTools.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = TablerIcons.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.tools_settings_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        // ── 底部说明卡片 ─────────────────────────────────────────────
        item(key = "footer_header") {
            SectionLabel(stringResource(R.string.tools_settings_footer_section))
        }
        item(key = "footer") {
            SettingsGroup {
                PolicyExplanationRow(
                    icon = TablerIcons.Check,
                    iconTint = Color(0xFF2E7D32),
                    title = stringResource(R.string.tools_settings_policy_always_allow),
                    description = stringResource(R.string.tools_settings_policy_always_allow_desc),
                )
                SettingsGroupDivider()
                PolicyExplanationRow(
                    icon = TablerIcons.Help,
                    iconTint = Color(0xFFEF6C00),
                    title = stringResource(R.string.tools_settings_policy_ask),
                    description = stringResource(R.string.tools_settings_policy_ask_desc),
                )
                SettingsGroupDivider()
                PolicyExplanationRow(
                    icon = TablerIcons.Ban,
                    iconTint = Color(0xFFD32F2F),
                    title = stringResource(R.string.tools_settings_policy_always_deny),
                    description = stringResource(R.string.tools_settings_policy_always_deny_desc),
                )
            }
        }
    }
}

/**
 * 单个工具的策略行 — 图标 + 名称 + 描述 + 三档分段控件。
 *
 * 策略切换会立即持久化到 [ToolConfigStore],下次工具调用时生效。
 */
@Composable
private fun ToolPolicyRow(
    toolName: String,
    toolDescription: String,
    riskLevel: ToolRiskLevel,
    currentPolicy: ToolApprovalPolicy,
    onPolicyChange: (ToolApprovalPolicy) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 风险等级图标
        val (icon, iconTint) = when (riskLevel) {
            ToolRiskLevel.SAFE -> TablerIcons.ShieldCheck to Color(0xFF2E7D32)
            ToolRiskLevel.NORMAL -> TablerIcons.Tools to Color(0xFFEF6C00)
            ToolRiskLevel.HIGH -> TablerIcons.AlertTriangle to Color(0xFFD32F2F)
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = toolName,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = MuseMonoFontFamily,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (toolDescription.isNotBlank()) {
                Text(
                    text = toolDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            // 三档分段控件
            val options = listOf(
                stringResource(R.string.tools_settings_policy_always_allow_short),
                stringResource(R.string.tools_settings_policy_ask_short),
                stringResource(R.string.tools_settings_policy_always_deny_short),
            )
            val selectedIndex = when (currentPolicy) {
                ToolApprovalPolicy.ALWAYS_ALLOW -> 0
                ToolApprovalPolicy.ASK_EVERY_TIME -> 1
                ToolApprovalPolicy.ALWAYS_DENY -> 2
            }
            io.zer0.muse.ui.common.form.MuseSegmentedControl(
                options = options,
                selectedIndex = selectedIndex,
                onSelectedChange = { idx ->
                    val newPolicy = when (idx) {
                        0 -> ToolApprovalPolicy.ALWAYS_ALLOW
                        2 -> ToolApprovalPolicy.ALWAYS_DENY
                        else -> ToolApprovalPolicy.ASK_EVERY_TIME
                    }
                    onPolicyChange(newPolicy)
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * 策略说明行 — 图标 + 标题 + 描述(底部说明卡片用)。
 */
@Composable
private fun PolicyExplanationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 把风险等级映射为分组标题资源 ID。 */
private fun riskLevelSectionTitle(level: ToolRiskLevel): Int = when (level) {
    ToolRiskLevel.SAFE -> R.string.tools_settings_section_safe
    ToolRiskLevel.NORMAL -> R.string.tools_settings_section_normal
    ToolRiskLevel.HIGH -> R.string.tools_settings_section_high
}
