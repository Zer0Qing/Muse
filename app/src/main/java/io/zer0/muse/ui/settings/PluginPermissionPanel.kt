package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginCapabilityAudit
import io.zer0.muse.data.plugin.PluginCapabilityGateway
import io.zer0.muse.data.plugin.PluginCapabilityLedgers
import io.zer0.muse.data.plugin.PluginPermissionTier
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.settings.StatusDot
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import java.text.DateFormat
import java.util.Date

/**
 * 插件权限面板：让用户**看见并改掉**一个插件能做什么（P0-3）。
 *
 * 为什么必须有它：P0-2 之后能力声明真的会拦请求了。如果用户只能在安装那一刻看一眼清单，
 * 那么"以前忘声明也能用、现在被拒"的存量插件就没有出路 —— 正确性会直接变成用户体验事故。
 * 所以这里同时提供：撤销已声明能力、批准升级新增的能力、以及**被拒调用的一键会话放行**。
 *
 * 单独成文件而不是塞进 [PluginManagePage]：那个文件已经 97KB，再往里加长列表会让它彻底没法读。
 */
@Composable
fun PluginPermissionPanel(
    gateway: PluginCapabilityGateway,
    pluginId: String,
    declaredCapabilities: List<String>,
    modifier: Modifier = Modifier,
    /** `manifest.runtime`；为 `python` 时额外渲染高信任开关（P0-5）。 */
    runtime: String = "js",
) {
    // 账本是网关内部状态，Compose 看不见它；用改版本号的方式让每次操作后重新读一次。
    var revision by remember { mutableIntStateOf(0) }
    val snapshot = remember(pluginId, declaredCapabilities, runtime, revision) {
        PermissionSnapshot.from(gateway, pluginId, declaredCapabilities, runtime)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Text(
            text = stringResource(R.string.plugin_permission_section_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.plugin_permission_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 待批块独立成块：它是"先看一眼再决定"的信息，混进开关卡片会被当成普通行扫过去。
        if (snapshot.pendingApproval.isNotEmpty()) {
            WarningBlock(
                title = stringResource(R.string.plugin_permission_pending_title),
                detail = snapshot.pendingApproval.joinToString("、"),
                actionLabel = stringResource(R.string.plugin_permission_approve),
                onAction = {
                    gateway.approveDeclaration(pluginId, declaredCapabilities)
                    revision++
                },
            )
        }

        // 运行时说明与能力开关收敛进同一张分组卡：同一份账本、同一套行形态，
        // 用户不必分辨"这行是说明还是开关"。
        CardGroup {
            if (snapshot.nodeRuntime) {
                // 7-4：node 档与 js 档的执行载体不同（子进程 + vm 上下文），
                // 不说清楚就会被当成"和别的插件一样"，而它恰恰是**默认档**。
                content(key = "node_runtime_note") {
                    Text(
                        text = stringResource(R.string.plugin_permission_node_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = MuseSpacing.lg,
                            vertical = MuseSpacing.md,
                        ),
                    )
                }
            }
            if (snapshot.pythonRuntime) {
                // Python 档是另一级信任（同 UID 子进程、能直接读写应用数据目录），
                // 差别写成副标题紧跟开关，而不是另起一段让用户自己关联。
                switchItem(
                    key = "python_runtime",
                    title = stringResource(R.string.plugin_permission_python_title),
                    subtitle = stringResource(R.string.plugin_permission_python_warning),
                    checked = snapshot.pythonAllowed,
                    onCheckedChange = { next ->
                        gateway.grant(
                            pluginId = pluginId,
                            capability = PluginCapabilityGateway.RUNTIME_PYTHON,
                            allowed = next,
                            declared = declaredCapabilities,
                        )
                        revision++
                    },
                )
            }
            snapshot.declared.forEach { row ->
                // 显示"这会做什么"而不是能力标识符：用户点的是后果，不是键名。
                // 标识符仍挂在开关的无障碍描述上 —— 文案随语言变，键不能变。
                val label = PluginCapabilityCopy.text(LocalContext.current, row.capability)
                val sensitive = PluginCapabilityCopy.isSensitive(row.capability)
                item(
                    key = row.capability,
                    onClick = {
                        gateway.grant(
                            pluginId = pluginId,
                            capability = row.capability,
                            allowed = !row.allowed,
                            declared = declaredCapabilities,
                            tier = PluginPermissionTier.ALWAYS,
                        )
                        revision++
                    },
                    trailingContent = {
                        MuseSwitch(
                            checked = row.allowed,
                            onCheckedChange = { next ->
                                gateway.grant(
                                    pluginId = pluginId,
                                    capability = row.capability,
                                    allowed = next,
                                    declared = declaredCapabilities,
                                    tier = PluginPermissionTier.ALWAYS,
                                )
                                revision++
                            },
                            contentDescription = row.capability,
                        )
                    },
                    headlineContent = {
                        Text(
                            text = label,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (sensitive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                )
            }
            if (snapshot.declared.isEmpty() && !snapshot.pythonRuntime && !snapshot.nodeRuntime) {
                content(key = "no_capabilities") {
                    Text(
                        text = stringResource(R.string.plugin_permission_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = MuseSpacing.lg,
                            vertical = MuseSpacing.md,
                        ),
                    )
                }
            }
        }

        DeniedCallsBlock(snapshot.blockedAttempts) { capability ->
            // 逃生口：不写进账本，进程结束即失效 —— 让老插件能跑，又不静默永久扩权。
            gateway.grant(
                pluginId = pluginId,
                capability = capability,
                allowed = true,
                declared = declaredCapabilities,
                tier = PluginPermissionTier.SESSION,
            )
            revision++
        }

        RecentCallsBlock(snapshot.recent)
    }
}

/** 被拒但插件确实在用的能力：给出"本次会话允许"的一次性出口。 */
@Composable
private fun DeniedCallsBlock(attempts: List<PluginCapabilityAudit.Event>, onAllowSession: (String) -> Unit) {
    if (attempts.isEmpty()) return
    Text(
        text = stringResource(R.string.plugin_permission_denied_title),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    attempts.forEach { attempt ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Text(
                text = stringResource(R.string.plugin_permission_denied_row, attempt.action, attempt.capability),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextAction(
                text = stringResource(R.string.plugin_permission_allow_session),
                onClick = { onAllowSession(attempt.capability) },
            )
        }
    }
}

/**
 * 最近调用记录（能力判定级，不是完整请求日志）。
 *
 * 这些行不进卡片：它们按时间追加、条数还会变，卡片分隔线在这里只会变成噪音，
 * 而且整块是只读流水账 —— 包成"可点的行"反而误导。
 */
@Composable
private fun RecentCallsBlock(events: List<PluginCapabilityAudit.Event>) {
    Text(
        text = stringResource(R.string.plugin_permission_recent_title),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    if (events.isEmpty()) {
        Text(
            text = stringResource(R.string.plugin_permission_recent_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    events.forEach { event ->
        Text(
            text = stringResource(
                R.string.plugin_permission_recent_row,
                timeText(event.timestamp),
                event.action,
                if (event.allowed) "✓" else "✗",
            ),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (event.allowed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/**
 * 插件行里的折叠入口：默认收起，避免把每个插件卡撑高；**有未批准的新能力时给一个圆点提示**。
 *
 * 网关在内部按 Context 取（进程内单例），是为了不往 `InstalledPluginRow` 加参数 ——
 * 那个函数被 detekt 基线按签名压着，改签名会让它的长方法/复杂度告警集体复活。
 */
@Composable
fun PluginPermissionDisclosure(
    pluginId: String,
    declaredCapabilities: List<String>,
    modifier: Modifier = Modifier,
    runtime: String = "js",
) {
    val context = LocalContext.current
    val gateway = remember(context) { PluginCapabilityLedgers.shared(context) }
    var expanded by remember(pluginId) { mutableStateOf(false) }
    val pendingCount = remember(pluginId, declaredCapabilities) {
        gateway.addedCapabilities(pluginId, declaredCapabilities).size
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = MuseSpacing.smTight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Text(
                text = stringResource(R.string.plugin_permission_section_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pendingCount > 0) {
                // 有待审能力：用状态圆点提醒，不新增长文案（也不依赖翻译宽度）
                StatusDot(color = MaterialTheme.statusColors.error)
            }
            Text(
                text = stringResource(
                    if (expanded) R.string.plugin_permission_collapse else R.string.common_expand,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            PluginPermissionPanel(
                gateway = gateway,
                pluginId = pluginId,
                declaredCapabilities = declaredCapabilities,
                runtime = runtime,
            )
        }
    }
}

/** 面板一次渲染需要的账本快照。 */
private data class PermissionSnapshot(
    val declared: List<CapabilityRow>,
    val pendingApproval: List<String>,
    val blockedAttempts: List<PluginCapabilityAudit.Event>,
    val recent: List<PluginCapabilityAudit.Event>,
    val pythonRuntime: Boolean = false,
    val pythonAllowed: Boolean = false,
    val nodeRuntime: Boolean = false,
) {
    companion object {
        fun from(gateway: PluginCapabilityGateway, pluginId: String, declared: List<String>, runtime: String = "js"): PermissionSnapshot {
            val granted = gateway.grantedCapabilities(pluginId, declared)
            val audit = gateway.recentAudit()
            val declaredNames = declared.map(::canonical)
            return PermissionSnapshot(
                nodeRuntime = runtime.equals("node", ignoreCase = true),
                pythonRuntime = runtime.equals("python", ignoreCase = true),
                pythonAllowed = gateway.isApproved(pluginId, PluginCapabilityGateway.RUNTIME_PYTHON),
                declared = declaredNames.map { CapabilityRow(it, it in granted) },
                pendingApproval = gateway.addedCapabilities(pluginId, declared).sorted(),
                blockedAttempts = audit.filter { event ->
                    !event.allowed && canonical(event.capability) !in declaredNames && event.capability != "-"
                }.distinctBy { it.capability }.takeLast(4).reversed(),
                recent = audit.filter { it.capability != "-" }.takeLast(6).reversed(),
            )
        }

        private fun canonical(capability: String): String = PluginCapabilityGateway.CAPABILITY_ALIASES[capability] ?: capability
    }
}

private data class CapabilityRow(val capability: String, val allowed: Boolean)

@Composable
private fun WarningBlock(title: String, detail: String, actionLabel: String, onAction: () -> Unit) {
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.statusColors.warningContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MuseSpacing.md, vertical = MuseSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextAction(text = actionLabel, onClick = onAction)
        }
    }
}

/** 小面积可点文本：面板里用文字按钮比 Button 更克制，也不会把行高撑开。 */
@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = Modifier
            .padding(MuseSpacing.xs)
            .clickable(onClick = onClick),
    )
}

private fun timeText(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
