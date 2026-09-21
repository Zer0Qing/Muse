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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowForward
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Copy
import compose.icons.tablericons.ExternalLink
import compose.icons.tablericons.Globe
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Trash
import compose.icons.tablericons.X
import io.zer0.muse.R
import io.zer0.muse.tools.BrowserManager
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.MuseFloatingActionMenu
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.MuseGlassContainer
import io.zer0.muse.ui.theme.MuseIconSizes
import kotlinx.coroutines.launch

/**
 * 浏览器状态胶囊 + 全屏查看器。
 *
 * 当前会话拥有浏览器入口时,对话页标题下方始终显示状态胶囊(未启动 / 加载中 /
 * 当前页面)。点击胶囊进入全屏浏览器视图,实时看到 AI 正在操作的页面。
 *
 * v1.0.90: 查看器按真正的浏览器做 —— 可编辑地址栏(输网址/搜索)、前进后退、
 * 刷新、清除 Cookie、系统浏览器打开、复制链接。关键是**用户手动浏览和 AI 操作
 * 共用同一个 WebView 实例**:用户改完页面后 AI 接着就能读到并继续操作。
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
                MuseSpinner(
                    size = 12.dp,
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

/** 全屏浏览器查看器:顶部浏览器工具条(地址栏/刷新/更多) + WebView 实时画面。 */
@Composable
fun BrowserViewerDialog(manager: BrowserManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val url by manager.currentUrl.collectAsState()
    val title by manager.currentTitle.collectAsState()
    val isActive by manager.isActive.collectAsState()
    val isLoading by manager.isLoading.collectAsState()
    val canGoBack by manager.canGoBack.collectAsState()
    val canGoForward by manager.canGoForward.collectAsState()
    val displayState = browserDisplayState(isActive, isLoading, url)

    // 地址栏:用户没在编辑时跟随当前页 URL;编辑时不打断输入。
    var addressInput by remember(manager) { mutableStateOf(url) }
    var addressFocused by remember(manager) { mutableStateOf(false) }
    var showMenu by remember(manager) { mutableStateOf(false) }
    LaunchedEffect(url, addressFocused) {
        if (!addressFocused && url.isNotBlank()) addressInput = url
    }

    val submitAddress: () -> Unit = {
        val target = addressInput.trim()
        if (target.isNotEmpty()) {
            // 提交后清焦点,让地址栏回到"跟随当前页"状态
            addressFocused = false
            scope.launch { manager.navigate(target) }
        }
    }

    // 显式入口首次打开时创建 WebView 并准备 about:blank。若 AI 已经开始导航,
    // showBlankPageIfNeeded 会保留现有加载,不会覆盖其页面或 SSRF 防护链路。
    LaunchedEffect(manager) {
        manager.showBlankPageIfNeeded()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 铺满整屏(含状态栏)：否则浏览器全屏层的底色/遮罩从状态栏下方开始。
            decorFitsSystemWindows = false,
        ),
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
                        MuseTactileButton(
                            icon = TablerIcons.ArrowLeft,
                            onClick = onDismiss,
                            contentDescription = stringResource(R.string.browser_viewer_collapse),
                            size = MuseIconSizes.touchTarget,
                            iconSize = 20.dp,
                        )
                        // 地址栏:可输入网址或搜索词。提交后走 manager.navigate,
                        // 与 AI 共用同一个 WebView —— 用户改完页面,AI 接着就能读到并继续操作。
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                if (isLoading) {
                                    MuseSpinner(
                                        size = 12.dp,
                                        strokeWidth = 1.5.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = TablerIcons.Globe,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                                BasicTextField(
                                    value = addressInput,
                                    onValueChange = { addressInput = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go,
                                    ),
                                    keyboardActions = KeyboardActions(onGo = { submitAddress() }),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp, vertical = 12.dp)
                                        .onFocusChanged { addressFocused = it.isFocused },
                                    decorationBox = { innerTextField ->
                                        if (addressInput.isBlank()) {
                                            Text(
                                                text = stringResource(R.string.browser_address_bar),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        innerTextField()
                                    },
                                )
                            }
                        }
                        MuseTactileButton(
                            icon = TablerIcons.Refresh,
                            onClick = { runCatching { manager.reload() } },
                            contentDescription = stringResource(R.string.browser_viewer_refresh),
                            size = MuseIconSizes.touchTarget,
                            iconSize = 19.dp,
                        )
                        // 更多:前进后退 / 关闭 / 清除 Cookie / 系统浏览器打开 / 复制链接
                        Box {
                            MuseTactileButton(
                                icon = Icons.Outlined.MoreVert,
                                onClick = { showMenu = true },
                                contentDescription = stringResource(R.string.chat_top_menu_cd),
                                size = MuseIconSizes.touchTarget,
                                iconSize = 20.dp,
                            )
                            if (showMenu) {
                                MuseFloatingActionMenu(
                                    items = listOf(
                                        MuseFloatingActionItem(
                                            key = "back",
                                            icon = TablerIcons.ArrowLeft,
                                            label = stringResource(R.string.browser_back),
                                            enabled = canGoBack,
                                            onClick = { manager.goBack() },
                                        ),
                                        MuseFloatingActionItem(
                                            key = "forward",
                                            icon = TablerIcons.ArrowForward,
                                            label = stringResource(R.string.browser_forward),
                                            enabled = canGoForward,
                                            onClick = { manager.goForward() },
                                        ),
                                        MuseFloatingActionItem(
                                            key = "clear_cookies",
                                            icon = TablerIcons.Trash,
                                            label = stringResource(R.string.browser_menu_clear_cookies),
                                            onClick = {
                                                manager.clearCookies()
                                                MuseToast.show(context.getString(R.string.browser_cookies_cleared))
                                            },
                                        ),
                                        MuseFloatingActionItem(
                                            key = "external",
                                            icon = TablerIcons.ExternalLink,
                                            label = stringResource(R.string.browser_menu_open_external),
                                            enabled = url.isNotBlank(),
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(
                                                        android.content.Intent(
                                                            android.content.Intent.ACTION_VIEW,
                                                            android.net.Uri.parse(url),
                                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                                    )
                                                }
                                            },
                                        ),
                                        MuseFloatingActionItem(
                                            key = "copy",
                                            icon = TablerIcons.Copy,
                                            label = stringResource(R.string.browser_menu_copy_link),
                                            enabled = url.isNotBlank(),
                                            onClick = {
                                                runCatching {
                                                    context.getSystemService(android.content.ClipboardManager::class.java)
                                                        ?.setPrimaryClip(
                                                            android.content.ClipData.newPlainText("muse-browser", url),
                                                        )
                                                }
                                            },
                                        ),
                                        // 关闭浏览器:销毁 WebView + 收起查看器,聊天页入口回到"未启动"状态
                                        MuseFloatingActionItem(
                                            key = "close",
                                            icon = TablerIcons.X,
                                            label = stringResource(R.string.browser_viewer_close),
                                            onClick = {
                                                manager.close()
                                                onDismiss()
                                            },
                                        ),
                                    ),
                                    onDismiss = { showMenu = false },
                                )
                            }
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
