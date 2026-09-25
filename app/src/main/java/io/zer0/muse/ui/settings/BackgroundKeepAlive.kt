package io.zer0.muse.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.icons.MuseIcons

/**
 * 后台保持运行引导 — 检测当前 ROM 厂商,给出专属的设置路径。
 *
 * 设计原则:
 *  - 被动入口:只在主动消息设置页提供,不主动弹窗打扰用户。
 *  - 主动消息/定时提醒依赖后台进程(进程被杀后由系统拉起),国内 ROM 默认
 *    禁止第三方应用自启动/后台活动,需要用户到系统设置里放行。
 *  - 部分厂商没有标准跳转 intent(如华为),引导用户手动操作 + 打开应用详情兜底。
 */

/** 厂商识别结果与引导内容。 */
data class KeepAliveGuide(
    /** 品牌显示名,如"小米"。 */
    val brandName: String,
    /** 引导步骤(用户手动操作路径)。 */
    val steps: List<String>,
    /** 尝试跳转系统设置;失败或无专用 intent 时回退应用详情页。返回是否成功发起跳转。 */
    val openSystemSettings: (Context) -> Boolean,
)

/**
 * v2.0: 依次尝试候选组件跳转;全部失败回退应用详情页。
 *
 * 所有 startActivity 均带 FLAG_ACTIVITY_NEW_TASK — 从弹窗 ContextThemeWrapper
 * 出发的 startActivity 不带该标志会抛异常并被静默呑噬(用户表现为"点了不跳转")。
 */
private fun attemptSettings(
    ctx: Context,
    candidates: List<ComponentName>,
    fallback: (Context) -> Boolean,
): Boolean {
    for (component in candidates) {
        val ok = runCatching {
            ctx.startActivity(
                Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        if (ok) return true
    }
    return fallback(ctx)
}

/** 检测当前设备厂商并返回对应引导。 */
fun detectKeepAliveGuide(context: Context): KeepAliveGuide {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val fallbackToDetails: (Context) -> Boolean = { ctx ->
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }
    return when {
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> KeepAliveGuide(
            brandName = "华为",
            steps = listOf(
                "打开系统「设置」→「应用」→「应用启动管理」",
                "找到 Muse，关闭「自动管理」，手动允许「自启动」「关联启动」「后台活动」",
                "在「设置」→「电池」→「更多电池设置」中关闭「休眠时始终保持网络连接」的限制",
            ),
            openSystemSettings = fallbackToDetails,
        )
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
            KeepAliveGuide(
                brandName = "小米",
                steps = listOf(
                    "打开「设置」→「应用设置」→「应用管理」→ 找到 Muse",
                    "开启「自启动」，在「省电策略」中选择「无限制」",
                    "或直接跳转系统自启动管理页（下方按钮）",
                ),
                openSystemSettings = { ctx ->
                    attemptSettings(
                        ctx,
                        listOf(
                            ComponentName(
                                "com.miui.securitycenter",
                                "com.miui.permcenter.autostart.AutoStartManagementActivity",
                            ),
                        ),
                        fallbackToDetails,
                    )
                },
            )
        manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") ->
            KeepAliveGuide(
                brandName = "OPPO",
                steps = listOf(
                    "打开「设置」→「应用」→「应用管理」→ 找到 Muse",
                    "开启「自启动」，在「电池」→「耗电管理」中选择「允许后台运行」",
                    "或直接跳转系统自启动管理页（下方按钮）",
                ),
                openSystemSettings = { ctx ->
                    attemptSettings(
                        ctx,
                        listOf(
                            ComponentName(
                                "com.coloros.safecenter",
                                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                            ),
                            ComponentName(
                                "com.oplus.safecenter",
                                "com.oplus.safecenter.permission.startup.StartupAppListActivity",
                            ),
                            ComponentName(
                                "com.coloros.safecenter",
                                "com.coloros.safecenter.startupapp.StartupAppListActivity",
                            ),
                        ),
                        fallbackToDetails,
                    )
                },
            )
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> KeepAliveGuide(
            brandName = "vivo",
            steps = listOf(
                "打开「设置」→「应用与权限」→「权限管理」→ 找到 Muse",
                "开启「自启动」，在「电池」→「后台高耗电」中选择「允许后台运行」",
                "或直接跳转系统自启动管理页（下方按钮）",
            ),
            openSystemSettings = { ctx ->
                attemptSettings(
                    ctx,
                    listOf(
                        ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                        ),
                        ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                        ),
                    ),
                    fallbackToDetails,
                )
            },
        )
        else -> KeepAliveGuide(
            brandName = "通用",
            steps = listOf(
                "在系统设置中允许 Muse 的「自启动 / 后台运行」",
                "在电池优化中把 Muse 设为「不限制」（下方按钮可直达）",
                "不要使用「强行停止」，直接划掉最近任务即可",
            ),
            openSystemSettings = { ctx ->
                val ok = runCatching {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrDefault(false)
                if (ok) true else fallbackToDetails(ctx)
            },
        )
    }
}

/** 引导弹窗:品牌 + 步骤 + 跳转按钮。 */
@Composable
fun KeepAliveGuideDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val guide = rememberKeepAliveGuide()
    MuseDialog(
        onDismissRequest = onDismiss,
        title = context.getString(R.string.keep_alive_dialog_title, guide.brandName),
        content = {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MuseIcons.lifebuoy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = context.getString(R.string.keep_alive_dialog_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    guide.steps.forEachIndexed { index, step ->
                        Text(
                            text = "${index + 1}. $step",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmText = context.getString(R.string.keep_alive_dialog_open),
        onConfirm = {
            val jumped = guide.openSystemSettings(context)
            onDismiss()
            if (!jumped) {
                io.zer0.muse.ui.common.feedback.MuseToast.show(
                    context.getString(R.string.keep_alive_manual_hint),
                    3500L,
                )
            }
        },
        dismissText = context.getString(R.string.keep_alive_dialog_dismiss),
        onDismiss = onDismiss,
    )
}

/** 记住厂商检测结果(进程内只检测一次)。 */
@Composable
private fun rememberKeepAliveGuide(): KeepAliveGuide {
    val context = LocalContext.current
    return androidx.compose.runtime.remember { detectKeepAliveGuide(context) }
}
