package io.zer0.muse.ui.chat

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import io.zer0.muse.ui.common.surface.MuseGlassContainer

/**
 * 浏览器状态胶囊 + 全屏查看器。
 *
 * AI 调用浏览器工具时,对话页标题下方显示一个状态胶囊(当前 URL / 加载动画);
 * 点击胶囊进入全屏浏览器视图,实时看到 AI 正在操作的页面。
 * 视觉遵循 mono 设计语言:黑白极简、圆角胶囊。
 *
 * v1.x: 每个会话独立 BrowserManager,胶囊只观察当前会话的实例;
 * [manager] 为 null 时(当前会话未创建浏览器)不显示任何内容。
 */

@Composable
fun BrowserStatusCapsule(manager: BrowserManager?, modifier: Modifier = Modifier) {
    if (manager == null) return
    val isActive by manager.isActive.collectAsState()
    val isLoading by manager.isLoading.collectAsState()
    val url by manager.currentUrl.collectAsState()
    val title by manager.currentTitle.collectAsState()
    var showViewer by remember { mutableStateOf(false) }

    if (!isActive && !showViewer) return

    // 胶囊:仅在有页面/加载中时展示
    if (!showViewer) {
        val host = remember(url) {
            runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url
        }
        val label = title.ifBlank { host }.take(28)
        Surface(
            onClick = { showViewer = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.background,
            modifier = modifier,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                if (isLoading) {
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
    val isLoading by manager.isLoading.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
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
                        IconButton(onClick = onDismiss, modifier = Modifier.size(38.dp)) {
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
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                imageVector = TablerIcons.Refresh,
                                contentDescription = stringResource(R.string.browser_viewer_refresh),
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        // 关闭浏览器:销毁 WebView + 收起查看器,胶囊一并消失
                        IconButton(onClick = {
                            manager.close()
                            onDismiss()
                        }, modifier = Modifier.size(38.dp)) {
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
