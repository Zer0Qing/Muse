package io.zer0.muse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertTriangle
import compose.icons.tablericons.Refresh
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.delay

/**
 * I3 区域错误边界(数据防御型):聊天区/输入区/列表区各自独立降级,单块渲染数据构建失败
 * 不拖垮整页,并自动重试一次。
 *
 * Compose 1.7.6 无组合期错误捕获 API(try-catch 包组合调用被编译器禁止,
 * AndroidComposeView.setErrorHandler 自 1.8 才引入),因此将区域渲染前的数据准备阶段
 * 隔离在可捕获的普通 lambda [data] 中:数据构建/解析/格式化抛异常时该区域独立降级。
 * 组合/布局/绘制期异常无区域级捕获通道,由 MuseCrashHandler 全局兜底上报。
 *
 * 降级后延迟 [autoRetryDelayMs] 自动重建一次,再次失败仅保留手动重试。
 */
@Composable
internal fun <T> RegionErrorBoundary(
    regionName: String,
    modifier: Modifier = Modifier,
    autoRetryDelayMs: Long = 3_000L,
    data: () -> T,
    render: @Composable (T) -> Unit,
) {
    var failed by remember(regionName) { mutableStateOf(false) }
    var generation by remember(regionName) { mutableIntStateOf(0) }
    var autoRetried by remember(regionName) { mutableStateOf(false) }
    val retry: () -> Unit = {
        autoRetried = true
        failed = false
        generation++
    }
    if (failed) {
        RegionFallback(modifier = modifier, onRetry = retry)
        if (!autoRetried) {
            LaunchedEffect(regionName, generation) {
                delay(autoRetryDelayMs)
                retry()
            }
        }
    } else {
        val dataResult = runCatching { data() }
        if (dataResult.isFailure) {
            // I3: 渲染数据构建失败(如解析/格式化异常)→ 该区域降级而非崩溃,业务原因见类 KDoc
            failed = true
        } else {
            val value = dataResult.getOrThrow()
            key(generation) {
                render(value)
            }
        }
    }
}

/** I3 降级占位:错误图标 + 文案 + 手动重试按钮。 */
@Composable
private fun RegionFallback(modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(MusePaddings.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = TablerIcons.AlertTriangle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(MusePaddings.screen),
        )
        Spacer(Modifier.height(MusePaddings.itemGap))
        Text(
            text = stringResource(R.string.chat_region_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.itemGap))
        TextButton(onClick = onRetry) {
            Icon(
                imageVector = TablerIcons.Refresh,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Spacer(Modifier.width(MusePaddings.tinyGap))
            Text(stringResource(R.string.chat_retry))
        }
    }
}
