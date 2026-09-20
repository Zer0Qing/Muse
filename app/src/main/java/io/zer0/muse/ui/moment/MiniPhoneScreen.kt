package io.zer0.muse.ui.moment

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentMessage
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小手机（微信形态）。
 *
 * v1.0.90 重做：原来是"桌面上摆一排应用图标 + 各自跳去独立页面"，看起来像设置页而不是
 * 一台手机，应用之间的层级也说不清。现在按用户的要求做成微信的形态：
 *
 *  - 顶部状态栏（时间 / 信号 / 电量）保留"这是一台设备"的感觉；
 *  - 内容区四个 Tab：微信（消息）/ 通讯录 / 发现 / 我，底部 Tab 栏常驻；
 *  - 列表沿用微信的排版语言：白底分组卡 + 头像 + 主副标题 + 右侧时间 + 发丝分割线；
 *  - 配色全部走主题令牌（选中态用主题色），不写死微信绿，换主题时整机跟着变。
 *
 * 「发现」这一栏直接列小手机里的应用（朋友圈 / 相册 / 日记本 / 天气 / 速记），
 * 并遵守小手机设置里的显隐与排序 —— 设置页因此仍然生效。
 */
@Composable
fun MiniPhoneScreen(
    momentsCount: Int,
    unreadMoments: Int,
    unreadMessages: Int,
    wallpaper: String?,
    // v1.0.74: 小手机主人名字/头像(原"Muse 的手机"+ M 头像)
    userName: String = "Muse",
    userAvatarUri: String? = null,
    hiddenApps: Set<String> = emptySet(),
    appOrder: List<String> = emptyList(),
    /** 朋友圈动态与消息：用于「微信」的消息列表与「通讯录」的联系人列表。 */
    moments: List<MomentEntity> = emptyList(),
    momentMessages: List<MomentMessage> = emptyList(),
    onOpenMoments: () -> Unit,
    onOpenMessages: () -> Unit,
    // v1.0.74: 速记复用快速记录 / 相册=AI 生成图 / 天气 / 日记本
    onOpenQuickNotes: () -> Unit = {},
    onOpenAlbum: () -> Unit = {},
    onOpenWeather: () -> Unit = {},
    onOpenDiary: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSetWallpaper: (String) -> Unit,
    onPrepareImage: suspend (android.net.Uri) -> String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var appeared by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tab by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val appear by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = io.zer0.muse.ui.theme.MuseMotion.tween(
            io.zer0.muse.ui.theme.MuseAnimation.SLOW_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "miniPhoneAppear",
    )
    LaunchedEffect(Unit) { appeared = true }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallpaperLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { picked ->
            scope.launch {
                val dataUri = onPrepareImage(picked)
                if (dataUri != null) onSetWallpaper(dataUri)
            }
        }
    }

    val clockText = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    val timeFormat = remember { SimpleDateFormat(MuseDateFormats.TIME_WITH_MONTH_DAY, Locale.getDefault()) }
    val tabTitles = listOf(
        stringResource(R.string.miniphone_tab_chats),
        stringResource(R.string.miniphone_tab_contacts),
        stringResource(R.string.miniphone_tab_discover),
        stringResource(R.string.miniphone_tab_me),
    )

    // 联系人：从动态里去重出"发过朋友圈的人"，附带动态条数。
    val contacts = remember(moments) {
        moments.filter { it.senderName.isNotBlank() }
            .groupBy { it.senderId ?: it.senderName }
            .map { (key, posts) ->
                MiniPhoneContact(
                    key = key,
                    name = posts.first().senderName,
                    posts = posts.size,
                    lastAt = posts.maxOfOrNull { it.createdAt } ?: 0L,
                )
            }
            .sortedByDescending { it.lastAt }
    }

    val chatRows = remember(momentMessages, searchQuery) {
        momentMessages
            .filter { searchQuery.isBlank() || it.actorName.contains(searchQuery, ignoreCase = true) || it.content.contains(searchQuery, ignoreCase = true) }
            .sortedByDescending { it.createdAt }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .fillMaxSize(0.94f)
                .padding(horizontal = 14.dp)
                .shadow(24.dp, RoundedCornerShape(30.dp))
                .graphicsLayer {
                    scaleX = if (appeared) appear else 0.92f
                    scaleY = if (appeared) appear else 0.92f
                    translationY = if (appeared) 0f else 40f
                    alpha = if (appeared) appear else 0f
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 状态栏（装饰）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 18.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = clockText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Filled.BatteryFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp),
                    )
                }

                // ── 标题栏 ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tabTitles[tab],
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (tab == 0) {
                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    searching = !searching
                                    if (!searching) searchQuery = ""
                                },
                                modifier = Modifier.size(MuseIconSizes.touchTarget),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.miniphone_search_hint),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }

                // ── 内容区 ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                ) {
                    when (tab) {
                        0 -> ChatsTab(
                            rows = chatRows,
                            unread = unreadMessages,
                            searching = searching,
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onOpenChat = onOpenMessages,
                        )
                        1 -> ContactsTab(contacts = contacts, onOpen = onOpenMoments)
                        2 -> DiscoverTab(
                            hiddenApps = hiddenApps,
                            appOrder = appOrder,
                            unreadMoments = unreadMoments,
                            momentsCount = momentsCount,
                            onOpenMoments = onOpenMoments,
                            onOpenAlbum = onOpenAlbum,
                            onOpenDiary = onOpenDiary,
                            onOpenWeather = onOpenWeather,
                            onOpenQuickNotes = onOpenQuickNotes,
                        )
                        else -> MeTab(
                            userName = userName,
                            userAvatarUri = userAvatarUri,
                            phoneTitle = stringResource(R.string.miniphone_phone_title, userName),
                            momentsCount = momentsCount,
                            onChangeWallpaper = {
                                wallpaperLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }

                // ── 底部 Tab 栏 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiniPhoneTab(Icons.Filled.ChatBubble, tabTitles[0], tab == 0, unreadMessages) { tab = 0; searching = false }
                    MiniPhoneTab(Icons.AutoMirrored.Filled.MenuBook, tabTitles[1], tab == 1, 0) { tab = 1; searching = false }
                    MiniPhoneTab(Icons.Filled.CameraAlt, tabTitles[2], tab == 2, unreadMoments) { tab = 2; searching = false }
                    MiniPhoneTab(Icons.Filled.Settings, tabTitles[3], tab == 3, 0) { tab = 3; searching = false }
                }
            }
        }
    }
}

