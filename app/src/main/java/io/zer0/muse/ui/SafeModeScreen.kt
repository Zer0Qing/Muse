package io.zer0.muse.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.ui.common.IosCardPress
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v2.0+: Safe Mode 极简 UI(上次崩溃后展示)。
 *
 * 参考 rikkahub 项目的 SafeModeActivity 设计,使用 muse 设计 token 体系:
 *  - 顶部:警告图标(Icons.Outlined.Warning) + "上次启动崩溃" 标题
 *  - 中部:崩溃时间 + 崩溃堆栈摘要(垂直滚动,等宽字体)
 *  - 底部:垂直排列的操作按钮(IosCardPress 触觉风格,无涟漪)
 *    a) 继续正常启动 — 清除 Safe Mode 标记并杀进程冷启动
 *    b) 查看完整崩溃日志 — 通过 Intent 分享崩溃日志文件
 *    c) 清除所有数据并重启 — ActivityManager.clearApplicationUserData
 *    d) 复制崩溃信息 — 复制到剪贴板
 *
 * 设计约束:
 *  - 不依赖 Koin(崩溃可能就是 Koin 启动失败),全部使用 MuseCrashHandler 静态方法
 *  - 崩溃信息从 SharedPreferences 读取(safe_mode_pending + crash_time + crash_trace),
 *    SP 为空时回退到崩溃日志文件 readLatestCrashLog
 *  - warm-paper 风格由外层 MuseTheme 提供(见 MainActivity 调用处),此处使用 MaterialTheme.colorScheme
 */
@Composable
fun SafeModeScreen() {
    val context = LocalContext.current

    // 优先从 SP 读取结构化崩溃信息(v2.0+),为空时回退到崩溃日志文件
    val crashTime = remember { MuseCrashHandler.getCrashTime(context) }
    val crashTrace = remember {
        MuseCrashHandler.getCrashTrace(context) ?: MuseCrashHandler.readLatestCrashLog(context)
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = MusePaddings.screen)
                .padding(vertical = MusePaddings.sectionGap)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(MusePaddings.sectionGap))

            // ── 顶部:警告图标 + 标题 ────────────────────────────────────────
            WarningHeader()

            Spacer(Modifier.height(MusePaddings.sectionGap))

            // ── 中部:崩溃信息卡片 ──────────────────────────────────────────
            CrashInfoCard(crashTime = crashTime, crashTrace = crashTrace)

            Spacer(Modifier.height(MusePaddings.sectionGap))

            // ── 底部:操作按钮(垂直排列,IosCardPress 触觉风格) ──────────────
            ActionButtons(
                crashTrace = crashTrace,
                onContinueNormal = {
                    // 清除 Safe Mode 标记(文件 flag + SP 持久化层)
                    MuseCrashHandler.clearSafeModeFlag(context)
                    // v1.91-hotfix 经验:必须杀进程!recreate() 仅重建 Activity,
                    // Application.onCreate 不会再次执行 → startKoin 不被调用
                    // → MainActivity by inject() 崩溃 "KoinApplication has been started"。
                    // 杀进程后系统下次冷启动会重新走 Application.onCreate → startKoin 正常执行。
                    (context as? Activity)?.finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                },
                onViewFullLog = { shareCrashLog(context) },
                onClearData = { clearApplicationData(context) },
                onCopyInfo = { copyCrashInfo(context, crashTime, crashTrace) },
            )

            Spacer(Modifier.height(MusePaddings.sectionGap))
        }
    }
}

/**
 * 顶部警告图标 + "上次启动崩溃" 标题。
 *
 * 圆形 errorContainer 背景 + Warning outline 图标,对标 iOS 警告样式。
 */
