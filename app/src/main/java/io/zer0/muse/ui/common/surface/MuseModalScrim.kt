package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Shared visual scrim for modal surfaces; platform window dim remains disabled. */
@Composable
internal fun museModalScrimColor(): Color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f)

/** 导航栏区域始终透明；必须先留安全区再绘制遮罩，避免压暗手势条。 */
@Composable
internal fun Modifier.museModalScrim(): Modifier = navigationBarsPadding()
    .background(museModalScrimColor())
