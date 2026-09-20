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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentMessage
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.theme.MuseIconSizes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小手机（1:1 微信形态）。
 *
 * 结构完全按微信来：
 *  第一页「微信」  = 会话列表（与助手的往来：头像 / 名字 / 最后一条 / 时间 / 未读点）
 *  第二页「通讯录」= 助手联系人（分字母段 + 右侧字母索引）
 *  第三页「发现」  = 朋友圈（封面预览 + 未读红点）+ 相册
 *  第四页「我」    = 个人资料卡 + 全部杂项（相册 / 日记本 / 天气 / 速记 / 换壁纸 / 设置）
 *
 * 排版照搬微信：浅灰底 + 白底分组、16dp 行内边距、发丝分割线（左侧缩进到头像之后）、
 * 顶部居中标题栏、底部四 Tab 常驻。配色走主题令牌，不写死微信绿。
 */
@Composable
fun MiniPhoneScreen(
    momentsCount: Int,
    unreadMoments: Int,
    unreadMessages: Int,
    wallpaper: String?,
    userName: String = "Muse",
    userAvatarUri: String? = null,
    hiddenApps: Set<String> = emptySet(),
    appOrder: List<String> = emptyList(),
    moments: List<MomentEntity> = emptyList(),
    momentMessages: List<MomentMessage> = emptyList(),
    assistants: Map<String, AssistantEntity> = emptyMap(),
    onOpenMoments: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenChat: (assistantId: String, name: String, avatar: String?) -> Unit = { _, _, _ -> },
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
    val tabTitles = listOf(
        stringResource(R.string.miniphone_tab_chats),
        stringResource(R.string.miniphone_tab_contacts),
        stringResource(R.string.miniphone_tab_discover),
        stringResource(R.string.miniphone_tab_me),
    )

    // 会话：按"谁发的动态 / 谁赞评过"聚合，取最近一条作为预览 —— 就是微信会话列表的形状。
    val conversations = remember(moments, momentMessages, searchQuery) {
        buildConversations(moments, momentMessages)
            .filter {
                searchQuery.isBlank() ||
                    it.name.contains(searchQuery, ignoreCase = true) ||
                    it.preview.contains(searchQuery, ignoreCase = true)
            }
    }

    // 联系人：助手里发过动态的人；再补上尚未发动态的助手，保证通讯录不空。
    val contacts = remember(moments, assistants, searchQuery) {
        val fromMoments = moments.filter { it.senderName.isNotBlank() }
            .groupBy { it.senderId ?: it.senderName }
            .map { (key, posts) ->
                MiniPhoneContact(
                    key = key,
                    name = posts.first().senderName,
                    posts = posts.size,
                    lastAt = posts.maxOfOrNull { it.createdAt } ?: 0L,
                )
            }
        val known = fromMoments.map { it.key }.toSet()
        val extra = assistants.values
            .filter { it.id !in known }
            .map { MiniPhoneContact(key = it.id, name = it.name, posts = 0, lastAt = 0L) }
        (fromMoments + extra)
            .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
            .sortedBy { it.name }
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
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                // ── 状态栏 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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

                // ── 标题栏（微信：浅灰底 + 居中黑字 + 右侧操作）──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tabTitles[tab],
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tab == 0 || tab == 1) {
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
                        if (tab == 2) {
                            IconButton(
                                onClick = onOpenMoments,
                                modifier = Modifier.size(MuseIconSizes.touchTarget),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.miniphone_app_moments),
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
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    when (tab) {
                        0 -> ChatsTab(
                            rows = conversations,
                            searching = searching,
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onOpen = { row -> onOpenChat(row.key, row.name, row.avatar) },
                        )
                        1 -> ContactsTab(
                            contacts = contacts,
                            searching = searching,
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onOpen = onOpenMoments,
                        )
                        2 -> DiscoverTab(
                            userAvatarUri = userAvatarUri,
                            userName = userName,
                            unreadMoments = unreadMoments,
                            onOpenMoments = onOpenMoments,
                            onOpenAlbum = onOpenAlbum,
                        )
                        else -> MeTab(
                            userName = userName,
                            userAvatarUri = userAvatarUri,
                            momentsCount = momentsCount,
                            onOpenAlbum = onOpenAlbum,
                            onOpenDiary = onOpenDiary,
                            onOpenWeather = onOpenWeather,
                            onOpenQuickNotes = onOpenQuickNotes,
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
                        .padding(top = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiniPhoneTab(Icons.Filled.ChatBubble, tabTitles[0], tab == 0, unreadMessages) { tab = 0; searching = false }
                    MiniPhoneTab(Icons.AutoMirrored.Filled.MenuBook, tabTitles[1], tab == 1, 0) { tab = 1; searching = false }
                    MiniPhoneTab(Icons.Filled.Star, tabTitles[2], tab == 2, unreadMoments) { tab = 2; searching = false }
                    MiniPhoneTab(Icons.Filled.Person, tabTitles[3], tab == 3, 0) { tab = 3; searching = false }
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

private data class MiniPhoneConversation(
    val key: String,
    val name: String,
    val preview: String,
    val avatar: String?,
    val lastAt: Long,
)

/** 把动态与赞评通知折叠成"会话"：同一个人的内容合并，取最近一条做预览。 */
private fun buildConversations(
    moments: List<MomentEntity>,
    messages: List<MomentMessage>,
): List<MiniPhoneConversation> {
    val byActor = linkedMapOf<String, MiniPhoneConversation>()
    moments.filter { it.senderName.isNotBlank() }.forEach { moment ->
        val key = moment.senderId ?: moment.senderName
        val existing = byActor[key]
        if (existing == null || moment.createdAt > existing.lastAt) {
            byActor[key] = MiniPhoneConversation(
                key = key,
                name = moment.senderName,
                preview = moment.content,
                avatar = null,
                lastAt = moment.createdAt,
            )
        }
    }
    messages.forEach { message ->
        val existing = byActor[message.actorName]
        if (existing == null) {
            byActor[message.actorName] = MiniPhoneConversation(
                key = message.actorName,
                name = message.actorName,
                preview = if (message.content.isNotBlank()) message.content else message.momentContent,
                avatar = message.actorAvatar,
                lastAt = message.createdAt,
            )
        }
    }
    return byActor.values.sortedByDescending { it.lastAt }
}

/** 第一页「微信」：会话列表。 */
@Composable
private fun ChatsTab(
    rows: List<MiniPhoneConversation>,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (MiniPhoneConversation) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (searching) {
            WeChatSearchBar(query = query, onQueryChange = onQueryChange)
        }
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MuseEmptyState(title = stringResource(R.string.moment_messages_empty), subtitle = null)
            }
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = rows, key = { it.key }) { row ->
                WeChatRow(
                    avatarUrl = row.avatar,
                    avatarSeed = row.name,
                    title = row.name,
                    subtitle = row.preview,
                    time = formatRowTime(row.lastAt),
                    onClick = { onOpen(row) },
                )
            }
        }
    }
}

/** 第二页「通讯录」：助手联系人，按首字母分段。 */
@Composable
private fun ContactsTab(
    contacts: List<MiniPhoneContact>,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (searching) {
            WeChatSearchBar(query = query, onQueryChange = onQueryChange)
        }
        if (contacts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MuseEmptyState(title = stringResource(R.string.miniphone_contacts_empty), subtitle = null)
            }
            return
        }
        val listState = rememberLazyListState()
        val indexed = remember(contacts) { contacts.groupBy { sectionLetter(it.name) } }
        val letters = remember(indexed) { indexed.keys.sorted() }
        // 字母段表头也算一项，滚动定位时需要累加偏移
        val headerIndices = remember(indexed, letters) {
            var cursor = 0
            letters.associateWith { letter ->
                val at = cursor
                cursor += 1 + indexed[letter].orEmpty().size
                at
            }
        }
        val scope = rememberCoroutineScope()
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                letters.forEach { letter ->
                    item(key = "section_$letter") {
                        SectionHeader(letter)
                    }
                    items(items = indexed[letter].orEmpty(), key = { it.key }) { contact ->
                        WeChatRow(
                            avatarUrl = null,
                            avatarSeed = contact.name,
                            title = contact.name,
                            subtitle = if (contact.posts > 0) {
                                stringResource(R.string.miniphone_contacts_count, contact.posts)
                            } else {
                                stringResource(R.string.miniphone_moments_subtitle)
                            },
                            time = formatRowTime(contact.lastAt),
                            onClick = onOpen,
                        )
                    }
                }
            }
            // 右侧字母索引（微信通讯录的标志性元素）
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                letters.forEach { letter ->
                    Text(
                        text = letter,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    scope.launch {
                                        listState.scrollToItem(headerIndices[letter] ?: 0)
                                    }
                                },
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** 第三页「发现」：朋友圈（封面预览）+ 相册。 */
@Composable
private fun DiscoverTab(
    userAvatarUri: String?,
    userName: String,
    unreadMoments: Int,
    onOpenMoments: () -> Unit,
    onOpenAlbum: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.height(10.dp))
        WeChatGroup {
            // 朋友圈：左侧放自己的头像（微信就是这样）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenMoments,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarBubble(avatarUrl = userAvatarUri, seed = userName, size = 40.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    text = stringResource(R.string.miniphone_app_moments),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (unreadMoments > 0) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                ChevronRightIcon()
            }
            WeChatDivider(startIndent = 70)
            WeChatListRow(
                icon = Icons.Filled.PhotoLibrary,
                title = stringResource(R.string.miniphone_app_album),
                badge = 0,
                onClick = onOpenAlbum,
            )
        }
    }
}

