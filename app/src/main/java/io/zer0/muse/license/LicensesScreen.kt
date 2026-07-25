package io.zer0.muse.license

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsItemRow
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.compose.koinInject

/**
 * Phase 13: 开源许可页。
 *
 * 设计(iOS 风格设置页"Open Source Licenses"):
 *  - TopAppBar 标题"开源许可"+ 返回箭头
 *  - 顶部统计卡: "Muse 使用 N 个开源依赖,均为 Apache 2.0 / MIT 协议"
 *  - 按协议分组的卡片:
 *    每组一张 Surface,组内是依赖列表(行式布局,点击进入协议全文)
 *  - 每个依赖行: 名称 + 版本 + 协议徽章 + "查看源码链接"右箭头
 *  - 点击协议组标题 → 弹窗显示完整协议文本(Monospace 字体,可滚动复制)
 *  - 点击链接 → 浏览器打开项目 URL
 *
 * 数据:
 *  - 依赖清单来自 [LicenseRepository.loadManifest](assets/licenses/manifest.json)
 *  - 协议全文按需懒加载([LicenseRepository.loadLicenseText])
 */
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    repository: LicenseRepository = koinInject(),
) {
    val context = LocalContext.current
    var manifest by remember { mutableStateOf<LicenseManifest?>(null) }
    var showLicenseDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var dialogText by remember { mutableStateOf<String?>(null) }
    var dialogLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        manifest = repository.loadManifest()
    }

    // M10: 用 LaunchedEffect 监听 showLicenseDialog 变化加载协议全文,
    // 替代手动 launch,避免快速点击多个协议时协程竞态导致文本错位
    LaunchedEffect(showLicenseDialog) {
        val key = showLicenseDialog ?: return@LaunchedEffect
        dialogText = null
        dialogLoading = true
        dialogText = repository.loadLicenseText(key.first)
        dialogLoading = false
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.licenses_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val m = manifest
        if (m == null) {
            // 加载中
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
            return@Scaffold
        }
        if (m.dependencies.isEmpty()) {
            // 解析失败 / manifest 为空
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Outlined.Info,
                    title = "暂无许可数据",
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 顶部统计卡片 ──
            item {
                HeaderSummary(manifest = m)
            }

            // ── 按协议分组的依赖列表 ──
            m.groupedByLicense().forEach { (licenseId, entries) ->
                item(key = "license_$licenseId") {
                    LicenseGroupSection(
                        licenseId = licenseId,
                        entries = entries,
                        onClickProtocolText = {
                            showLicenseDialog = licenseId to licenseId.toLicenseLabel()
                        },
                        onClickUrl = { url ->
                            // M11: 用 resultOf 替代 runCatching,捕获 ActivityNotFoundException 后提示用户
                            resultOf {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }.onError { msg, t ->
                                MuseToast.show("未找到可打开链接的应用")
                            }
                        },
                    )
                }
            }

            // ── 底部说明 ──
            item {
                FooterNote()
            }
        }
    }

    // 协议全文弹窗
    showLicenseDialog?.let { (licenseId, licenseLabel) ->
        LicenseTextDialog(
            title = licenseLabel,
            text = dialogText,
            isLoading = dialogLoading,
            onDismiss = {
                showLicenseDialog = null
                dialogText = null
            },
        )
    }
}

/**
 * 顶部统计卡: App 名 + 依赖总数 + 简短说明。
 */
@Composable
private fun HeaderSummary(manifest: LicenseManifest) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = MuseShapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
        ) {
            Text(
                text = "${manifest.appName} v${manifest.appVersion}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.licenses_total_count, manifest.totalCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Muse 闭源商业发布,所有第三方依赖均为宽松协议" +
                    "(Apache 2.0 / MIT / BSD / EPL-1.0)," +
                    "无需开源我们的代码,仅需保留本声明。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * 单个协议的分组(Surface 容器 + 标题行 + 内部依赖列表)。
 *
 * 点击标题行 → 弹窗显示该协议全文。
 * 点击行内"查看源码"按钮 → 浏览器打开。
 */
@Composable
private fun LicenseGroupSection(
    licenseId: String,
    entries: List<LicenseEntry>,
    onClickProtocolText: () -> Unit,
    onClickUrl: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 协议组标题(可点击展开协议全文)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClickProtocolText)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = licenseId.toLicenseLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                shape = MuseShapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = "${entries.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "查看协议",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }

        // 依赖列表(卡片组,沿用 SettingsGroup 视觉)
        SettingsGroup {
            entries.forEachIndexed { index, entry ->
                DependencyRow(
                    entry = entry,
                    onClickUrl = onClickUrl,
                )
                if (index < entries.lastIndex) {
                    SettingsGroupDivider()
                }
            }
        }
    }
}

/**
 * 单个依赖行(在 SettingsGroup 内使用)。
 *
 * 布局: [名称 + 版本] [版权小字] [查看源码右箭头]
 * 点击整行 → 若有 URL 则打开;没有 URL 则不响应(避免误点反馈)。
 */
@Composable
private fun DependencyRow(
    entry: LicenseEntry,
    onClickUrl: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (entry.hasUrl) it.clickable { onClickUrl(entry.url) } else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // 名称 + 版本号
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = entry.versionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // 版权(单行省略,过长会自动 ... 截断)
            Text(
                text = entry.copyright,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
            )
            if (entry.notes.isNotBlank()) {
                Text(
                    text = entry.notes,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    maxLines = 2,
                )
            }
        }
        if (entry.hasUrl) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "查看源码",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 底部说明: 法务/隐私链接占位(未来可加 Google Play 数据安全表格链接等)。
 */
@Composable
private fun FooterNote() {
    Text(
        text = "如对协议有疑问,可在 Muse GitHub 仓库的 LEGAL 目录查阅完整声明。" +
            "本页面数据每构建一次更新一次(generatedAt 标记时间)。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
    )
}

/**
 * 协议全文弹窗 — 单按钮关闭,长文本可滚动。
 */
@Composable
private fun LicenseTextDialog(
    title: String,
    text: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
            ) {
                when {
                    isLoading -> CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center),
                    )
                    text.isNullOrBlank() -> Text(
                        text = "未找到该协议文本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    else -> Column(
                        // v1.0.7 修复:去掉嵌套 verticalScroll(MuseDialog content 已自带滚动)
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = MuseMonoFontFamily,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        dismissText = null,
    )
}
