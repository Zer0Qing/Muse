package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.ui.chat.MiniPhoneChatRoute
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.moments.MiniPhoneMomentsRoute
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 小手机外壳。
 *
 * 三层:状态栏、内容区(桌面 / 某一页 / 聊天页 / 朋友圈)、底部 tab 栏;壁纸是整壳的底。
 * 桌面与页面在同一个壳里切换(桌面是「没选 tab」),tab 状态与当前会话都用 rememberSaveable 存,
 * 旋转、进程被回收再回来都还停在原来那一页。
 *
 * 聊天页与朋友圈都不进宿主导航:本模块只有一个「当前会话 id」与一个「朋友圈开没开」,
 * 任一成立就把内容区换成对应页面、同时收起 tab 栏,返回时清掉。
 * 朋友圈有两个入口:发现页开全部动态,我页的「我的朋友圈」只看自己发的。
 *
 * 发起群聊也是一层整页:消息页顶栏的「+」打开它,选够人点「完成」之后直接进那个群 ——
 * 建群页自己不碰库,建完拿到的会话 id 回到这里打开。
 *
 * @param chatContent 聊天页的渲染口,默认取本模块的 [MiniPhoneChatRoute];测试可注入替身
 * @param momentsContent 朋友圈的渲染口,第一个参数是「只看自己」模式;默认取 [MiniPhoneMomentsRoute]
 * @param onOpenCharacterChat 通讯录点一个人时要会话 id:已有会话就返回它,没有就地开一条;
 *   返回 null 表示没法开(角色 id 为空),外壳停在原页。导航在本壳,不交给上层
 * @param actions 页面上的用户动作(开关、会话动作、清空、开我的朋友圈、建群),由入口接到 ViewModel
 */
@Composable
fun MiniPhoneShell(
    state: ShellUiState,
    modifier: Modifier = Modifier,
    chatContent: @Composable (threadId: String, onBack: () -> Unit) -> Unit = { threadId, onBack ->
        MiniPhoneChatRoute(threadId = threadId, onBack = onBack)
    },
    momentsContent: @Composable (onlyMine: Boolean, onBack: () -> Unit) -> Unit = { onlyMine, onBack ->
        MiniPhoneMomentsRoute(onlyMine = onlyMine, onBack = onBack)
    },
    onOpenCharacterChat: suspend (characterId: String) -> String? = { null },
    actions: MiniPhoneShellActions = MiniPhoneShellActions(),
) {
    val colors = MiniPhoneTheme.colors
    var selectedTab by rememberSaveable { mutableStateOf<MiniPhoneTab?>(null) }
    var currentThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var momentsOpen by rememberSaveable { mutableStateOf(false) }
    var momentsOnlyMine by rememberSaveable { mutableStateOf(false) }
    var groupCreateOpen by rememberSaveable { mutableStateOf(false) }
    // 建群所选中的人用 list 存:点击先后就是入群顺序,成员页也按这个顺序排
    var groupSelection by rememberSaveable { mutableStateOf(listOf<String>()) }
    val openThread: (String) -> Unit = { currentThreadId = it }
    val closeThread: () -> Unit = { currentThreadId = null }
    val openMoments: (Boolean) -> Unit = { onlyMine ->
        momentsOnlyMine = onlyMine
        momentsOpen = true
    }
    // 通讯录点击要先问会话 id(可能要新建一条),所以得带一层协程作用域
    val scope = rememberCoroutineScope()
    val openContact: (String) -> Unit = { characterId ->
        scope.launch {
            onOpenCharacterChat(characterId)?.let { threadId -> currentThreadId = threadId }
        }
    }
    // 建群同样要等库里写完才拿得到会话 id,不能同步打开
    val createGroup: () -> Unit = {
        scope.launch {
            actions.onCreateGroup(groupSelection)?.let { created ->
                groupCreateOpen = false
                groupSelection = emptyList()
                currentThreadId = created
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.wallpaperTop, colors.wallpaperBottom))),
    ) {
        Column(Modifier.fillMaxSize()) {
            MiniPhoneStatusBar()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val threadId = currentThreadId
                when {
                    threadId != null -> chatContent(threadId, closeThread)
                    groupCreateOpen -> GroupCreatePage(
                        sections = state.contactSections,
                        selectedIds = groupSelection.toSet(),
                        onToggle = { characterId ->
                            groupSelection = if (characterId in groupSelection) {
                                groupSelection - characterId
                            } else {
                                groupSelection + characterId
                            }
                        },
                        onBack = {
                            groupCreateOpen = false
                            groupSelection = emptyList()
                        },
                        onDone = createGroup,
                    )
                    momentsOpen -> momentsContent(momentsOnlyMine) { momentsOpen = false }
                    else -> MiniPhonePageContent(
                        selectedTab = selectedTab,
                        state = state,
                        nav = PageNav(
                            selectTab = { selectedTab = it },
                            openMoments = { openMoments(false) },
                            openMyMoments = { openMoments(true) },
                            openThread = openThread,
                            openContact = openContact,
                            openGroupCreate = { groupCreateOpen = true },
                            goHome = { selectedTab = null },
                        ),
                        actions = actions,
                    )
                }
            }
            // 聊天页、朋友圈与建群页占满内容区,tab 栏让位给它们自己的底栏与输入栏
            if (currentThreadId == null && !momentsOpen && !groupCreateOpen) {
                MiniPhoneTabBar(selected = selectedTab, onSelect = { selectedTab = it })
            }
        }
    }
}