private data class MiniPhoneContact(
    val key: String,
    val name: String,
    val posts: Int,
    val lastAt: Long,
)

/** 微信 Tab：消息列表（朋友圈的赞与评论通知）。 */
@Composable
private fun ChatsTab(
    rows: List<MomentMessage>,
    unread: Int,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenChat: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (searching) {
            WeChatSearchBar(query = query, onQueryChange = onQueryChange)
        }
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MuseEmptyState(
                    title = stringResource(R.string.moment_messages_empty),
                    subtitle = null,
                )
            }
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = rows, key = { it.id }) { message ->
                WeChatRow(
                    title = message.actorName,
                    subtitle = if (message.content.isNotBlank()) message.content else message.momentContent,
                    time = formatRowTime(message.createdAt),
                    avatarUrl = message.actorAvatar,
                    avatarSeed = message.actorName,
                    highlight = message.id in rows.take(unread).map { it.id },
                    onClick = onOpenChat,
                )
            }
        }
    }
}

/** 通讯录 Tab：发过朋友圈的人。 */
@Composable
private fun ContactsTab(
    contacts: List<MiniPhoneContact>,
    onOpen: () -> Unit,
) {
    if (contacts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MuseEmptyState(
                title = stringResource(R.string.miniphone_contacts_empty),
                subtitle = null,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = contacts, key = { it.key }) { contact ->
            WeChatRow(
                title = contact.name,
                subtitle = stringResource(R.string.miniphone_contacts_count, contact.posts),
                time = formatRowTime(contact.lastAt),
                avatarUrl = null,
                avatarSeed = contact.name,
                highlight = false,
                onClick = onOpen,
            )
        }
    }
}

