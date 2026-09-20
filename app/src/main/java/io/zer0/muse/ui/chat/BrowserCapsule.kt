package io.zer0.muse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.ExternalLink
import compose.icons.tablericons.Globe
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.X
import io.zer0.muse.tools.BrowserManager
import io.zer0.muse.R
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.MuseGlassContainer
import io.zer0.muse.ui.theme.MuseIconSizes

/**
 * 浏览器状态胶囊 + 全屏查看器。
 *
 * 当前会话拥有浏览器入口时,对话页标题下方始终显示状态胶囊(未启动 / 加载中 /
 * 当前页面)。点击胶囊进入全屏浏览器视图,实时看到 AI 正在操作的页面。
 * 视觉遵循 mono 设计语言:黑白极简、圆角胶囊。
 *
 * v1.x: 每个会话独立 BrowserManager,胶囊只观察当前会话的实例;
 * [manager] 为 null 时(当前会话尚未准备好)不显示入口。
 */

internal enum class BrowserDisplayState {
    NOT_STARTED,
    BLANK_PAGE,
    LOADING,
    READY,
}

/** 纯逻辑状态映射,供 UI 与测试复用。 */
internal fun browserDisplayState(
    isActive: Boolean,
    isLoading: Boolean,
    url: String,
): BrowserDisplayState = when {
    isLoading -> BrowserDisplayState.LOADING
    !isActive || url.isBlank() -> BrowserDisplayState.NOT_STARTED
    url.equals("about:blank", ignoreCase = true) -> BrowserDisplayState.BLANK_PAGE
    else -> BrowserDisplayState.READY
}

/** 未启动时不占用聊天标题栏；浏览器真正开始工作后才显示入口。 */
internal fun shouldShowBrowserCapsule(state: BrowserDisplayState): Boolean =
    state != BrowserDisplayState.NOT_STARTED

/** 将页面标题/地址归一化为状态胶囊可展示的短标签。 */
internal fun browserPageLabel(title: String, url: String): String {
    title.trim().takeIf { it.isNotBlank() }?.let { return it }
    if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) return ""
    return runCatching { java.net.URI(url).host }
        .getOrNull()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }
        ?: url.trim()
}

@Composable
fun BrowserStatusCapsule(manager: BrowserManager?, modifier: Modifier = Modifier) {
    if (manager == null) return
    val isActive by manager.isActive.collectAsState()
    val isLoading by manager.isLoading.collectAsState()
    val url by manager.currentUrl.collectAsState()
    val title by manager.currentTitle.collectAsState()
    var showViewer by remember(manager) { mutableStateOf(false) }
    val displayState = browserDisplayState(isActive, isLoading, url)
    if (!shouldShowBrowserCapsule(displayState) && !showViewer) return
    val pageLabel = remember(title, url) { browserPageLabel(title, url) }
    val label = when (displayState) {
        BrowserDisplayState.NOT_STARTED -> stringResource(R.string.browser_status_not_started)
        BrowserDisplayState.BLANK_PAGE -> stringResource(R.string.browser_status_blank)
        BrowserDisplayState.LOADING -> stringResource(R.string.browser_status_loading)
        BrowserDisplayState.READY -> pageLabel.ifBlank {
            stringResource(R.string.browser_status_ready)
        }
    }.take(28)
    val capsuleDescription = stringResource(R.string.browser_status_cd, label)

    Surface(
        onClick = { showViewer = true },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.background,
        modifier = modifier.semantics { contentDescription = capsuleDescription },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            if (displayState == BrowserDisplayState.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.background,
                )
            } else {
                Icon(
                    imageVector = TablerIcons.Globe,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Icon(
                imageVector = TablerIcons.ExternalLink,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
            )
        }
    }

    if (showViewer) {
        BrowserViewerDialog(
            manager = manager,
            onDismiss = { showViewer = false },
        )
    }
}

/** 全屏浏览器查看器:顶部工具条 + WebView 实时画面。 */
@Composable
fun BrowserViewerDialog(manager: BrowserManager, onDismiss: () -> Unit) {
    val url by manager.currentUrl.collectAsState()
    val title by manager.currentTitle.collectAsState()
    val isActive by manager.isActive.collectAsState()
    val isLoading by manager.isLoading.collectAsState()
    val displayState = browserDisplayState(isActive, isLoading, url)

    // 显式入口首次打开时创建 WebView 并准备 about:blank。若 AI 已经开始导航,
    // showBlankPageIfNeeded 会保留现有加载,不会覆盖其页面或 SSRF 防护链路。
    LaunchedEffect(manager) {
        manager.showBlankPageIfNeeded()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 工具条
                MuseGlassContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        // 返回:收起查看器,浏览器保持 headless 继续供 AI 使用(胶囊保留)
                        // ST-06: 38dp → MuseIconSizes.touchTarget(48dp,MD3 触控目标红线)
                        IconButton(onClick = onDismiss, modifier = Modifier.size(MuseIconSizes.touchTarget)) {
                            Icon(
                                imageVector = TablerIcons.ArrowLeft,
                                contentDescription = stringResource(R.string.browser_viewer_collapse),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                        ) {
                            Text(
                                text = title.ifBlank { url.ifBlank { stringResource(R.string.browser_viewer_title) } },
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        IconButton(
                            onClick = {
                                runCatching { manager.reload() }
                            },
                            modifier = Modifier.size(MuseIconSizes.touchTarget),
                        ) {
                            Icon(
                                imageVector = TablerIcons.Refresh,
                                contentDescription = stringResource(R.string.browser_viewer_refresh),
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        // 关闭浏览器:销毁当前 WebView + 收起查看器,聊天页入口保留并回到“未启动”状态
                        // ST-06: 38dp → 48dp 触控目标
                        IconButton(onClick = {
                            manager.close()
                            onDismiss()
                        }, modifier = Modifier.size(MuseIconSizes.touchTarget)) {
                            Icon(
                                imageVector = TablerIcons.X,
                                contentDescription = stringResource(R.string.browser_viewer_close),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                // WebView 内容
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.FrameLayout(ctx).also { fl ->
                                manager.attachToDisplay(fl)
                            }
                        },
                        update = { fl ->
                            // 渲染进程崩溃重建后 WebView 实例变化,重新挂载
                            manager.attachToDisplay(fl)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (displayState == BrowserDisplayState.NOT_STARTED ||
                        displayState == BrowserDisplayState.BLANK_PAGE
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            tonalElevation = 2.dp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                            ) {
                                Icon(
                                    imageVector = TablerIcons.Globe,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(30.dp),
                                )
                                Text(
                                    text = stringResource(R.string.browser_viewer_blank_title),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                Text(
                                    text = stringResource(R.string.browser_viewer_blank_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 返回 headless:页面继续保留,AI 可继续操作
            manager.detachFromDisplay()
        }
    }
}