/**
 * 页面上全部用户动作,由入口接到 ViewModel。
 *
 * 聚成一份而不是逐个当参数:外壳的参数表里已经有四个渲染口与一个协程回调,
 * 再把七个回调平铺进来就该彻底看不出重点了;加一个动作也不必再动所有调用方。
 */
@Immutable
data class MiniPhoneShellActions(
    val onToggleAutoMoment: (Boolean) -> Unit = {},
    val onToggleChatProactive: (Boolean) -> Unit = {},
    val onToggleThreadPin: (threadId: String, pinned: Boolean) -> Unit = { _, _ -> },
    val onMarkThreadRead: (threadId: String) -> Unit = {},
    val onDeleteThread: (threadId: String) -> Unit = {},
    val onClearConversations: () -> Unit = {},
    /** 建群:参数是选中的角色 id(点击先后就是入群顺序),返回新会话 id;没建成时返回 null。 */
    val onCreateGroup: suspend (characterIds: List<String>) -> String? = { null },
)

/**
 * 内容区的六条跳转回调:切 tab、开朋友圈(全部 / 只看自己)、开会话、按角色开会话、开建群页、回桌面。
 *
 * 它们描述的是同一件事(用户要去哪),合成一个值比六个平铺参数好读。
 */
private data class PageNav(
    val selectTab: (MiniPhoneTab) -> Unit = {},
    val openMoments: () -> Unit = {},
    val openMyMoments: () -> Unit = {},
    val openThread: (String) -> Unit = {},
    val openContact: (String) -> Unit = {},
    val openGroupCreate: () -> Unit = {},
    val goHome: () -> Unit = {},
)

/** 内容区:桌面、某一页或聊天页。 */
@Composable
private fun MiniPhonePageContent(selectedTab: MiniPhoneTab?, state: ShellUiState, nav: PageNav, actions: MiniPhoneShellActions) {
    if (selectedTab == null) {
        MiniPhoneLauncher(onOpen = nav.selectTab)
        return
    }
    MiniPhonePageFrame(
        title = selectedTab.title,
        onGoHome = nav.goHome,
        headerAction = if (selectedTab == MiniPhoneTab.MESSAGES) {
            { GroupCreateEntry(onClick = nav.openGroupCreate) }
        } else {
            null
        },
    ) {
        when (selectedTab) {
            MiniPhoneTab.MESSAGES -> MessagesPage(
                threads = state.threads,
                onOpenThread = nav.openThread,
                // 空态里的按钮:直接切到通讯录,不用用户自己回去找 tab
                onGoContacts = { nav.selectTab(MiniPhoneTab.CONTACTS) },
                actions = ThreadMenuActions(
                    onTogglePin = actions.onToggleThreadPin,
                    onMarkRead = actions.onMarkThreadRead,
                    onDelete = actions.onDeleteThread,
                ),
            )
            MiniPhoneTab.CONTACTS -> ContactsPage(sections = state.contactSections, onOpenContact = nav.openContact)
            MiniPhoneTab.DISCOVER -> DiscoverPage(onOpenMoments = nav.openMoments)
            MiniPhoneTab.ME -> MePage(
                profile = state.profile,
                appVersionName = state.appVersionName,
                autoMomentEnabled = state.autoMomentEnabled,
                chatProactiveEnabled = state.chatProactiveEnabled,
                actions = MePageActions(
                    onToggleAutoMoment = actions.onToggleAutoMoment,
                    onToggleChatProactive = actions.onToggleChatProactive,
                    onOpenMyMoments = nav.openMyMoments,
                    onClearConversations = actions.onClearConversations,
                ),
            )
        }
    }
}

/**
 * 状态栏:左边时钟,右边信号 / Wi-Fi / 电量。
 *
 * 时钟对齐到下一分钟再刷新,不做每秒轮询 —— 秒不需要显示,少唤醒六十次。
 */