/** 第四页「我」：个人资料卡 + 全部杂项。 */
@Composable
private fun MeTab(
    userName: String,
    userAvatarUri: String?,
    momentsCount: Int,
    onOpenAlbum: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenQuickNotes: () -> Unit,
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
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarBubble(avatarUrl = userAvatarUri, seed = userName, size = 58.dp)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = userName,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (momentsCount > 0) {
                            stringResource(R.string.miniphone_moments_count, momentsCount)
                        } else {
                            stringResource(R.string.miniphone_moments_subtitle)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                ChevronRightIcon()
            }
        }
        Spacer(Modifier.height(10.dp))
        WeChatGroup {
            WeChatListRow(Icons.Filled.PhotoLibrary, stringResource(R.string.miniphone_app_album), 0, onOpenAlbum)
            WeChatListRow(Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.miniphone_app_diary), 0, onOpenDiary)
            WeChatListRow(Icons.Filled.WbSunny, stringResource(R.string.miniphone_app_weather), 0, onOpenWeather)
            WeChatListRow(Icons.Filled.Edit, stringResource(R.string.miniphone_app_notes), 0, onOpenQuickNotes)
        }
        Spacer(Modifier.height(10.dp))
        WeChatGroup {
            WeChatListRow(Icons.Filled.Email, stringResource(R.string.miniphone_me_wallpaper), 0, onChangeWallpaper)
            WeChatListRow(Icons.Filled.Settings, stringResource(R.string.miniphone_app_settings), 0, onOpenSettings)
        }
    }
}

