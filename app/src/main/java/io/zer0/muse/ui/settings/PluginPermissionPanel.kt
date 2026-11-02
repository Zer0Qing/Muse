package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginCapabilityAudit
import io.zer0.muse.data.plugin.PluginCapabilityLedgers
import io.zer0.muse.data.plugin.PluginCapabilityGateway
import io.zer0.muse.data.plugin.PluginPermissionTier
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.theme.MusePaddings
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

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
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

        if (snapshot.nodeRuntime) {
            // 7-4：node 档与 js 档的执行载体不同（子进程 + vm 上下文），
            // 不说清楚就会被当成"和别的插件一样"，而它恰恰是**默认档**。
            RuntimeNote(text = stringResource(R.string.plugin_permission_node_note))
        }

        if (snapshot.pythonRuntime) {
            PythonTrustRow(
                allowed = snapshot.pythonAllowed,
                onToggle = { next ->
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

        CapabilityRows(snapshot.declared) { capability, next ->
            gateway.grant(
                pluginId = pluginId,
                capability = capability,
                allowed = next,
                declared = declaredCapabilities,
                tier = PluginPermissionTier.ALWAYS,
            )
            revision++
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

/**
 * Python 运行时开关：默认关闭，且**不来自清单声明**。
 *
 * 文案必须把差别说清楚 —— Python 插件跑在同 UID 子进程里，能直接读写应用数据目录，
 * 桥接闸门与文件沙盒都管不到它；这不是"再多一项能力"，而是另一级信任。
 */
@Composable
private fun PythonTrustRow(
    allowed: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Text(
                    text = stringResource(R.string.plugin_permission_python_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                MuseSwitch(checked = allowed, onCheckedChange = onToggle)
            }
            Text(
                text = stringResource(R.string.plugin_permission_python_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 运行时说明行：node 档要说清它与 js 的差别（默认档、可中断、能力一律经账本）。 */
@Composable
private fun RuntimeNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 已声明能力逐行开关。 */
@Composable
private fun CapabilityRows(
    rows: List<CapabilityRow>,
    onToggle: (String, Boolean) -> Unit,
) {
    if (rows.isEmpty()) {
        Text(
            text = stringResource(R.string.plugin_permission_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    rows.forEach { row ->
        // 显示"这会做什么"而不是能力标识符：用户点的是后果，不是键名。
        val label = PluginCapabilityCopy.text(LocalContext.current, row.capability)
        val sensitive = PluginCapabilityCopy.isSensitive(row.capability)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (sensitive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = row.capability },
            )
            MuseSwitch(checked = row.allowed, onCheckedChange = { next -> onToggle(row.capability, next) })
        }
    }
}

/** 被拒但插件确实在用的能力：给出"本次会话允许"的一次性出口。 */
@Composable
private fun DeniedCallsBlock(
    attempts: List<PluginCapabilityAudit.Event>,
    onAllowSession: (String) -> Unit,
) {
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
                modifier = Modifier.weight(1f),
            )
            TextAction(
                text = stringResource(R.string.plugin_permission_allow_session),
                onClick = { onAllowSession(attempt.capability) },
            )
        }
    }
}

/** 最近调用记录（能力判定级，不是完整请求日志）。 */
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
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Text(
                text = stringResource(R.string.plugin_permission_section_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (pendingCount > 0) {
                // 有待审能力：用小圆点提醒，不新增长文案（也不依赖翻译宽度）
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            }
            Text(
                text = stringResource(
                    if (expanded) R.string.plugin_permission_collapse else R.string.plugin_permission_expand,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        fun from(
            gateway: PluginCapabilityGateway,
            pluginId: String,
            declared: List<String>,
            runtime: String = "js",
        ): PermissionSnapshot {
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

        private fun canonical(capability: String): String =
            PluginCapabilityGateway.CAPABILITY_ALIASES[capability] ?: capability
    }
}

private data class CapabilityRow(val capability: String, val allowed: Boolean)

@Composable
private fun WarningBlock(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextAction(text = actionLabel, onClick = onAction)
        }
    }
}

/** 小面积可点文本：面板里用文字按钮比 Button 更克制，也不会把行高撑开。 */
@Composable
private fun TextAction(
    text: String,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(4.dp)
            .clickable(onClick = onClick),
    )
}

private fun timeText(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
