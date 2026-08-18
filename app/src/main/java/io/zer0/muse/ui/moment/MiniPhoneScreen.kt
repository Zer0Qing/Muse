package io.zer0.muse.ui.moment

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.0.74: 小手机沉浸页。
 *
 * - 桌面壁纸:长按桌面/设置图标换壁纸
 * - 图标:朋友圈(未读红点计数)/ 消息(未读红点)/ 相册 / 备忘录 / 设置
 * - 消息图标点亮:进入消息中心
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
    // v1.0.74: 备忘录复用快速记录 / 相册=AI 生成图 / 天气 / 日记本
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
    // v1.0.74 fix: 入场动画此前 target 恒为 1f 首帧即终值,实际不播放(死代码)。
    // 改为 appeared 翻转后才到 1f,先 0f 起播。
    var appeared by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val appear by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            // v1.0.74 fix: 外层遮罩点击返回;Surface 内部用 clickable(enabled=true) 消费事件防穿透
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .widthIn(max = 430.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(min = 560.dp, max = 700.dp)
                .shadow(24.dp, RoundedCornerShape(28.dp))
                .graphicsLayer {
                    scaleX = if (appeared) appear else 0.9f
                    scaleY = if (appeared) appear else 0.9f
                    translationY = if (appeared) 0f else 40f
                    alpha = if (appeared) appear else 0f
                }
                // v1.0.74 fix: enabled=true 消费子区域点击,防穿透到外层返回;indication=null 无涟漪
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 头像 + 标题 + 时间 + 桌面操作,保持头部信息在一行内聚合。
                    Box(
                        modifier = Modifier
                            .size(52.dp)
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
                                contentDescription = "头像",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                text = userName.take(1),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${userName} 的手机",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = SimpleDateFormat("HH:mm  ·  MM月dd日", Locale.getDefault())
                                .format(Date(now)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            text = if (momentsCount > 0) {
                                "已记录 $momentsCount 条生活动态"
                            } else {
                                "记录生活的每一个瞬间"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            wallpaperLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhotoLibrary,
                            contentDescription = "更换壁纸",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // 桌面(壁纸背景)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                ),
                            ),
                        ),
                ) {
                    // 壁纸
                    if (!wallpaper.isNullOrBlank()) {
                        io.zer0.muse.ui.SmartImage(
                            model = wallpaper,
                            contentDescription = "桌面壁纸",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // 图标网格:只展示已实现且未被用户隐藏的小应用。
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(vertical = 16.dp, horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val appEntries = listOf(
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.MOMENTS,
                                icon = Icons.Filled.Favorite,
                                label = "朋友圈",
                                badgeCount = unreadMoments,
                                onClick = onOpenMoments,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.MESSAGES,
                                icon = Icons.Filled.Notifications,
                                label = "消息",
                                badgeCount = unreadMessages,
                                onClick = onOpenMessages,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.ALBUM,
                                icon = Icons.Filled.PhotoLibrary,
                                label = "相册",
                                onClick = onOpenAlbum,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.QUICK_NOTES,
                                icon = Icons.Filled.Home,
                                label = "备忘录",
                                onClick = onOpenQuickNotes,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.WEATHER,
                                icon = Icons.Filled.WbSunny,
                                label = "天气",
                                onClick = onOpenWeather,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.DIARY,
                                icon = Icons.Filled.EditNote,
                                label = "日记本",
                                onClick = onOpenDiary,
                            ),
                            MiniPhoneAppEntry(
                                id = MiniPhoneApps.SETTINGS,
                                icon = Icons.Filled.Settings,
                                label = "设置",
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
                                    text = "桌面暂无启用的 App",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "可在小手机设置中恢复",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                            }
                        } else {
                            appEntries.chunked(3).forEach { rowEntries ->
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    rowEntries.forEach { entry ->
                                        MiniAppIcon(
                                            icon = entry.icon,
                                            label = entry.label,
                                            enabled = true,
                                            badgeCount = entry.badgeCount,
                                            onClick = entry.onClick,
                                        )
                                    }
                                    repeat(3 - rowEntries.size) {
                                        Spacer(Modifier.width(64.dp))
                                    }
                                }
                            }
                        }
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

/** v1.0.74: 桌面应用图标(圆形 + 文字;badge 数字红点)。 */
@Composable
private fun MiniAppIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit = {},
    badgeCount: Int = 0,
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .clipClickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    color = if (enabled) {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    shape = RoundedCornerShape(16.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                },
                modifier = Modifier.size(24.dp),
            )
            // 未读红点(数字)
            if (badgeCount > 0 && enabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 2.dp)
                        .size(if (badgeCount > 9) 18.dp else 16.dp)
                        .background(Color(0xFFFF3B30), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (badgeCount > 9) "9+" else "$badgeCount",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White,
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
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            },
        )
    }
}

/** 灰置图标的点击处理(未启用时不可点)。 */
private fun Modifier.clipClickable(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.clickable(onClick = onClick) else this