// ── 微信排版基础件 ───────────────────────────────────────────────────────────

/** 底部 Tab 项。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.MiniPhoneTab(
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
                modifier = Modifier.size(24.dp),
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

/** 发丝分割线，左侧缩进（微信的排版习惯）。 */
@Composable
private fun WeChatDivider(startIndent: Int = 70) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

/** 字母段表头（通讯录）。 */
@Composable
private fun SectionHeader(letter: String) {
    Text(
        text = letter,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChevronRightIcon() {
    Icon(
        imageVector = Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}

/** 头像：有图用图，没图用名字首字。 */
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

/** 会话/联系人行：头像 + 主副标题 + 右侧时间（微信会话行）。 */
@Composable
private fun WeChatRow(
    avatarUrl: String?,
    avatarSeed: String,
    title: String,
    subtitle: String,
    time: String,
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
            AvatarBubble(avatarUrl = avatarUrl, seed = avatarSeed, size = 48.dp)
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
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            ChevronRightIcon()
        }
        WeChatDivider()
    }
}

/** 搜索条（微信顶部灰条）。 */
@Composable
private fun WeChatSearchBar(query: String, onQueryChange: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    horizontalArrangement = Arrangement.Center,
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

/** 首字母段（数字/符号归到 #）。 */
private fun sectionLetter(name: String): String {
    val first = name.trim().firstOrNull() ?: return "#"
    return when {
        first in 'A'..'Z' -> first.toString()
        first in 'a'..'z' -> first.uppercase()
        else -> "#"
    }
}

/** 行内时间：今天显示时刻，其余显示月日。 */
private fun formatRowTime(createdAt: Long): String {
    if (createdAt <= 0L) return ""
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = createdAt }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "MM-dd"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(createdAt))
}

/**
 * 小手机微信聊天页的目标状态（仅用于导航层暂存）。
 */
internal data class MiniPhoneChatTarget(
    val assistantId: String,
    val name: String,
    val avatar: String?,
)