/** 发现 Tab：小手机里启用的应用（遵守设置里的显隐与排序）。 */
@Composable
private fun DiscoverTab(
    hiddenApps: Set<String>,
    appOrder: List<String>,
    unreadMoments: Int,
    momentsCount: Int,
    onOpenMoments: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenQuickNotes: () -> Unit,
) {
    val entries = remember(hiddenApps, appOrder) {
        listOf(
            DiscoverEntry(MiniPhoneApps.MOMENTS, Icons.Filled.CameraAlt, R.string.miniphone_app_moments, onOpenMoments, unreadMoments),
            DiscoverEntry(MiniPhoneApps.ALBUM, Icons.Filled.PhotoLibrary, R.string.miniphone_app_album, onOpenAlbum, 0),
            DiscoverEntry(MiniPhoneApps.DIARY, Icons.AutoMirrored.Filled.MenuBook, R.string.miniphone_app_diary, onOpenDiary, 0),
            DiscoverEntry(MiniPhoneApps.WEATHER, Icons.Filled.WbSunny, R.string.miniphone_app_weather, onOpenWeather, 0),
            DiscoverEntry(MiniPhoneApps.QUICK_NOTES, Icons.Filled.Edit, R.string.miniphone_app_notes, onOpenQuickNotes, 0),
        ).filterNot { it.id in hiddenApps }
            .sortedBy { entry ->
                val index = appOrder.indexOf(entry.id)
                if (index >= 0) index else Int.MAX_VALUE
            }
    }
    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MuseEmptyState(
                title = stringResource(R.string.miniphone_empty_title),
                subtitle = stringResource(R.string.miniphone_empty_hint),
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 10.dp),
    ) {
        WeChatGroup {
            entries.forEach { entry ->
                WeChatListRow(
                    icon = entry.icon,
                    title = stringResource(entry.labelRes),
                    badge = entry.badge,
                    onClick = entry.onClick,
                )
            }
        }
        if (momentsCount > 0) {
            Text(
                text = stringResource(R.string.miniphone_moments_count, momentsCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, top = 12.dp),
            )
        }
    }
}

private data class DiscoverEntry(
    val id: String,
    val icon: ImageVector,
    val labelRes: Int,
    val onClick: () -> Unit,
    val badge: Int,
)

/** 我 Tab：机主资料 + 换壁纸 + 设置。 */
@Composable
private fun MeTab(
    userName: String,
    userAvatarUri: String?,
    phoneTitle: String,
    momentsCount: Int,
    onChangeWallpaper: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 10.dp),
    ) {
        WeChatGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarBubble(avatarUrl = userAvatarUri, seed = userName, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = userName.ifBlank { phoneTitle },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (momentsCount > 0) {
                            stringResource(R.string.miniphone_moments_count, momentsCount)
                        } else {
                            stringResource(R.string.miniphone_moments_subtitle)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        WeChatGroup {
            WeChatListRow(
                icon = Icons.Filled.PhotoLibrary,
                title = stringResource(R.string.miniphone_me_wallpaper),
                badge = 0,
                onClick = onChangeWallpaper,
            )
            WeChatListRow(
                icon = Icons.Filled.Settings,
                title = stringResource(R.string.miniphone_app_settings),
                badge = 0,
                onClick = onOpenSettings,
            )
        }
    }
}

// ── 微信排版基础件 ───────────────────────────────────────────────────────────

/** 底部 Tab 项。 */
@Composable
private fun RowScope.MiniPhoneTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** 白底分组卡（微信的列表容器）。 */
@Composable
private fun WeChatGroup(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/** 发丝分割线，左侧缩进到头像之后（微信的排版习惯）。 */
@Composable
private fun WeChatDivider(startIndent: Int = 58) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

/** 头像气泡：有图用图，没图用名字首字。 */
@Composable
private fun AvatarBubble(
    avatarUrl: String?,
    seed: String,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            io.zer0.muse.ui.SmartImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = seed.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** 聊天/联系人行：头像 + 主副标题 + 右侧时间。 */
@Composable
private fun WeChatRow(
    title: String,
    subtitle: String,
    time: String,
    avatarUrl: String?,
    avatarSeed: String,
    highlight: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarBubble(avatarUrl = avatarUrl, seed = avatarSeed, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (highlight) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            }
        }
        WeChatDivider()
    }
}

/** 设置式行：图标 + 标题 + 可选红点 + 箭头。 */
@Composable
private fun WeChatListRow(
    icon: ImageVector,
    title: String,
    badge: Int,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        WeChatDivider(startIndent = 50)
    }
}

/** 搜索条（微信顶部的灰条）。 */
@Composable
private fun WeChatSearchBar(query: String, onQueryChange: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (query.isBlank()) {
                                Text(
                                    text = stringResource(R.string.miniphone_search_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        },
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.action_cancel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onQueryChange("") },
                ),
            )
        }
    }
}

/** 行内时间：今天显示时刻，其余显示月日。 */
private fun formatRowTime(createdAt: Long): String {
    if (createdAt <= 0L) return ""
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = createdAt }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "MM-dd"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(createdAt))
}
