package io.zer0.muse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.data.plugin.PluginManifest
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.components.CardGroup
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.tiny
import org.koin.compose.koinInject

/**
 * v1.0.4 P2-3:AI 工具管理页 — 把 ToolRegistry 中已注册但用户不可见的工具列表透出。
 *
 * 后端能力(均已实现):
 *  - [ToolRegistry.listTools] 列出全部 ToolDef(name/description/parameters/riskLevel/category)
 *  - [ToolRegistry.BUILT_IN_TOOL_IDS] 静态内置工具 id 清单
 *  - [ToolRiskLevel] 三级风险等级(SAFE / NORMAL / HIGH),用于权限审批
 *
 * 本页职责:
 *  1. 按分类(built-in / local / mcp)分组列出所有工具
 *  2. 每行展示:name + description + 风险等级颜色徽章
 *  3. 点击查看详情:参数 Schema + required + category + 安全说明
 *  4. 引导用户:"通过助手详情页 → 扩展 → 工具,可控制每个助手使用哪些工具"
 *
 * 注:工具的全局启用/禁用由 AssistantEntity.toolIdsJson 控制(per-Assistant 粒度),
 *     本页不做全局开关(避免与 per-Assistant 机制冲突),只做展示与说明。
 */
@Composable
fun ToolsScreen(
    onBack: () -> Unit,
) {
    val toolRegistry: ToolRegistry = koinInject()
    // listTools() 是同步函数(读 ConcurrentHashMap.values),无需 IO
    val tools = remember { toolRegistry.listTools().sortedBy { it.name } }
    // 按分类分组
    val grouped = remember(tools) {
        tools.groupBy { it.category }.toSortedMap()
    }
    var detailTarget by remember { mutableStateOf<ToolRegistry.ToolDef?>(null) }

    SettingsSubPageScaffold(
        title = stringResource(R.string.tools_screen_title),
        onBack = onBack,
    ) {
        // ── 说明卡片 ────────────────────────────────────────────────────
        item(key = "intro") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.tools_intro_what),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(MusePaddings.itemGap))
                        Text(
                            text = stringResource(R.string.tools_intro_manage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── 工具总数 + 高风险数 ────────────────────────────────────────
        item(key = "stats") {
            val highRiskCount = tools.count { it.riskLevel == ToolRiskLevel.HIGH }
            Text(
                text = stringResource(R.string.tools_stats_count, tools.size, highRiskCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        // ── 按分类分组展示 ─────────────────────────────────────────────
        grouped.forEach { (category, toolsInCategory) ->
            item(key = "category_$category") {
                Text(
                    text = categoryLabel(category),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item(key = "group_$category") {
                CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                    toolsInCategory.forEach { tool ->
                        item(
                            key = tool.name,
                            onClick = { detailTarget = tool },
                        ) {
                            ToolRow(tool = tool)
                        }
                    }
                }
            }
        }

        // ── v1.0.4 (P3-6): 内置插件清单 ──────────────────────────────
        // 后端 PluginManifest.BUILT_IN 声明了 5 个内置插件(image-gen / beautify /
        // media / mcp-bridge / office),含 trust/capabilities/activationEvents。
        // 此处透出给用户,提升能力边界透明度(只读展示,启用/禁用仍由代码控制)。
        item(key = "plugins_section_header") {
            Text(
                text = stringResource(R.string.tools_plugins_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item(key = "plugins_section_desc") {
            Text(
                text = stringResource(R.string.tools_plugins_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        item(key = "plugins_group") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                PluginManifest.BUILT_IN.forEach { plugin ->
                    item(key = plugin.id) {
                        PluginRow(plugin = plugin)
                    }
                }
            }
        }
    }

    // ── 详情弹窗 ─────────────────────────────────────────────────────
    detailTarget?.let { tool ->
        ToolDetailDialog(
            tool = tool,
            onDismiss = { detailTarget = null },
        )
    }
}

/**
 * 单条工具行 — 图标 + name + description + 风险等级徽章。
 *
 * v1.0.4: 点击逻辑由 [CardGroupScope.item] 的 onClick 接管,本组件只负责渲染内容。
 */
@Composable
private fun ToolRow(
    tool: ToolRegistry.ToolDef,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 高风险工具用 Warning 图标,普通/安全用 Build 图标
        val icon = if (tool.riskLevel == ToolRiskLevel.HIGH) {
            Icons.Outlined.Warning
        } else {
            Icons.Outlined.Build
        }
        val iconTint = when (tool.riskLevel) {
            ToolRiskLevel.HIGH -> MaterialTheme.colorScheme.error
            ToolRiskLevel.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
            ToolRiskLevel.SAFE -> MaterialTheme.colorScheme.primary
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tool.name,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = MuseMonoFontFamily,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        RiskBadge(level = tool.riskLevel)
    }
}

/**
 * 风险等级徽章 — 颜色:SAFE 绿 / NORMAL 橙 / HIGH 红。
 */
@Composable
private fun RiskBadge(level: ToolRiskLevel) {
    val (label, color) = when (level) {
        ToolRiskLevel.SAFE -> stringResource(R.string.tools_risk_safe) to Color(0xFF2E7D32)
        ToolRiskLevel.NORMAL -> stringResource(R.string.tools_risk_normal) to Color(0xFFEF6C00)
        ToolRiskLevel.HIGH -> stringResource(R.string.tools_risk_high) to Color(0xFFD32F2F)
    }
    Surface(
        shape = MuseShapes.extraSmall,
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 工具详情弹窗 — 展示参数 Schema + 风险等级 + 安全说明。
 */
@Composable
private fun ToolDetailDialog(
    tool: ToolRegistry.ToolDef,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = tool.name,
        content = {
            // v1.0.7 修复崩溃:MuseDialog 的 content 区已自带 verticalScroll(heightIn(max=420dp)),
            // 此处再嵌套 verticalScroll 会导致"Vertically scrollable component was measured
            // with an infinity maximum height constraints"崩溃。去掉内层 verticalScroll。
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 描述
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))

                // 元信息行:分类 + 风险等级
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.tools_detail_category_label, categoryLabel(tool.category)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    RiskBadge(level = tool.riskLevel)
                }
                Spacer(Modifier.height(16.dp))

                // 参数 Schema
                if (tool.parameters.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.tools_detail_params),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    tool.parameters.forEach { (paramName, paramDesc) ->
                        val required = paramName in tool.required
                        val type = tool.parameterTypes[paramName] ?: "string"
                        ParamRow(
                            name = paramName,
                            type = type,
                            required = required,
                            description = paramDesc,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }

                // execute_javascript 专属安全说明
                if (tool.name == "execute_javascript") {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = MuseShapes.small,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Code,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = stringResource(R.string.tools_detail_sandbox_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.tools_detail_sandbox_content),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
    )
}

/**
 * 参数行 — 名称(等宽)+ 类型 + 必填标记 + 描述。
 */
@Composable
private fun ParamRow(
    name: String,
    type: String,
    required: Boolean,
    description: String,
) {
    Surface(
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(8.dp))
                Surface(
                    shape = MuseShapes.tiny,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                ) {
                    Text(
                        text = type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (required) {
                    Spacer(Modifier.size(6.dp))
                    Surface(
                        shape = MuseShapes.tiny,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = stringResource(R.string.tools_param_required),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 把分类 key 映射为本地化标签。 */
@Composable
private fun categoryLabel(category: String): String = when (category) {
    "built-in" -> stringResource(R.string.tools_category_built_in)
    "local" -> stringResource(R.string.tools_category_local)
    "mcp" -> stringResource(R.string.tools_category_mcp)
    else -> category
}

/**
 * v1.0.4 (P3-6): 内置插件行 — 展示 PluginManifest 的 name/description/trust/capabilities。
 *
 * 只读展示(不带开关):插件的实际启用由代码内部决定,这里仅向用户透出能力边界。
 * trust=full-access 用红色徽章高亮,sandboxed 用中性色,让用户一眼识别高风险插件。
 */
@Composable
private fun PluginRow(plugin: PluginManifest) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Extension,
            contentDescription = null,
            tint = if (plugin.trust == "full-access") {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plugin.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (plugin.description.isNotEmpty()) {
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            // 能力 + 激活事件小字
            val permissionsLabel = stringResource(R.string.tools_permissions_label)
            val activationLabel = stringResource(R.string.tools_activation_label)
            val capsText = buildString {
                if (plugin.capabilities.isNotEmpty()) {
                    append(permissionsLabel)
                    append(plugin.capabilities.joinToString(", "))
                }
                if (plugin.activationEvents.isNotEmpty() && plugin.activationEvents != listOf("onStartup")) {
                    if (isNotEmpty()) append(" · ")
                    append(activationLabel)
                    append(plugin.activationEvents.joinToString(", "))
                }
            }
            if (capsText.isNotEmpty()) {
                Text(
                    text = capsText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MuseMonoFontFamily,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // trust 徽章
        Surface(
            color = if (plugin.trust == "full-access") {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            shape = MuseShapes.small,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(
                text = plugin.trust,
                style = MaterialTheme.typography.labelSmall,
                color = if (plugin.trust == "full-access") {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