@Composable
private fun MiniPhoneStatusBar(modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val nowMs = rememberMinuteClock()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(dimens.statusBarHeight)
            .padding(horizontal = dimens.pageMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = MiniPhoneTimeFormat.clock(nowMs),
            style = typography.caption.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium),
            modifier = Modifier.testTag(MiniPhoneTags.STATUS_CLOCK),
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = MiniPhoneIcons.Signal,
            contentDescription = null,
            tint = colors.textPrimary,
            modifier = Modifier.size(dimens.statusIcon),
        )
        Spacer(Modifier.width(StatusIconGap))
        Icon(
            imageVector = MiniPhoneIcons.Wifi,
            contentDescription = null,
            tint = colors.textPrimary,
            modifier = Modifier.size(dimens.statusIcon),
        )
        Spacer(Modifier.width(StatusIconGap))
        Icon(
            imageVector = MiniPhoneIcons.Battery,
            contentDescription = null,
            tint = colors.textPrimary,
            modifier = Modifier.size(dimens.statusIcon),
        )
    }
}

/** 每分钟对齐刷新一次的「现在」。 */
@Composable
private fun rememberMinuteClock(): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMs = current
            delay(MINUTE_MS - current % MINUTE_MS + TICK_SLOP_MS)
        }
    }
    return nowMs
}

/**
 * 桌面:四列图标网格,底部 dock 留空。
 *
 * 图标表来自 [MiniPhoneApps],换一批入口不需要改布局。
 */
@Composable
private fun MiniPhoneLauncher(onOpen: (MiniPhoneTab) -> Unit, modifier: Modifier = Modifier) {
    val dimens = MiniPhoneTheme.dimens
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimens.pageMargin, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LauncherRowGap)) {
            MiniPhoneApps.launcher.chunked(MiniPhoneApps.COLUMNS).forEach { rowApps ->
                Row(Modifier.fillMaxWidth()) {
                    rowApps.forEach { app ->
                        LauncherTile(
                            app = app,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpen(app.tab) },
                        )
                    }
                    repeat(MiniPhoneApps.COLUMNS - rowApps.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // 底部 dock:本阶段留空,只占住高度,后续放常驻入口
        Spacer(Modifier.height(dimens.dockHeight))
    }
}

/** 桌面上的一个图标:纸片底 + 强调色图标 + 一行标签。 */
@Composable
private fun LauncherTile(app: MiniPhoneApp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag(MiniPhoneTags.launcher(app))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(dimens.launcherTile)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = app.icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(dimens.launcherIcon),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label,
            style = typography.timestamp.copy(color = colors.textPrimary),
        )
    }
}

/** 页面外框:标题 + 页头动作 + 回桌面按钮 + 内容。底色用纸色,保证列表文字压得住。 */
@Composable
private fun MiniPhonePageFrame(
    title: String,
    onGoHome: () -> Unit,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.pageHeaderHeight)
                .padding(horizontal = dimens.pageMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = typography.title.copy(color = colors.textPrimary),
                modifier = Modifier.testTag(MiniPhoneTags.PAGE_TITLE),
            )
            Spacer(Modifier.weight(1f))
            if (headerAction != null) {
                headerAction()
                Spacer(Modifier.width(PageHeaderActionGap))
            }
            Box(
                modifier = Modifier
                    .size(HomeButtonSize)
                    .clip(CircleShape)
                    .clickable(onClick = onGoHome)
                    .testTag(MiniPhoneTags.HOME_BUTTON),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Home,
                    contentDescription = "回到桌面",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

/**
 * 消息页顶栏的建群入口:一枚自己画的加号。
 *
 * 与回桌面按钮同尺寸同形制(圆形轻按区 + 自绘图标),颜色用强调色 ——
 * 它是这一页唯一的主动作,不该和导航图标长成一模一样。
 */
@Composable
private fun GroupCreateEntry(onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Box(
        modifier = Modifier
            .size(HomeButtonSize)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(MiniPhoneTags.GROUP_CREATE_ENTRY),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Plus,
            contentDescription = "发起群聊",
            tint = colors.accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 底部 tab 栏:四项,选中项用强调色。 */
@Composable
private fun MiniPhoneTabBar(selected: MiniPhoneTab?, onSelect: (MiniPhoneTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(dimens.hairline)
                .background(colors.divider),
        )
        Row(Modifier.fillMaxWidth().height(dimens.tabBarHeight)) {
            MiniPhoneTab.entries.forEach { tab ->
                val active = tab == selected
                val tint = if (active) colors.accent else colors.textSecondary
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(tab) }
                        .testTag(MiniPhoneTags.tab(tab)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = MiniPhoneApps.iconOf(tab),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(dimens.tabIcon),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.title,
                        style = typography.timestamp.copy(color = tint, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
                    )
                }
            }
        }
    }
}

private const val MINUTE_MS: Long = 60_000L
private const val TICK_SLOP_MS: Long = 40L

private val StatusIconGap = 6.dp
private val LauncherRowGap = 18.dp
private val HomeButtonSize = 32.dp
private val PageHeaderActionGap = 4.dp
