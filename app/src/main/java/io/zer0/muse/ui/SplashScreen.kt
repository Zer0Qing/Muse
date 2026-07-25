package io.zer0.muse.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseShapes

/**
 * iOS 风格品牌启动页 — warm-paper 背景 + Muse Logo 居中 + "Your AI Companion" 副标题。
 *
 * 显示 1.2 秒后 fade 过渡到主界面。
 * 配合 MainActivity 的 SplashScreen.setKeepOnScreenCondition 使用:
 *  - 系统 SplashScreen 在 MainActivity.onCreate 中 hold
 *  - 本 Compose 页面在 NavHost 组合后显示,1.2s fade-out 后回调 onFinished
 *
 * @param onFinished 启动页结束回调(触发 splashReady = true)
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
) {
    val alpha = androidx.compose.runtime.remember { Animatable(1f) }

    // 1.2s 后开始 fade-out(200ms)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(MuseAnimation.TACTILE_MS),
        )
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .alpha(alpha.value),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Muse Logo — 直接展示图标,不再叠加主题色背景块
            Image(
                painter = painterResource(R.drawable.ic_muse_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .size(80.dp)
                    .clip(MuseShapes.extraLarge),
            )

            Spacer(Modifier.height(20.dp))

            // 品牌名
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(Modifier.height(6.dp))

            // 副标题 — 使用更柔和的色调
            Text(
                text = stringResource(R.string.splash_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
