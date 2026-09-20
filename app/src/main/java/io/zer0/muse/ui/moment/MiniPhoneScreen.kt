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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseDateFormats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小手机桌面。
 *
 * v1.0.90 重做：原来是一张"应用图标清单"贴在一个圆角卡片里 —— 头部塞着头像/时间/
 * 换壁纸，中间是一片渐变底加三列图标，看起来像设置页而不是一台手机。
 *
 * 现在按"真手机桌面"来排：
 *  - 顶部状态栏（时间 + 信号/电量），先把"这是一台设备"的感觉立住；
 *  - 壁纸铺满整机，时间与日期做成桌面上的大号组件（真手机就是这么放的），
 *    点击时间组件直接进朋友圈；
 *  - 图标网格四列，图标语义与名字对齐（原来"备忘录"用房子图标、"消息"用铃铛）；
 *  - 头像与机主名收到底部一条，换壁纸放在它右边 —— 桌面本身不再被头部信息占掉一截。
 *
 * 壁纸缺省时用主题色渐变兜底，保证任何主题下都不会露出纯色板。
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

    val timeText = remember(now) {
        SimpleDateFormat(MuseDateFormats.TIME_WITH_MONTH_DAY, Locale.getDefault()).format(Date(now))
    }
    val clockText = remember(now) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            // 外层遮罩点击返回;手机内部消费点击防穿透
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(34.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .fillMaxSize(0.92f)
                .padding(horizontal = 16.dp)
                .shadow(24.dp, RoundedCornerShape(34.dp))
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
                // ── 状态栏（装饰）:时间 + 信号/电量 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
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
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Filled.BatteryFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // ── 壁纸 + 桌面 ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                ),
                            ),
                        ),
                ) {
                    if (!wallpaper.isNullOrBlank()) {
                        io.zer0.muse.ui.SmartImage(
                            model = wallpaper,
                            contentDescription = stringResource(R.string.miniphone_wallpaper_image_cd),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // 有壁纸时压一层暗调,保证图标与文字可读
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.28f)),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 18.dp),
                    ) {
                        // ── 桌面时间组件(点它进朋友圈) ──
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onOpenMoments,
                                )
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = clockText,
                                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                            )
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                            Text(
                                text = if (momentsCount > 0) {
                                    stringResource(R.string.miniphone_moments_count, momentsCount)
                                } else {
                                    stringResource(R.string.miniphone_moments_subtitle)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }

                        Spacer(Modifier.height(22.dp))

                        val appEntries = listOf(
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.MOMENTS,
                                icon = Icons.Filled.CameraAlt,
                                label = stringResource(R.string.miniphone_app_moments),
                                badgeCount = unreadMoments,
                                onClick = onOpenMoments,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.ALBUM,
                                icon = Icons.Filled.PhotoLibrary,
                                label = stringResource(R.string.miniphone_app_album),
                                onClick = onOpenAlbum,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.MESSAGES,
                                icon = Icons.Filled.ChatBubble,
                                label = stringResource(R.string.miniphone_app_messages),
                                badgeCount = unreadMessages,
                                onClick = onOpenMessages,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.DIARY,
                                icon = Icons.AutoMirrored.Filled.MenuBook,
                                label = stringResource(R.string.miniphone_app_diary),
                                onClick = onOpenDiary,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.WEATHER,
                                icon = Icons.Filled.WbSunny,
                                label = stringResource(R.string.miniphone_app_weather),
                                onClick = onOpenWeather,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.QUICK_NOTES,
                                icon = Icons.Filled.Edit,
                                label = stringResource(R.string.miniphone_app_notes),
                                onClick = onOpenQuickNotes,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.SETTINGS,
                                icon = Icons.Filled.Settings,
                                label = stringResource(R.string.miniphone_app_settings),
                                onClick = onOpenSettings,
                            ),
                        ).filterNot { it.id in hiddenApps }
                            .sortedBy { entry ->
                                val index = appOrder.indexOf(entry.id)
                                if (index >= 0) index else Int.MAX_VALUE
                            }

                        if (appEntries.isEmpty()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.miniphone_empty_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.miniphone_empty_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                appEntries.chunked(4).forEach { rowEntries ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        rowEntries.forEach { entry ->
                                            MiniAppIcon(
                                                icon = entry.icon,
                                                label = entry.label,
                                                badgeCount = entry.badgeCount,
                                                onClick = entry.onClick,
                                            )
                                        }
                                        repeat(4 - rowEntries.size) {
                                            Spacer(Modifier.width(60.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 机主信息 + 换壁纸 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary,
                                    ),
                                ),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!userAvatarUri.isNullOrBlank()) {
                            io.zer0.muse.ui.SmartImage(
                                model = userAvatarUri,
                                contentDescription = stringResource(R.string.miniphone_avatar_cd),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                text = userName.take(1),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.miniphone_phone_title, userName),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            wallpaperLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhotoLibrary,
                            contentDescription = stringResource(R.string.miniphone_wallpaper_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private data class MiniPhoneAppEntry(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val badgeCount: Int = 0,
    val onClick: () -> Unit,
)

/** 桌面应用图标(圆角方块 + 文字;badge 数字红点)。 */
@Composable
private fun MiniAppIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit = {},
    badgeCount: Int = 0,
) {
    Column(
        modifier = Modifier
            .width(60.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .background(
                    color = Color.White.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(15.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 1.dp, end = 1.dp)
                        .size(if (badgeCount > 9) 18.dp else 16.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (badgeCount > 9) "9+" else "$badgeCount",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onError,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}
