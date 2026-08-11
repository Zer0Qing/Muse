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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch

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
    onOpenMoments: () -> Unit,
    onOpenMessages: () -> Unit,
    // v1.0.74: 备忘录复用快速记录 / 相册=AI 生成图 / 天气 / 日记本
    onOpenQuickNotes: () -> Unit = {},
    onOpenAlbum: () -> Unit = {},
    onOpenWeather: () -> Unit = {},
    onOpenDiary: () -> Unit = {},
    onSetWallpaper: (String) -> Unit,
    onPrepareImage: suspend (android.net.Uri) -> String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // v1.0.74 fix: 入场动画此前 target 恒为 1f 首帧即终值,实际不播放(死代码)。
    // 改为 appeared 翻转后才到 1f,先 0f 起播。
    var appeared by remember { mutableStateOf(false) }
    val appear by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "miniPhoneAppear",
    )
    LaunchedEffect(Unit) { appeared = true }

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
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
                // v1.0.74 fix: 固定高度缩短(实机比例奇怪),紧凑九宫格为主
                .height(430.dp)
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
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // v1.0.74: 头像用用户头像(无则渐变首字),标题用用户名字
                Box(
                    modifier = Modifier
                        .size(64.dp)
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
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${userName} 的手机",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (momentsCount > 0) "已记录 $momentsCount 条生活动态" else "记录生活的每一个瞬间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))

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
                        )
                        .clickable {
                            wallpaperLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
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
                    // 图标网格(2 行 x 3 列)
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            MiniAppIcon(
                                icon = Icons.Filled.Favorite,
                                label = "朋友圈",
                                enabled = true,
                                badgeCount = unreadMoments,
                                onClick = onOpenMoments,
                            )
                            MiniAppIcon(
                                icon = Icons.Filled.Notifications,
                                label = "消息",
                                enabled = true,
                                badgeCount = unreadMessages,
                                onClick = onOpenMessages,
                            )
                            MiniAppIcon(
                                icon = Icons.Filled.PhotoLibrary,
                                label = "相册",
                                enabled = false,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            MiniAppIcon(
                                icon = Icons.Filled.PhotoLibrary,
                                label = "相册",
                                enabled = true,
                                onClick = onOpenAlbum,
                            )
                            MiniAppIcon(
                                icon = Icons.Filled.Home,
                                label = "备忘录",
                                enabled = true,
                                onClick = onOpenQuickNotes,
                            )
                            MiniAppIcon(
                                icon = Icons.Filled.WbSunny,
                                label = "天气",
                                enabled = true,
                                onClick = onOpenWeather,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            MiniAppIcon(
                                icon = Icons.Filled.EditNote,
                                label = "日记本",
                                enabled = true,
                                onClick = onOpenDiary,
                            )
                            MiniAppIcon(
                                icon = Icons.Filled.Settings,
                                label = "设置",
                                enabled = false,
                            )
                            MiniAppIcon(
                                icon = Icons.Filled.Star,
                                label = "敬请期待",
                                enabled = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

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