@Composable
private fun WarningHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(MusePaddings.itemGap))
        Text(
            text = stringResource(R.string.safe_mode_warning_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(MusePaddings.tightGap))
        Text(
            text = stringResource(R.string.main_activity_safe_mode_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 中部崩溃信息卡片 — 崩溃时间 + 堆栈摘要(等宽字体 + 垂直滚动)。
 */
@Composable
private fun CrashInfoCard(
    crashTime: String?,
    crashTrace: String?,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
        ) {
            // 崩溃时间行
            if (!crashTime.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.safe_mode_crash_time_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(MusePaddings.contentGap))
                    Text(
                        text = crashTime,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(MusePaddings.contentGap))
            }
            // 崩溃堆栈摘要
            Text(
                text = stringResource(R.string.safe_mode_stack_trace_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MusePaddings.tightGap))
            val traceText = crashTrace?.take(4000) ?: stringResource(R.string.safe_mode_no_crash_log)
            Text(
                text = traceText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MuseMonoFontFamily,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 操作按钮组(垂直排列,每个按钮使用 IosCardPress 触觉风格)。
 *
 * 每个按钮:图标 + 标题文字,左对齐,无涟漪按压反馈,对标 iOS 列表项。
 */
@Composable
private fun ActionButtons(
    crashTrace: String?,
    onContinueNormal: () -> Unit,
    onViewFullLog: () -> Unit,
    onClearData: () -> Unit,
    onCopyInfo: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        // a) 继续正常启动 — primary 强调色
        SafeModeActionButton(
            icon = Icons.Filled.Refresh,
            label = stringResource(R.string.safe_mode_continue_normal),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onContinueNormal,
        )
        // b) 查看完整崩溃日志
        SafeModeActionButton(
            icon = Icons.AutoMirrored.Outlined.Article,
            label = stringResource(R.string.safe_mode_view_full_log),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onViewFullLog,
            enabled = !crashTrace.isNullOrBlank(),
        )
        // c) 清除所有数据并重启 — error 强调色,危险操作
        SafeModeActionButton(
            icon = Icons.Outlined.CleaningServices,
            label = stringResource(R.string.safe_mode_clear_data_restart),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onClick = onClearData,
        )
        // d) 复制崩溃信息
        SafeModeActionButton(
            icon = Icons.Outlined.ContentCopy,
            label = stringResource(R.string.safe_mode_copy_crash_info),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onCopyInfo,
            enabled = !crashTrace.isNullOrBlank(),
        )
    }
}

/**
 * 单个 Safe Mode 操作按钮 — IosCardPress 包装,左侧图标 + 文字。
 *
 * @param icon 图标
 * @param label 按钮文字
 * @param containerColor 背景色
 * @param contentColor 文字和图标颜色
 * @param onClick 点击回调
 * @param enabled 是否可点击(禁用时降低透明度并吞掉点击事件)
 */
@Composable
private fun SafeModeActionButton(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IosCardPress(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.medium,
        containerColor = if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(MusePaddings.iconPadding))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 通过 Intent 分享崩溃日志文件(txt)。
 *
 * 优先取最新一份崩溃日志文件,通过 FileProvider 暴露 URI,
 * 用 ACTION_SEND 分享给外部应用(邮件 / 文件管理 / 聊天应用等)。
 */
private fun shareCrashLog(context: Context) {
    val logs = MuseCrashHandler.listCrashLogs(context)
    val logFile = logs.firstOrNull()
    if (logFile == null) {
        Toast.makeText(context, R.string.safe_mode_no_crash_log, Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "muse crash log — ${logFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, context.getString(R.string.safe_mode_view_full_log))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onFailure { e ->
        Logger.w("SafeModeScreen", "shareCrashLog failed", e)
        Toast.makeText(context, R.string.safe_mode_share_log_failed, Toast.LENGTH_SHORT).show()
    }
}

/**
 * 清除应用全部数据并重启。
 *
 * API 19+: ActivityManager.clearApplicationUserData 系统级清除(files / databases / SP / cache),
 *           自动杀进程,下次启动相当于全新安装。
 * 旧设备 fallback:手动删除核心目录后杀进程。
 */
private fun clearApplicationData(context: Context) {
    Logger.w("SafeModeScreen", "clearApplicationData — 用户主动清除全部数据")
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        @Suppress("DEPRECATION")
        val cleared = am.clearApplicationUserData()
        if (cleared) {
            // 系统会自动杀进程,这里兜底防返回
            (context as? Activity)?.finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        } else {
            // 用户取消或失败,fallback 到手动清理
            manualClearData(context)
        }
    } else {
        manualClearData(context)
    }
}

/** 旧设备 / clearApplicationUserData 失败时的手动清理 fallback。 */
private fun manualClearData(context: Context) {
    runCatching {
        context.filesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
        context.deleteSharedPreferences("muse_safe_mode")
    }
    (context as? Activity)?.finishAffinity()
    android.os.Process.killProcess(android.os.Process.myPid())
}

/**
 * 复制崩溃信息(时间 + 堆栈)到剪贴板。
 */
private fun copyCrashInfo(context: Context, crashTime: String?, crashTrace: String?) {
    val text = buildString {
        if (!crashTime.isNullOrBlank()) {
            appendLine("崩溃时间: $crashTime")
            appendLine()
        }
        if (!crashTrace.isNullOrBlank()) {
            appendLine("崩溃堆栈:")
            append(crashTrace)
        }
        if (isEmpty()) {
            append("(无崩溃信息)")
        }
    }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("muse crash info", text))
    Toast.makeText(context, R.string.main_activity_crash_log_copied, Toast.LENGTH_SHORT).show()
}
