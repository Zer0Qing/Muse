package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.tools.ToolApprovalPolicy

/**
 * Tool approval card (RikkaHub GenerationHandler.kt port).
 *
 * Shown when a tool call requires user approval before execution.
 * Displays tool name, argument preview, and approve/deny buttons.
 *
 * v1.0.20: 新增"始终允许"/"始终拒绝"按钮 — 点击后通过 [onPersistPolicy]
 * 持久化到 [io.zer0.muse.tools.ToolConfigStore],后续该工具调用直接走对应策略,
 * 不再弹审批卡片。
 */
@Composable
fun ToolApprovalCard(
    toolName: String,
    argumentsPreview: String,
    onApprove: () -> Unit,
    onDeny: (reason: String) -> Unit,
    alwaysAllow: Boolean,
    onAlwaysAllowChanged: (Boolean) -> Unit,
    /** v1.0.16: 本次开启期间批准全部工具 */
    appRunAllowAll: Boolean = false,
    /** v1.0.16: 本次开启期间批准全部 复选状态变更回调 */
    onAppRunAllowAllChanged: (Boolean) -> Unit = {},
    /**
     * v1.0.20: 持久化单工具策略回调。
     *
     * "始终允许"按钮点击时传入 [ToolApprovalPolicy.ALWAYS_ALLOW],
     * "始终拒绝"按钮点击时传入 [ToolApprovalPolicy.ALWAYS_DENY]。
     * 调用方应在此回调内:
     *  1. 调用 [io.zer0.muse.tools.ToolConfigStore.setPolicy] 持久化策略
     *  2. 同步触发 onApprove / onDeny 处理本次调用
     *
     * 默认空实现(向后兼容,不接通持久化时按钮仅触发本次批准/拒绝)。
     */
    onPersistPolicy: (ToolApprovalPolicy) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showDenyReason by remember { mutableStateOf(false) }
    var denyReason by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.tool_approval_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            // Arguments preview (truncated)
            val preview = if (argumentsPreview.length > 200) {
                argumentsPreview.take(200) + "..."
            } else {
                argumentsPreview
            }
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
            )

            // "始终允许"复选框(本次批准时附带勾选,与"始终允许"按钮的区别:
            // 复选框是 onApprove 时附带 alwaysAllow=true,按钮是直接持久化策略)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = alwaysAllow,
                    onCheckedChange = onAlwaysAllowChanged,
                )
                Text(
                    text = stringResource(R.string.tool_approval_always_allow, toolName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // v1.0.16: "本次开启期间批准全部工具"复选框(内存态,不持久化,冷启动后失效)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = appRunAllowAll,
                    onCheckedChange = onAppRunAllowAllChanged,
                )
                Text(
                    text = stringResource(R.string.tool_approval_allow_all_this_run),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 操作按钮行(两行布局:本次操作 + 持久化策略)
            // v1.0.20: 第一行 — 本次批准 / 本次拒绝
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onApprove,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.tool_approval_approve), style = MaterialTheme.typography.labelMedium)
                }

                TextButton(
                    onClick = {
                        if (showDenyReason) {
                            onDeny(denyReason)
                        } else {
                            showDenyReason = true
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(if (showDenyReason) stringResource(R.string.tool_approval_confirm_deny) else stringResource(R.string.tool_approval_deny), style = MaterialTheme.typography.labelMedium)
                }
            }

            // v1.0.20: 第二行 — 始终允许(持久化)/ 始终拒绝(持久化)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        // 持久化 ALWAYS_ALLOW 策略,并触发本次批准
                        onPersistPolicy(ToolApprovalPolicy.ALWAYS_ALLOW)
                        onApprove()
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.tool_approval_always_approve), style = MaterialTheme.typography.labelMedium)
                }

                TextButton(
                    onClick = {
                        // 持久化 ALWAYS_DENY 策略,并触发本次拒绝
                        onPersistPolicy(ToolApprovalPolicy.ALWAYS_DENY)
                        onDeny("")
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.tool_approval_always_deny), style = MaterialTheme.typography.labelMedium)
                }
            }

            // 拒绝理由输入框
            if (showDenyReason) {
                androidx.compose.material3.OutlinedTextField(
                    value = denyReason,
                    onValueChange = { denyReason = it },
                    label = { Text(stringResource(R.string.tool_approval_deny_reason)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
