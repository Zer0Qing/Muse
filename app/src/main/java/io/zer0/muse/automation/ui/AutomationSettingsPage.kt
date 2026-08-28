package io.zer0.muse.automation.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.executors.MuseAccessibilityService
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch

/**
 * UI 自动化权限设置页 —— 三层梯度卡片。
 *
 * 每层一张卡片,展示:
 * - 状态指示点(绿=已开启/灰=未开启)
 * - 标题 + 副标题(说明该层能力)
 * - 右侧操作按钮(去开启/已开启/测试)
 *
 * 视觉风格与 Muse 其他设置卡片统一:surfaceVariant 浅灰底、圆角、左侧图标。
 */
@Composable
fun AutomationSettingsPage(
    manager: AutomationManager,
    onBack: () -> Unit = {},
) {
    val state by manager.permissionState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { manager.refreshPermissions() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(MusePaddings.screen),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部说明卡
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "UI 自动化",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "让 Muse 能看到屏幕并替你操作设备,实现跨 App 任务自动化",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 第一层:无障碍
        PermissionCard(
            icon = Icons.Outlined.Shield,
            title = "无障碍服务",
            subtitle = "读取屏幕内容、模拟点击滑动。门槛最低,推荐开启。",
            enabled = state.accessibilityEnabled,
            levelLabel = "第一层",
            onAction = {
                openAutomationSettings(
                    context = context,
                    candidates = listOf(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                        appDetailsIntent(context),
                        Intent(Settings.ACTION_SETTINGS),
                    ),
                ) {
                    MuseToast.show(context.getString(R.string.automation_settings_unavailable))
                }
            },
        )

        // 第二层:Shell
        PermissionCard(
            icon = Icons.Outlined.Terminal,
            title = "Shell (Shizuku / adb)",
            subtitle = "系统级命令、截屏、静默安装。需通过 Shizuku 或 adb 授权。",
            enabled = state.shellEnabled,
            levelLabel = "第二层",
            onAction = {
                // 打开 Shizuku 应用(若已安装),否则按 ROM 能力逐级降级。
                val shizukuIntent = context.packageManager
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                openAutomationSettings(
                    context = context,
                    candidates = buildList {
                        shizukuIntent?.let(::add)
                        add(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        add(appDetailsIntent(context))
                        add(Intent(Settings.ACTION_SETTINGS))
                    },
                ) {
                    MuseToast.show(context.getString(R.string.automation_settings_unavailable))
                }
            },
        )

        // 第三层:Root
        PermissionCard(
            icon = Icons.Outlined.AdminPanelSettings,
            title = "Root 权限",
            subtitle = "完全系统控制,可访问其他 App 数据。需设备已 root。",
            enabled = state.rootEnabled,
            levelLabel = "第三层",
            onAction = {
                // Root 设备通常通过 Magisk 管理,无 Magisk 时回退到应用设置。
                val magiskIntent = context.packageManager
                    .getLaunchIntentForPackage("com.topjohnwu.magisk")
                openAutomationSettings(
                    context = context,
                    candidates = buildList {
                        magiskIntent?.let(::add)
                        add(appDetailsIntent(context))
                        add(Intent(Settings.ACTION_SETTINGS))
                    },
                ) {
                    MuseToast.show(context.getString(R.string.automation_settings_unavailable))
                }
            },
        )

        Spacer(Modifier.height(4.dp))

        // 测试按钮
        Button(
            onClick = {
                scope.launch {
                    testing = true
                    testResult = null
                    val result = runCatching {
                        manager.refreshPermissions()
                        val screen = manager.readScreen()
                        buildString {
                            appendLine("✓ 当前应用: ${screen.packageName ?: "未知"}")
                            appendLine("✓ 控件数: ${screen.nodes.size}")
                            appendLine("✓ 分辨率: ${screen.screenWidth}x${screen.screenHeight}")
                            appendLine("✓ 数据来源: ${screen.source}")
                        }
                    }
                    testResult = result.getOrElse { "测试失败: ${it.message}" }
                    testing = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("测试中…")
            } else {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("测试屏幕读取")
            }
        }

        // 测试结果
        testResult?.let { result ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (result.startsWith("✓")) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}

private fun appDetailsIntent(context: android.content.Context): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    "package:${context.packageName}".toUri(),
)

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    levelLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onAction)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 图标 + 状态点
            Box(contentAlignment = Alignment.BottomEnd) {
                Surface(
                    shape = CircleShape,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                // 状态小圆点
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (enabled) Color(0xFF4CAF50) else Color.Gray)
                        .then(
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape,
                            )
                        ),
                )
            }

            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    ) {
                        Text(
                            text = levelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 右侧状态
            if (enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = "已开启",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("去开启", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
